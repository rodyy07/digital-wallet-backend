package com.example.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class WalletService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public WalletService(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public static class WalletException extends RuntimeException {
        final HttpStatus status; final String code;
        WalletException(HttpStatus status, String code, String message) { super(message); this.status=status; this.code=code; }
    }
    private WalletException bad(String code, String message) { return new WalletException(HttpStatus.BAD_REQUEST,code,message); }
    private WalletException notFound(String message) { return new WalletException(HttpStatus.NOT_FOUND,"NOT_FOUND",message); }
    private WalletException conflict(String code, String message) { return new WalletException(HttpStatus.CONFLICT,code,message); }

    @Transactional
    public long createUser(ApiModels.CreateUser req) {
        return jdbc.queryForObject("INSERT INTO users(name,email,phone) VALUES (?,?,?) RETURNING id",
                Long.class, req.name(), req.email().toLowerCase(), req.phone());
    }

    @Transactional
    public long createWallet(long userId, ApiModels.CreateWallet req) {
        if (jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, userId) == 0)
            throw notFound("User not found");
        return jdbc.queryForObject("INSERT INTO accounts(user_id,name,account_type) VALUES (?,?,'WALLET') RETURNING id",
                Long.class, userId, req.name());
    }

    public ApiModels.BalanceResponse balance(long accountId) {
        ApiModels.BalanceResponse result = jdbc.query(
                "SELECT id,balance_paise,currency FROM accounts WHERE id=? AND account_type='WALLET'",
                rs -> rs.next() ? new ApiModels.BalanceResponse(rs.getLong(1),rs.getLong(2),rs.getString(3)) : null,
                accountId);
        if (result == null) throw notFound("Wallet not found");
        return result;
    }

    public List<Map<String,Object>> statement(long accountId, int limit, long beforeId) {
        if (limit < 1 || limit > 100) throw bad("INVALID_LIMIT","limit must be between 1 and 100");
        return jdbc.queryForList("""
            SELECT le.id AS entry_id, le.transfer_id, le.direction, le.amount_paise, le.created_at,
                   t.transfer_type, t.status, t.note
            FROM ledger_entries le JOIN transfers t ON t.id=le.transfer_id
            WHERE le.account_id=? AND (?=0 OR le.id<?)
            ORDER BY le.created_at DESC, le.id DESC LIMIT ?
            """, accountId, beforeId, beforeId, limit);
    }

    @Transactional
    public ApiModels.TransferResponse transfer(long userId, String idemKey, ApiModels.TransferRequest req) {
        if (idemKey == null || idemKey.isBlank()) throw bad("IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key header is required");
        if (req.sourceAccountId() == req.destinationAccountId()) throw bad("SAME_ACCOUNT","Source and destination must differ");
        String hash = hash(req.sourceAccountId()+":"+req.destinationAccountId()+":"+req.amountPaise()+":"+req.note());
        // Unique constraint is the concurrency arbiter. This transaction stores key and money movement atomically.
        try {
            jdbc.update("INSERT INTO idempotency_keys(user_id,idem_key,request_hash) VALUES (?,?,?)", userId, idemKey, hash);
        } catch (DuplicateKeyException e) {
            Map<String,Object> existing = jdbc.queryForMap("SELECT request_hash,state,response_status,response_body::text AS response_body FROM idempotency_keys WHERE user_id=? AND idem_key=?", userId, idemKey);
            if (!hash.equals(existing.get("request_hash"))) throw conflict("IDEMPOTENCY_KEY_REUSED","Same key was used with a different request body");
            if ("COMPLETED".equals(existing.get("state")) && existing.get("response_body") != null) {
                try { return mapper.readValue((String)existing.get("response_body"), ApiModels.TransferResponse.class); }
                catch (Exception ignored) { throw conflict("IDEMPOTENCY_RESULT_UNAVAILABLE","Saved idempotency response could not be decoded"); }
            }
            throw conflict("REQUEST_IN_PROGRESS","Matching request is still in progress; retry shortly");
        }
        // Ownership checks and lock acquisition use a stable ascending account-id order to reduce deadlocks.
        List<Long> ids = List.of(req.sourceAccountId(), req.destinationAccountId()).stream().sorted().toList();
        for (Long id : ids) {
            List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,user_id,account_type,balance_paise FROM accounts WHERE id=? FOR UPDATE", id);
            if (rows.isEmpty()) throw notFound("Account not found: "+id);
            Map<String,Object> row = rows.get(0);
            
if (id == req.sourceAccountId()
    && "WALLET".equals(row.get("account_type"))
    && ((Number) row.get("user_id")).longValue() != userId) {
throw new WalletException(
    HttpStatus.FORBIDDEN,
    "ACCOUNT_NOT_OWNED",
    "Source wallet is not owned by caller"
);
}

        }
        long sourceBalance = jdbc.queryForObject("SELECT balance_paise FROM accounts WHERE id=?", Long.class, req.sourceAccountId());
        if (sourceBalance < req.amountPaise()) throw bad("INSUFFICIENT_FUNDS","Wallet has insufficient funds");
        long transferId = jdbc.queryForObject("""
            INSERT INTO transfers(transfer_type,status,amount_paise,source_account_id,destination_account_id,note)
            VALUES ('PEER','COMPLETED',?,?,?,?) RETURNING id
            """, Long.class, req.amountPaise(), req.sourceAccountId(), req.destinationAccountId(), req.note());
        jdbc.update("INSERT INTO ledger_entries(transfer_id,account_id,direction,amount_paise) VALUES (?,?, 'DEBIT',?)",
                transferId, req.sourceAccountId(), req.amountPaise());
        jdbc.update("INSERT INTO ledger_entries(transfer_id,account_id,direction,amount_paise) VALUES (?,?, 'CREDIT',?)",
                transferId, req.destinationAccountId(), req.amountPaise());
        jdbc.update("UPDATE accounts SET balance_paise=balance_paise-? WHERE id=?", req.amountPaise(), req.sourceAccountId());
        jdbc.update("UPDATE accounts SET balance_paise=balance_paise+? WHERE id=?", req.amountPaise(), req.destinationAccountId());
        jdbc.update("INSERT INTO transfer_audit_logs(transfer_id,new_status,changed_by,reason) VALUES (?,'COMPLETED',?,'peer transfer')", transferId, "user:"+userId);
        ApiModels.TransferResponse response = new ApiModels.TransferResponse(transferId,"COMPLETED",req.amountPaise());
        try {
            jdbc.update("UPDATE idempotency_keys SET state='COMPLETED',response_status=201,response_body=?::jsonb WHERE user_id=? AND idem_key=?",
                    mapper.writeValueAsString(response), userId, idemKey);
        } catch (Exception e) { throw new IllegalStateException("Could not save idempotency response", e); }
        return response;
    }

    
