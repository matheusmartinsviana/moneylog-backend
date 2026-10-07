-- Índices das consultas da Etapa 2 (B5).
--  - ledger_postings (user_id, transaction_id): findByTransactions(userId, ids), chamada em todo GET/PUT/void e em toda
--    página da lista. O ix_ledger_postings_source é (source_type, source_id) e não serve a essa consulta.
--  - transactions (user_id, refund_of_id): soma dos reembolsos ativos (teto do reembolso, estorno e edição da despesa).
CREATE INDEX ix_ledger_postings_user_transaction ON ledger_postings (user_id, transaction_id);
CREATE INDEX ix_transactions_user_refund_of ON transactions (user_id, refund_of_id) WHERE refund_of_id IS NOT NULL;
