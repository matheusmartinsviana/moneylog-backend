package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public final class Ledger {

    private final List<LedgerEntry> active;

    public Ledger(Collection<LedgerEntry> entries) {
        this.active = entries.stream().filter(LedgerEntry::active).toList();
    }

    public List<LedgerEntry> entries() {
        return active;
    }

    public Money balance(BookRef book, LocalDate upTo) {
        return sum(e -> e.posting().book().equals(book) && !e.posting().effectiveDate().isAfter(upTo));
    }

    public Money balanceAllDates(BookRef book) {
        return sum(e -> e.posting().book().equals(book));
    }

    public Money balance(BookType type, LocalDate upTo) {
        return sum(e -> e.posting().bookType() == type && !e.posting().effectiveDate().isAfter(upTo));
    }

    public List<LedgerEntry> inCompetence(BookType type, Competence competence) {
        return active.stream()
                .filter(e -> e.posting().bookType() == type && e.posting().competence().equals(competence))
                .toList();
    }

    public Money sum(Predicate<LedgerEntry> filter) {
        return Money.sum(active.stream().filter(filter).map(LedgerEntry::posting).map(Posting::amount).toList(), CurrencyCode.BRL);
    }
}
