ALTER TABLE credit_cards ADD COLUMN auto_debit BOOLEAN;
ALTER TABLE credit_cards ADD COLUMN brand VARCHAR(40);
ALTER TABLE credit_cards ADD COLUMN best_purchase_day SMALLINT;
ALTER TABLE credit_cards ADD COLUMN last_digits VARCHAR(24) NOT NULL DEFAULT '';
ALTER TABLE credit_cards ADD CONSTRAINT ck_credit_cards_best_purchase_day CHECK (best_purchase_day IS NULL OR best_purchase_day BETWEEN 1 AND 31);

ALTER TABLE transactions ADD COLUMN last_digits CHAR(4);
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_last_digits CHECK (last_digits IS NULL OR (type = 'CARD_PURCHASE' AND last_digits ~ '^[0-9]{4}$'));

ALTER TABLE notifications DROP CONSTRAINT ck_notifications_kind;
ALTER TABLE notifications ALTER COLUMN kind TYPE VARCHAR(40);
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_kind CHECK (kind IN ('INVOICE_DUE', 'DEBT_COLLECT', 'DEBT_PAYABLE_DUE', 'RECURRENCE_PENDING',
    'COMMITTED_NEXT_MONTH', 'DAILY_SUMMARY', 'REMINDER', 'GOAL_DEADLINE', 'GROUPED', 'INVOICE_DUE_AUTODEBIT_OFF', 'NEGATIVE_MONTH_PROJECTED',
    'NEW_DEBT_BEFORE_FINANCING', 'CARD_CASH_OUT_COST', 'SUBSCRIPTION_OVERLAP', 'SUBSCRIPTION_UNUSED', 'WEEKLY_CHARGE', 'THIRD_PARTY_OVERDUE',
    'BALANCE_BELOW_REAL_ESTATE', 'DOCUMENT_DIVERGENCE'));

ALTER TABLE notification_settings ALTER COLUMN disabled_kinds TYPE VARCHAR(800);
ALTER TABLE notification_settings ADD COLUMN subscription_unused_days INTEGER;
ALTER TABLE notification_settings ADD COLUMN invoice_due_lead_days INTEGER NOT NULL DEFAULT 3;
ALTER TABLE notification_settings ADD COLUMN document_divergence_tolerance_cents BIGINT NOT NULL DEFAULT 0;
ALTER TABLE notification_settings ADD CONSTRAINT ck_notification_settings_unused_days CHECK (subscription_unused_days IS NULL OR subscription_unused_days BETWEEN 1 AND 3650);
ALTER TABLE notification_settings ADD CONSTRAINT ck_notification_settings_lead_days CHECK (invoice_due_lead_days BETWEEN 1 AND 30);
ALTER TABLE notification_settings ADD CONSTRAINT ck_notification_settings_tolerance CHECK (document_divergence_tolerance_cents >= 0);
