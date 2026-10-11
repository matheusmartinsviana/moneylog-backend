package com.moneylog.core.sk.ledger;

import java.time.LocalDate;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.FinancialDates;

public record Posting(BookRef book, Money amount, LocalDate effectiveDate, Competence competence) {

    public Posting(BookRef book, Money amount, LocalDate effectiveDate) {
        this(book, amount, effectiveDate, null);
    }

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
        Competence ofDate = Competence.from(effectiveDate);
        if (competence == null) {
            competence = ofDate;
        } else if (!competence.equals(ofDate)) {
            if (book.type() != BookType.INCOME) {
                throw new InvalidPostingException("só a receita (livro INCOME) pode ter competência diferente do mês da data.");
            }
            if (!FinancialDates.isInWindow(competence.firstDay()) || !FinancialDates.isInWindow(competence.lastDay())) {
                throw new InvalidPostingException("a competência precisa estar entre 2000-01 e 2100-12.");
            }
        }
    }

    public Posting inCompetence(Competence target) {
        return new Posting(book, amount, effectiveDate, target);
    }

    public BookType bookType() {
        return book.type();
    }

    @Override
    public String toString() {
        return book + " " + (amount.isPositive() ? "+" : "") + amount.toPlainString() + " @ " + effectiveDate;
    }
}
