-- Índices da Etapa 4 (T75): leitura de pessoas e dívidas. Só índices: nenhuma tabela ou linha muda.
--  - debts (user_id, person_id): o saldo e o histórico de cada pessoa (Person.net, GET /v1/people/{id}) e a lista de dívidas por pessoa.
--  - transactions (debt_id): a origem e as quitações de uma dívida (o status e o saldo da dívida são derivados delas, S-41); sem o
--    índice cada leitura de dívida varreria as movimentações do usuário.
CREATE INDEX ix_debts_user_person ON debts (user_id, person_id);
CREATE INDEX ix_transactions_debt ON transactions (debt_id) WHERE debt_id IS NOT NULL;
