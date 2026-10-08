package com.moneylog.core.card.domain;

import java.util.Collection;
import java.util.Objects;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.Money;

/**
 * Limite disponível (D1 §2.5, S-46) = limite + saldo do livro {@code CARD} do cartão considerando <b>todos</b> os
 * postings ativos, inclusive os de data futura (parcelas futuras). Consequências: a compra parcelada consome o valor
 * total; o pagamento (inclusive parcial) devolve exatamente o valor pago; o cancelamento de parcelas devolve as
 * canceladas; o saldo credor aumenta o disponível, que pode passar do limite; disponível negativo é permitido (S-11).
 */
public final class AvailableLimitCalculator {

    private AvailableLimitCalculator() {
    }

    /**
     * @param cardPostings postings <b>ativos</b> do livro {@code CARD} deste cartão (todas as datas)
     */
    public static AvailableLimit calculate(CreditCard card, Collection<Posting> cardPostings) {
        Objects.requireNonNull(card, "card");
        Money balance = Money.zero(card.currency());
        for (Posting posting : cardPostings) {
            if (posting.bookType() == BookType.CARD && card.id().equals(posting.book().id())) {
                balance = balance.add(posting.amount());
            }
        }
        Money credit = balance.isPositive() ? balance : Money.zero(card.currency());
        if (card.limit() == null) {
            // sem limite definido: não há disponível nem estouro a calcular (ADR-014)
            return new AvailableLimit(null, false, credit);
        }
        Money available = card.limit().add(balance);
        return new AvailableLimit(available, available.isNegative(), credit);
    }
}
