package com.moneylog.core.sk.ledger;

import java.time.LocalDate;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/**
 * Lançamento no razão (D1 §1.1): livro, valor com sinal (débito {@code +}, crédito {@code −}) e data efetiva.
 * A competência é sempre o mês da data efetiva. Valor zero não é lançamento.
 */
public record Posting(BookRef book, Money amount, LocalDate effectiveDate) {

    public Posting {
        if (book == null) {
            throw new InvalidPostingException("o livro é obrigatório.");
        }
        if (amount == null || amount.isZero()) {
            throw new InvalidPostingException("o valor precisa ser diferente de zero.");
        }
        if (effectiveDate == null) {
            throw new InvalidPostingException("a data efetiva é obrigatória.");
        }
    }

    public Competence competence() {
        return Competence.from(effectiveDate);
    }

    public BookType bookType() {
        return book.type();
    }

    @Override
    public String toString() {
        return book + " " + (amount.isPositive() ? "+" : "") + amount.toPlainString() + " @ " + effectiveDate;
    }
}
