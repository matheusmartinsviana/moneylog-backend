-- FKs compostas (id, user_id) (S-76, T46): o banco recusa uma linha de negócio que aponte para um registro de OUTRO
-- usuário. É a segunda barreira de isolamento; a primeira continua sendo o filtro por user_id de todo gateway.
--
-- 1) Cada tabela pai ganha UNIQUE (id, user_id) (alvo das FKs compostas). credit_cards já tem
--    uq_credit_cards_id_user desde a V1.03.
-- 2) Cada FK simples entre tabelas de negócio é substituída por fk_<tabela>_<coluna>_user em (<coluna>, user_id).
--    Colunas anuláveis continuam opcionais: com a coluna NULL a FK composta não é verificada (MATCH SIMPLE).
-- 3) Ficam de fora: as FKs para users (já são do próprio user_id), credit_card_billing_rules.card_id (já é composta
--    desde a V1.03) e ledger_postings.book_id, que é polimórfico (ACCOUNT/CARD/RECEIVABLE/PAYABLE/EXPENSE/INCOME) e
--    continua garantido pela aplicação.
-- Só troca de constraints: nenhuma tabela ou linha é apagada. As tabelas novas das etapas seguintes seguem a regra.

-- 1) alvos das FKs compostas
ALTER TABLE accounts                 ADD CONSTRAINT uq_accounts_id_user                 UNIQUE (id, user_id);
ALTER TABLE categories               ADD CONSTRAINT uq_categories_id_user               UNIQUE (id, user_id);
ALTER TABLE credit_card_invoices     ADD CONSTRAINT uq_credit_card_invoices_id_user     UNIQUE (id, user_id);
ALTER TABLE persons                  ADD CONSTRAINT uq_persons_id_user                  UNIQUE (id, user_id);
ALTER TABLE debts                    ADD CONSTRAINT uq_debts_id_user                    UNIQUE (id, user_id);
ALTER TABLE transactions             ADD CONSTRAINT uq_transactions_id_user             UNIQUE (id, user_id);
ALTER TABLE transaction_installments ADD CONSTRAINT uq_transaction_installments_id_user UNIQUE (id, user_id);

-- 2) credit_cards -> accounts
ALTER TABLE credit_cards DROP CONSTRAINT fk_credit_cards_default_account;
ALTER TABLE credit_cards
    ADD CONSTRAINT fk_credit_cards_default_payment_account_id_user
        FOREIGN KEY (default_payment_account_id, user_id) REFERENCES accounts (id, user_id);

-- credit_card_invoices -> credit_cards
ALTER TABLE credit_card_invoices DROP CONSTRAINT fk_credit_card_invoices_card;
ALTER TABLE credit_card_invoices
    ADD CONSTRAINT fk_credit_card_invoices_card_id_user FOREIGN KEY (card_id, user_id) REFERENCES credit_cards (id, user_id);

-- debts -> persons, transactions (a origem continua DEFERRABLE INITIALLY DEFERRED, como na V1.06)
ALTER TABLE debts DROP CONSTRAINT fk_debts_person;
ALTER TABLE debts
    ADD CONSTRAINT fk_debts_person_id_user FOREIGN KEY (person_id, user_id) REFERENCES persons (id, user_id);
ALTER TABLE debts DROP CONSTRAINT fk_debts_origin_transaction;
ALTER TABLE debts
    ADD CONSTRAINT fk_debts_origin_transaction_id_user FOREIGN KEY (origin_transaction_id, user_id)
        REFERENCES transactions (id, user_id) DEFERRABLE INITIALLY DEFERRED;

-- transactions -> accounts, credit_cards, categories, credit_card_invoices, debts, transactions
ALTER TABLE transactions DROP CONSTRAINT fk_transactions_account;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_account_id_user FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id);
ALTER TABLE transactions DROP CONSTRAINT fk_transactions_counterpart_account;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_counterpart_account_id_user FOREIGN KEY (counterpart_account_id, user_id)
        REFERENCES accounts (id, user_id);
ALTER TABLE transactions DROP CONSTRAINT fk_transactions_card;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_card_id_user FOREIGN KEY (card_id, user_id) REFERENCES credit_cards (id, user_id);
ALTER TABLE transactions DROP CONSTRAINT fk_transactions_category;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_category_id_user FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id);
ALTER TABLE transactions DROP CONSTRAINT fk_transactions_invoice;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_invoice_id_user FOREIGN KEY (invoice_id, user_id)
        REFERENCES credit_card_invoices (id, user_id);
ALTER TABLE transactions DROP CONSTRAINT fk_transactions_debt;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_debt_id_user FOREIGN KEY (debt_id, user_id) REFERENCES debts (id, user_id);
ALTER TABLE transactions DROP CONSTRAINT fk_transactions_refund_of;
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_refund_of_id_user FOREIGN KEY (refund_of_id, user_id) REFERENCES transactions (id, user_id);

-- transaction_installments -> transactions, credit_card_invoices
ALTER TABLE transaction_installments DROP CONSTRAINT fk_installments_transaction;
ALTER TABLE transaction_installments
    ADD CONSTRAINT fk_transaction_installments_transaction_id_user FOREIGN KEY (transaction_id, user_id)
        REFERENCES transactions (id, user_id);
ALTER TABLE transaction_installments DROP CONSTRAINT fk_installments_invoice;
ALTER TABLE transaction_installments
    ADD CONSTRAINT fk_transaction_installments_invoice_id_user FOREIGN KEY (invoice_id, user_id)
        REFERENCES credit_card_invoices (id, user_id);

-- ledger_postings -> transactions, transaction_installments (book_id fica fora: é polimórfico)
ALTER TABLE ledger_postings DROP CONSTRAINT fk_ledger_postings_transaction;
ALTER TABLE ledger_postings
    ADD CONSTRAINT fk_ledger_postings_transaction_id_user FOREIGN KEY (transaction_id, user_id)
        REFERENCES transactions (id, user_id);
ALTER TABLE ledger_postings DROP CONSTRAINT fk_ledger_postings_installment;
ALTER TABLE ledger_postings
    ADD CONSTRAINT fk_ledger_postings_installment_id_user FOREIGN KEY (installment_id, user_id)
        REFERENCES transaction_installments (id, user_id);
