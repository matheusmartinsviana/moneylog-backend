package com.moneylog.core.finance.app.cards;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.moneylog.core.card.domain.AvailableLimit;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.sk.money.Money;

/**
 * Cartão com o que a API calcula (S-129, S-79): limite disponível, fatura corrente e os dois "comprometidos".
 *
 * @param totalCommitted  Σ {@code outstanding} das faturas nem {@code PAID} nem {@code FUTURE} (a corrente e as
 *                        fechadas/atrasadas); é o que vai ao fluxo de caixa
 * @param committedFuture Σ das parcelas não canceladas em faturas {@code FUTURE} (compras já assumidas que ainda não
 *                        caíram em fatura). Disjunto de {@code totalCommitted}; os dois nunca se somam em outro lugar
 *                        (PR 01, regra 1)
 * @param creditBalance   crédito no cartão: {@code creditCarry} da última fatura da cadeia (D2 §4.3)
 * @param usedRatio       razão do limite usado, 0..1, 4 casas, só para a largura da barra
 * @param archivable      ativo, sem fatura com saldo, sem crédito e sem parcela futura (S-120)
 */
public record CardDetails(CreditCard card, Instant createdAt, Instant updatedAt, AvailableLimit limit, Money creditBalance,
                          InvoiceView currentInvoice, Money totalCommitted, Money committedFuture, BigDecimal usedRatio,
                          boolean archivable) {

    public CardDetails {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(creditBalance, "creditBalance");
        Objects.requireNonNull(currentInvoice, "currentInvoice");
        Objects.requireNonNull(totalCommitted, "totalCommitted");
        Objects.requireNonNull(committedFuture, "committedFuture");
        Objects.requireNonNull(usedRatio, "usedRatio");
    }
}
