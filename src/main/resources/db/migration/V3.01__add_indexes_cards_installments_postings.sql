-- Índices da Etapa 3 (T52): cartões, parcelas e lançamentos por parcela. Só índices: nenhuma tabela ou linha muda.
--  - transactions (user_id, card_id): CardInvoices.chainFor lê só as movimentações do cartão (findAllOfCard, S-135) e o
--    carregamento do cartão não varre o histórico inteiro do usuário.
--  - transaction_installments (user_id, transaction_id): as parcelas de um lote de movimentações (carregamento da lista e
--    do detalhe) e do usuário inteiro (carregamento único, S-79).
--  - ledger_postings (installment_id): o cancelamento e o estorno por parcela desativam os lançamentos de uma parcela
--    (T57); sem o índice a consulta varreria o razão.
CREATE INDEX ix_transactions_user_card ON transactions (user_id, card_id) WHERE card_id IS NOT NULL;
CREATE INDEX ix_installments_user_transaction ON transaction_installments (user_id, transaction_id);
CREATE INDEX ix_ledger_postings_installment ON ledger_postings (installment_id) WHERE installment_id IS NOT NULL;
