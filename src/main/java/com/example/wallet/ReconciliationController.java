package com.example.wallet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/reconciliation")
public class ReconciliationController {
    private final ReconciliationService service;
    private final JdbcTemplate jdbc;
    public ReconciliationController(ReconciliationService service, JdbcTemplate jdbc) { this.service=service; this.jdbc=jdbc; }
    @PostMapping("/run") public Map<String,Object> run() { long id=service.run(); return Map.of("run_id",id,"status",id<0?"SKIPPED":"COMPLETED"); }
    @GetMapping("/runs/{id}") public Map<String,Object> report(@PathVariable long id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM reconciliation_runs WHERE id=?",id);
        if(rows.isEmpty()) throw new WalletService.WalletException(org.springframework.http.HttpStatus.NOT_FOUND,"NOT_FOUND","Run not found");
        return Map.of("run",rows.get(0),"issues",jdbc.queryForList("SELECT * FROM reconciliation_issues WHERE run_id=? ORDER BY id",id));
    }
}
