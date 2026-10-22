package com.example.wallet;

import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class WalletController {
    private final WalletService service;
    private final JdbcTemplate jdbc;
    public WalletController(WalletService service, JdbcTemplate jdbc) { this.service=service; this.jdbc=jdbc; }

    @PostMapping("/users")
    public Map<String,Object> createUser(@Valid @RequestBody ApiModels.CreateUser req) {
        long id=service.createUser(req); return Map.of("id",id,"name",req.name(),"email",req.email());
    }
    @PostMapping("/users/{userId}/wallets")
    public Map<String,Object> createWallet(@PathVariable long userId,@Valid @RequestBody ApiModels.CreateWallet req) {
        long id=service.createWallet(userId,req); return Map.of("id",id,"user_id",userId,"name",req.name(),"currency","INR");
    }
    @GetMapping("/wallets/{id}/balance")
    public ApiModels.BalanceResponse balance(@PathVariable long id) { return service.balance(id); }
    @GetMapping("/wallets/{id}/statement")
    public List<Map<String,Object>> statement(@PathVariable long id,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") long beforeId) {
        return service.statement(id,limit,beforeId);
    }
    @PostMapping("/transfers")
    public ApiModels.TransferResponse transfer(@RequestHeader("X-User-Id") long userId,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ApiModels.TransferRequest req) {
        return service.transfer(userId,key,req);
    }
    @PostMapping("/transfers/topup")
    public ApiModels.TransferResponse topup(@RequestHeader("Idempotency-Key") String key,@Valid @RequestBody ApiModels.TopupRequest req) {
        return service.topup(key,req);
    }
    @PostMapping("/transfers/withdraw")
    public ApiModels.TransferResponse withdraw(@RequestHeader("X-User-Id") long userId,@RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody ApiModels.WithdrawRequest req) { return service.withdraw(userId,key,req); }

    @GetMapping("/transfers/{id}")
    public Map<String,Object> transfer(@PathVariable long id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM transfers WHERE id=?",id);
        if(rows.isEmpty()) throw new WalletService.WalletException(org.springframework.http.HttpStatus.NOT_FOUND,"NOT_FOUND","Transfer not found");
        return Map.of("transfer",rows.get(0),"entries",jdbc.queryForList("SELECT * FROM ledger_entries WHERE transfer_id=? ORDER BY id",id),
                "audit",jdbc.queryForList("SELECT * FROM transfer_audit_logs WHERE transfer_id=? ORDER BY id",id));
    }
}
