package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;

public final class OwnerSources {

    private final FinancialRecords records;
    private final List<RecurringTransaction> recurrences;
    private final List<RealEstateItem> realEstate;
    private final Map<UUID, RecurringTransaction> recurrencesById = new HashMap<>();
    private final Map<UUID, Transaction> transactionsById = new HashMap<>();
    private final Map<UUID, CreditCard> cardsById = new HashMap<>();
    private final Map<UUID, InvoiceChain> chains = new HashMap<>();
    private final Map<String, Transaction> realizedByOccurrence = new HashMap<>();

    public OwnerSources(FinancialRecords records, Collection<RecurringTransaction> recurrences, Collection<RealEstateItem> realEstate) {
        this.records = records;
        this.recurrences = List.copyOf(recurrences);
        this.realEstate = List.copyOf(realEstate);
        this.recurrences.forEach(r -> recurrencesById.put(r.id(), r));
        records.cards().forEach(c -> cardsById.put(c.id(), c));
        for (Transaction tx : records.transactions()) {
            transactionsById.put(tx.id(), tx);
            TransactionOrigin origin = tx.origin();
            if (tx.isActive() && origin.recurrenceId() != null) {
                realizedByOccurrence.put(occurrenceKey(origin.recurrenceId(), origin.occurrenceDate()), tx);
            }
        }
    }

    public static String occurrenceKey(UUID recurrenceId, LocalDate occurrenceDate) {
        return recurrenceId + "@" + occurrenceDate;
    }

    public FinancialRecords records() {
        return records;
    }

    public List<RecurringTransaction> recurrences() {
        return recurrences;
    }

    public List<RealEstateItem> realEstate() {
        return realEstate;
    }

    public RecurringTransaction recurrence(UUID id) {
        return id == null ? null : recurrencesById.get(id);
    }

    public Transaction transaction(UUID id) {
        return transactionsById.get(id);
    }

    public CreditCard card(UUID id) {
        return id == null ? null : cardsById.get(id);
    }

    public InvoiceChain chain(CreditCard card) {
        return chains.computeIfAbsent(card.id(), id -> CardInvoices.chainFor(card, records.transactions()));
    }

    public Transaction realized(UUID recurrenceId, LocalDate occurrenceDate) {
        return realizedByOccurrence.get(occurrenceKey(recurrenceId, occurrenceDate));
    }
}
