-- Competência de renda (ADR-017): uma receita (INCOME) pode pertencer a um mês diferente do mês em que o dinheiro cai (salário dividido:
-- o adiantamento de setembro conta como renda de outubro). Só a competência da renda muda: o lançamento, o saldo e o caixa seguem na
-- data real (transaction_date / effective_date). Aditiva: NULL = o mês da data, como sempre; nenhuma linha existente muda.
ALTER TABLE transactions ADD COLUMN income_competence CHAR(7);
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_income_competence CHECK (
    income_competence IS NULL
    OR (type = 'INCOME' AND income_competence ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
        AND income_competence BETWEEN '2000-01' AND '2100-12'));

-- O posting do livro INCOME é o único cuja competência pode diferir do mês da data efetiva; nos demais livros vale a regra de sempre.
ALTER TABLE ledger_postings DROP CONSTRAINT ck_ledger_postings_competence;
ALTER TABLE ledger_postings ADD CONSTRAINT ck_ledger_postings_competence CHECK (
    competence = to_char(effective_date, 'YYYY-MM')
    OR (book_type = 'INCOME' AND competence ~ '^[0-9]{4}-(0[1-9]|1[0-2])$' AND competence BETWEEN '2000-01' AND '2100-12'));

-- Recorrência de receita: deslocamento da competência das ocorrências realizadas (0 = mesmo mês; 1 = mês seguinte).
ALTER TABLE recurring_transactions ADD COLUMN income_competence_offset_months INTEGER NOT NULL DEFAULT 0;
ALTER TABLE recurring_transactions ADD CONSTRAINT ck_recurring_income_competence_offset CHECK (
    income_competence_offset_months IN (0, 1) AND (income_competence_offset_months = 0 OR type = 'INCOME'));
