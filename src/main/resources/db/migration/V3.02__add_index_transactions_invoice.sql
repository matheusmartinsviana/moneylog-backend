-- Índice da Etapa 3 (T58): transactions.invoice_id (pagamento e crédito no cartão apontam para a linha da fatura).
-- A mudança de dias de fechamento/vencimento poda as linhas de fatura FUTURE sem referência (ADR-006 §7): o DELETE
-- confere a FK composta fk_transactions_invoice_id_user e a consulta de "ainda tem referência?" procura por invoice_id; sem
-- o índice as duas varreriam a tabela inteira. Só índice: nenhuma tabela ou linha muda.
CREATE INDEX ix_transactions_invoice ON transactions (invoice_id) WHERE invoice_id IS NOT NULL;
