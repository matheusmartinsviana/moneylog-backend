-- Recorrência de despesa/receita com a conta "a definir": a previsão (valor, dia, categoria) existe e a conta e o meio de pagamento
-- são informados ao confirmar cada ocorrência. Conta e meio andam juntos (ou ambos nulos). Aditiva: nenhuma linha existente muda.
ALTER TABLE recurring_transactions DROP CONSTRAINT ck_recurring_transactions_target;
ALTER TABLE recurring_transactions ADD CONSTRAINT ck_recurring_transactions_target CHECK (
    (type = 'CARD_PURCHASE' AND card_id IS NOT NULL AND account_id IS NULL AND payment_method IS NULL)
    OR (type <> 'CARD_PURCHASE' AND card_id IS NULL AND ((account_id IS NULL) = (payment_method IS NULL))));
