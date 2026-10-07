-- Dívida que nasce de consumo e divisão de despesa (T78, S-40, S-137, D1 §2.6.1).
--  1) debts.category_id: a dívida PAYABLE sem movimentação que nasce de consumo ("um amigo pagou meu jantar") lança
--     EXPENSE(cat) +X / PAYABLE −X em vez de EQUITY. Só PAYABLE WITHOUT_MOVEMENT tem categoria (ck_debts_category). FK composta
--     (id, user_id) como as demais (S-76).
--  2) A origem de uma dívida pode ser uma despesa dividida (EXPENSE com shares), e uma despesa tem uma dívida por parte: a
--     mesma movimentação de origem passa a servir a mais de uma dívida. O índice único de origin_transaction_id vira índice
--     comum; "uma dívida, uma origem" continua garantido pelo domínio (cada dívida tem um origin_transaction_id só) e a
--     despesa/empréstimo cria as dívidas na mesma operação.
ALTER TABLE debts ADD COLUMN category_id UUID;
ALTER TABLE debts
    ADD CONSTRAINT fk_debts_category_id_user FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id);
ALTER TABLE debts
    ADD CONSTRAINT ck_debts_category CHECK (category_id IS NULL OR (direction = 'PAYABLE' AND origin = 'WITHOUT_MOVEMENT'));

DROP INDEX uq_debts_origin_transaction;
CREATE INDEX ix_debts_origin_transaction ON debts (origin_transaction_id) WHERE origin_transaction_id IS NOT NULL;
