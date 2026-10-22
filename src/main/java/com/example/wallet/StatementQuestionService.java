package com.example.wallet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe starter: intentionally uses a deterministic parser, not an LLM-generated SQL path.
 * Add a provider adapter only after preserving the strict intent schema and caller account scope.
 */
@Service
public class StatementQuestionService {
    private final JdbcTemplate jdbc;
    public StatementQuestionService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public Map<String,Object> ask(long userId, String question) {
        if (question == null || question.length()>500) throw new WalletService.WalletException(
                org.springframework.http.HttpStatus.BAD_REQUEST,"UNSUPPORTED_QUESTION","Question must be 1-500 characters");
        String q=question.toLowerCase();
        if (q.contains("ignore previous instructions") || q.contains("all users") || q.contains("show all users"))
            return Map.of("answer","I can only answer supported questions about your own statement.","transfer_ids",java.util.List.of());
        // Supported narrow template: total sent/received in a calendar month. For any other intent, fail closed.
        Matcher m=Pattern.compile("how much did i (send|receive) .* (last month|this month)").matcher(q);
        if (!m.find()) throw new WalletService.WalletException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_QUESTION","Supported: total sent or received this month/last month. No arbitrary SQL is accepted.");
        LocalDate now=LocalDate.now();
        LocalDate from=m.group(2).equals("last month")?now.withDayOfMonth(1).minusMonths(1):now.withDayOfMonth(1);
        LocalDate to=from.plusMonths(1);
        String direction=m.group(1).equals("send")?"DEBIT":"CREDIT";
        var rows=jdbc.queryForList("""
            SELECT le.transfer_id, le.amount_paise
            FROM ledger_entries le JOIN accounts a ON a.id=le.account_id
            WHERE a.user_id=? AND le.direction=? AND le.created_at>=? AND le.created_at<?
            ORDER BY le.created_at DESC
            """,userId,direction,from.atStartOfDay().atOffset(java.time.ZoneOffset.UTC),to.atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
        long total=rows.stream().mapToLong(r->((Number)r.get("amount_paise")).longValue()).sum();
        return Map.of("answer","Total "+m.group(1)+" during "+from+" to "+to.minusDays(1)+": "+total+" paise",
                "total_paise",total,"transfer_ids",rows.stream().map(r->r.get("transfer_id")).distinct().toList());
    }
}
