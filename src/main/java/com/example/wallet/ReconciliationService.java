package com.example.wallet;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;
    public ReconciliationService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Scheduled(cron="${wallet.reconciliation.cron:0 0 2 * * *}")
    public void scheduledRun() { run(); }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public long run() {
        // A database row lock ensures only one reconciliation job runs at a time.
        if (jdbc.query("SELECT job_name FROM job_locks WHERE job_name='nightly-reconciliation' FOR UPDATE NOWAIT",
                rs -> rs.next() ? rs.getString(1) : null) == null) return -1;
        long runId=jdbc.queryForObject("INSERT INTO reconciliation_runs(status) VALUES ('RUNNING') RETURNING id",Long.class);
        jdbc.update("""
            INSERT INTO reconciliation_issues(run_id,check_code,entity_type,entity_id,expected_value,actual_value)
            SELECT ?, 'TRANSFER_UNBALANCED','TRANSFER',t.id,
                   COALESCE(SUM(le.amount_paise) FILTER (WHERE le.direction='DEBIT'),0)::text,
                   COALESCE(SUM(le.amount_paise) FILTER (WHERE le.direction='CREDIT'),0)::text
            FROM transfers t LEFT JOIN ledger_entries le ON le.transfer_id=t.id
            GROUP BY t.id
            HAVING COALESCE(SUM(le.amount_paise) FILTER (WHERE le.direction='DEBIT'),0)
                <> COALESCE(SUM(le.amount_paise) FILTER (WHERE le.direction='CREDIT'),0)
            """,runId);
        jdbc.update("""
            INSERT INTO reconciliation_issues(run_id,check_code,entity_type,entity_id,expected_value,actual_value)
            SELECT ?, 'BALANCE_MISMATCH','ACCOUNT',a.id,
              COALESCE(SUM(CASE WHEN le.direction='CREDIT' THEN le.amount_paise ELSE -le.amount_paise END),0)::text,
              a.balance_paise::text
            FROM accounts a LEFT JOIN ledger_entries le ON le.account_id=a.id
            GROUP BY a.id,a.balance_paise
            HAVING COALESCE(SUM(CASE WHEN le.direction='CREDIT' THEN le.amount_paise ELSE -le.amount_paise END),0) <> a.balance_paise
            """,runId);
        jdbc.update("""
            INSERT INTO reconciliation_issues(run_id,check_code,entity_type,entity_id,expected_value,actual_value)
            SELECT ?, 'MISSING_ENTRIES','TRANSFER',t.id,'at least 2','0 or 1'
            FROM transfers t LEFT JOIN ledger_entries le ON le.transfer_id=t.id
            GROUP BY t.id HAVING COUNT(le.id)<2
            """,runId);
        jdbc.update("UPDATE reconciliation_runs SET finished_at=now(),status='COMPLETED',checked_count=(SELECT count(*) FROM transfers),issue_count=(SELECT count(*) FROM reconciliation_issues WHERE run_id=?) WHERE id=?",runId,runId);
        return runId;
    }
}
