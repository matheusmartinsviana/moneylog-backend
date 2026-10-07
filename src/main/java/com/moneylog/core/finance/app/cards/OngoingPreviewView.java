package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.sk.money.Money;

/**
 * Prévia da compra parcelada em andamento (ADR-013): o que a criação gravaria, sem gravar, calculado pela mesma fábrica do domínio.
 * O front não soma parcela: {@code remaining} é o total que passa a ser devido e {@code lastDueDate} é o vencimento da última.
 *
 * @param remaining     Σ das parcelas que faltam (o que sobe no total de dívidas)
 * @param firstDueDate  vencimento da fatura da próxima parcela
 * @param lastDueDate   vencimento da fatura da última parcela
 */
public record OngoingPreviewView(int remainingInstallments, Money installmentAmount, Money remaining, LocalDate firstDueDate,
                                 LocalDate lastDueDate, PurchasePreviewView purchase) {

    public OngoingPreviewView {
        java.util.Objects.requireNonNull(purchase, "purchase");
    }

    public List<PurchasePreviewView.Installment> installments() {
        return purchase.installments();
    }
}
