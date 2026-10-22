package com.example.wallet;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("seed")
public class DevSeedRunner implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    public DevSeedRunner(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public void run(String... args) {
        jdbc.update("INSERT INTO accounts(user_id,name,account_type,balance_paise) SELECT NULL,'Cash-in','CASH_IN',0 WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE account_type='CASH_IN')");
        jdbc.update("INSERT INTO accounts(user_id,name,account_type,balance_paise) SELECT NULL,'Cash-out','CASH_OUT',0 WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE account_type='CASH_OUT')");
        jdbc.update("INSERT INTO accounts(user_id,name,account_type,balance_paise) SELECT NULL,'Fee revenue','FEE_REVENUE',0 WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE account_type='FEE_REVENUE')");
        if (jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)==0) {
            long u=jdbc.queryForObject("INSERT INTO users(name,email,phone,kyc_status) VALUES ('Demo User','demo@example.com','9999999999','VERIFIED') RETURNING id",Long.class);
            jdbc.update("INSERT INTO accounts(user_id,name,account_type,balance_paise) VALUES (?, 'Main wallet','WALLET',50000)",u);
        }
    }
}
