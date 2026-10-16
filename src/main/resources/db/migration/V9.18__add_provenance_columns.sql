ALTER TABLE recurring_transactions ADD COLUMN provenance_origin VARCHAR(10) NOT NULL DEFAULT 'INFORMED';
ALTER TABLE recurring_transactions ADD COLUMN provenance_confidence VARCHAR(9) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE recurring_transactions ADD CONSTRAINT ck_recurring_transactions_prov_origin CHECK (provenance_origin IN ('PRINT', 'PAYSLIP', 'CONTRACT', 'INFORMED'));
ALTER TABLE recurring_transactions ADD CONSTRAINT ck_recurring_transactions_prov_conf CHECK (provenance_confidence IN ('CONFIRMED', 'ESTIMATED'));

ALTER TABLE income_profiles ADD COLUMN provenance_origin VARCHAR(10) NOT NULL DEFAULT 'INFORMED';
ALTER TABLE income_profiles ADD COLUMN provenance_confidence VARCHAR(9) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE income_profiles ADD CONSTRAINT ck_income_profiles_prov_origin CHECK (provenance_origin IN ('PRINT', 'PAYSLIP', 'CONTRACT', 'INFORMED'));
ALTER TABLE income_profiles ADD CONSTRAINT ck_income_profiles_prov_conf CHECK (provenance_confidence IN ('CONFIRMED', 'ESTIMATED'));

ALTER TABLE assets ADD COLUMN provenance_origin VARCHAR(10) NOT NULL DEFAULT 'INFORMED';
ALTER TABLE assets ADD COLUMN provenance_confidence VARCHAR(9) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE assets ADD CONSTRAINT ck_assets_prov_origin CHECK (provenance_origin IN ('PRINT', 'PAYSLIP', 'CONTRACT', 'INFORMED'));
ALTER TABLE assets ADD CONSTRAINT ck_assets_prov_conf CHECK (provenance_confidence IN ('CONFIRMED', 'ESTIMATED'));

ALTER TABLE tracked_commitment_items ADD COLUMN provenance_origin VARCHAR(10) NOT NULL DEFAULT 'INFORMED';
ALTER TABLE tracked_commitment_items ADD COLUMN provenance_confidence VARCHAR(9) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE tracked_commitment_items ADD CONSTRAINT ck_tracked_commitment_items_prov_origin CHECK (provenance_origin IN ('PRINT', 'PAYSLIP', 'CONTRACT', 'INFORMED'));
ALTER TABLE tracked_commitment_items ADD CONSTRAINT ck_tracked_commitment_items_prov_conf CHECK (provenance_confidence IN ('CONFIRMED', 'ESTIMATED'));

ALTER TABLE card_purchase_shares ADD COLUMN provenance_origin VARCHAR(10) NOT NULL DEFAULT 'INFORMED';
ALTER TABLE card_purchase_shares ADD COLUMN provenance_confidence VARCHAR(9) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE card_purchase_shares ADD CONSTRAINT ck_card_purchase_shares_prov_origin CHECK (provenance_origin IN ('PRINT', 'PAYSLIP', 'CONTRACT', 'INFORMED'));
ALTER TABLE card_purchase_shares ADD CONSTRAINT ck_card_purchase_shares_prov_conf CHECK (provenance_confidence IN ('CONFIRMED', 'ESTIMATED'));

ALTER TABLE recurrence_shares ADD COLUMN provenance_origin VARCHAR(10) NOT NULL DEFAULT 'INFORMED';
ALTER TABLE recurrence_shares ADD COLUMN provenance_confidence VARCHAR(9) NOT NULL DEFAULT 'CONFIRMED';
ALTER TABLE recurrence_shares ADD CONSTRAINT ck_recurrence_shares_prov_origin CHECK (provenance_origin IN ('PRINT', 'PAYSLIP', 'CONTRACT', 'INFORMED'));
ALTER TABLE recurrence_shares ADD CONSTRAINT ck_recurrence_shares_prov_conf CHECK (provenance_confidence IN ('CONFIRMED', 'ESTIMATED'));
