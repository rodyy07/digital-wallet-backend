-- Optional local seed. Run after Flyway migrations:
INSERT INTO accounts(user_id,name,account_type,balance_paise)
SELECT NULL,'Cash-in','CASH_IN',0 WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_type='CASH_IN');
INSERT INTO accounts(user_id,name,account_type,balance_paise)
SELECT NULL,'Cash-out','CASH_OUT',0 WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_type='CASH_OUT');
INSERT INTO accounts(user_id,name,account_type,balance_paise)
SELECT NULL,'Fee revenue','FEE_REVENUE',0 WHERE NOT EXISTS (SELECT 1 FROM accounts WHERE account_type='FEE_REVENUE');
