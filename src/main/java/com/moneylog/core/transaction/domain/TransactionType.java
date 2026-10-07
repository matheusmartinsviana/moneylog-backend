package com.moneylog.core.transaction.domain;

/**
 * Os 11 tipos de movimentação do PR 01 (D1 §2.4). O tipo decide os postings; o meio de pagamento não é tipo.
 */
public enum TransactionType {
    /** Gasto pago de conta ({@code EXPENSE(cat) +X / ACCOUNT −X}). */
    EXPENSE,
    /** Receita ({@code ACCOUNT +X / INCOME(cat) −X}). */
    INCOME,
    /** Transferência entre contas próprias da mesma liquidez. */
    TRANSFER,
    /** Aplicação/resgate entre conta CASH e conta INVESTMENT. */
    INVESTMENT,
    /** Dinheiro emprestado (cria a dívida RECEIVABLE). */
    LOAN_GIVEN,
    /** Dinheiro recebido: devolução de RECEIVABLE ou captação (cria PAYABLE). */
    MONEY_RECEIVED,
    /** Pagamento de dívida PAYABLE. */
    DEBT_PAYMENT,
    /** Compra no cartão (à vista = 1 parcela). */
    CARD_PURCHASE,
    /** Pagamento de fatura. */
    INVOICE_PAYMENT,
    /** Reembolso em conta ou no cartão. */
    REFUND,
    /** Ajuste manual (correção ou rendimento). */
    ADJUSTMENT
}
