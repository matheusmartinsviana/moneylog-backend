package com.moneylog.core.importing.domain;

/** Marcas que a importação põe numa linha para a revisão (S-167, S-168). */
public enum ImportLineFlag {
    /** Extrato de cartão: pagamento da fatura. Rejeitada por padrão (o pagamento vem da conta ou do app). */
    LOOKS_LIKE_INVOICE_PAYMENT(false),
    /** Extrato de cartão: parece uma parcela de compra já registrada. Exige decisão. */
    LOOKS_LIKE_INSTALLMENT(true),
    /** Extrato de cartão: crédito que não é compra (estorno, pagamento). Rejeitada por padrão: reembolso e pagamento se registram na conta ou à mão. */
    CARD_CREDIT_NOT_IMPORTED(false),
    /** Conta: há a perna oposta (mesmo valor, ±1 dia) em outra conta do usuário. Exige decisão; nunca é importada sozinha como despesa/receita. */
    POSSIBLE_TRANSFER(true);

    private final boolean requiresDecision;

    ImportLineFlag(boolean requiresDecision) {
        this.requiresDecision = requiresDecision;
    }

    public boolean requiresDecision() {
        return requiresDecision;
    }
}