@Transactional
public ApiModels.TransferResponse topup(
        String idemKey,
        ApiModels.TopupRequest req) {

    if (idemKey == null || idemKey.isBlank()) {
        throw bad(
            "IDEMPOTENCY_KEY_REQUIRED",
            "Idempotency-Key header is required"
        );
    }

    if (req.amountPaise() <= 0) {
        throw bad("INVALID_AMOUNT", "Amount must be positive");
    }

    long destination = req.destinationAccountId();

    // Lock the destination wallet.
    var rows = jdbc.queryForList(
        """
        SELECT id, account_type
        FROM accounts
        WHERE id = ?
        FOR UPDATE
        """,
        destination
    );

    if (rows.isEmpty()
            || !"WALLET".equals(rows.get(0).get("account_type"))) {
        throw notFound("Destination wallet not found");
    }

    long transferId = jdbc.queryForObject(
        """
        INSERT INTO transfers(
            transfer_type,
            status,
            amount_paise,
            source_account_id,
            destination_account_id,
            note
        )
        VALUES ('TOPUP', 'COMPLETED', ?, NULL, ?, ?)
        RETURNING id
        """,
        Long.class,
        req.amountPaise(),
        destination,
        req.note()
    );

    // Record the external funding source as a debit.
    jdbc.update(
        """
        INSERT INTO ledger_entries(
            transfer_id, account_id, direction, amount_paise
        )
        VALUES (?, ?, 'DEBIT', ?)
        """,
        transferId,
        systemAccount("CASH_IN"),
        req.amountPaise()
    );

    // Credit the user's wallet.
    jdbc.update(
        """
        INSERT INTO ledger_entries(
            transfer_id, account_id, direction, amount_paise
        )
        VALUES (?, ?, 'CREDIT', ?)
        """,
        transferId,
        destination,
        req.amountPaise()
    );

    jdbc.update(
        """
        UPDATE accounts
        SET balance_paise = balance_paise + ?
        WHERE id = ?
        """,
        req.amountPaise(),
        destination
    );

    return new ApiModels.TransferResponse(
        transferId, "COMPLETED", req.amountPaise()
    );
}


    @Transactional
    public ApiModels.TransferResponse withdraw(long userId, String idemKey, ApiModels.WithdrawRequest req) {
        long system = systemAccount("CASH_OUT");
        return systemTransfer(userId, idemKey, req.sourceAccountId(), system, req.amountPaise(), "WITHDRAWAL", req.note());
    }

    private long systemAccount(String type) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE account_type=? ORDER BY id LIMIT 1", Long.class, type);
    }

    private ApiModels.TransferResponse systemTransfer(long userId, String key, long source, long destination, long amount, String type, String note) {
        if (key == null || key.isBlank()) throw bad("IDEMPOTENCY_KEY_REQUIRED","Idempotency-Key header is required");
        if (amount <= 0) throw bad("INVALID_AMOUNT","Amount must be positive paise");
        List<Long> ids = List.of(source,destination).stream().sorted().toList();
        for (long id : ids) jdbc.queryForList("SELECT id FROM accounts WHERE id=? FOR UPDATE", id);
        if (jdbc.queryForObject("SELECT balance_paise FROM accounts WHERE id=?",Long.class,source) < amount)
            throw bad("INSUFFICIENT_FUNDS","Source account has insufficient funds");
        long transferId = jdbc.queryForObject("INSERT INTO transfers(transfer_type,status,amount_paise,source_account_id,destination_account_id,note) VALUES (?, 'COMPLETED',?,?,?,?) RETURNING id",
                Long.class,type,amount,source,destination,note);
        jdbc.update("INSERT INTO ledger_entries(transfer_id,account_id,direction,amount_paise) VALUES (?,?,'DEBIT',?)",transferId,source,amount);
        jdbc.update("INSERT INTO ledger_entries(transfer_id,account_id,direction,amount_paise) VALUES (?,?,'CREDIT',?)",transferId,destination,amount);
        jdbc.update("UPDATE accounts SET balance_paise=balance_paise-? WHERE id=?",amount,source);
        jdbc.update("UPDATE accounts SET balance_paise=balance_paise+? WHERE id=?",amount,destination);
        return new ApiModels.TransferResponse(transferId,"COMPLETED",amount);
    }

    private String hash(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
