package com.moneylog.core.sk.ledger;

/**
 * Livros do razão (D1 §1.1). Convenção de sinal: débito {@code +}, crédito {@code −}.
 */
public enum BookType {
    /** Ativo: saldo positivo = dinheiro na conta. Id = accountId. */
    ACCOUNT(true),
    /** Passivo: saldo negativo = valor devido ao cartão. Id = cardId. */
    CARD(true),
    /** Ativo: "me devem". Id = debtId. */
    RECEIVABLE(true),
    /** Passivo: "eu devo". Id = debtId. */
    PAYABLE(true),
    /** Resultado (consumo). Id = categoryId; a competência vem da data efetiva do posting. */
    EXPENSE(true),
    /** Resultado (receita). Id = categoryId; a competência vem da data efetiva do posting. */
    INCOME(true),
    /** Patrimônio de abertura e ajustes. Sem id. */
    EQUITY(false);

    private final boolean requiresId;

    BookType(boolean requiresId) {
        this.requiresId = requiresId;
    }

    public boolean requiresId() {
        return requiresId;
    }

    /** Livros que compõem o patrimônio: Σ ACCOUNT + RECEIVABLE + CARD + PAYABLE (D1 §1.1). */
    public boolean isNetWorth() {
        return this == ACCOUNT || this == CARD || this == RECEIVABLE || this == PAYABLE;
    }

    /** Livros de resultado, lidos por competência. */
    public boolean isResult() {
        return this == EXPENSE || this == INCOME;
    }
}
