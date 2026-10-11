package com.moneylog.core.recurrence.app;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.persistence.Versioned;

public final class RecurrenceViews {

    private final CategoryGateway categories;
    private final AccountGateway accounts;
    private final CardGateway cards;

    public RecurrenceViews(CategoryGateway categories, AccountGateway accounts, CardGateway cards) {
        this.categories = categories;
        this.accounts = accounts;
        this.cards = cards;
    }

    public List<RecurrenceView> of(UUID userId, List<Versioned<RecurringTransaction>> items, LocalDate today) {
        Map<UUID, Category> categoriesById = new HashMap<>();
        categories.list(userId, null, true).forEach(c -> categoriesById.put(c.id(), c));
        Map<UUID, Account> accountsById = new HashMap<>();
        Map<UUID, CreditCard> cardsById = new HashMap<>();
        return items.stream().map(item -> {
            RecurringTransaction r = item.value();
            Category category = categoriesById.get(r.categoryId());
            if (category == null) {
                throw new CategoryNotFoundException(r.categoryId());
            }
            Account account = r.accountId() == null ? null : accountsById.computeIfAbsent(r.accountId(),
                    id -> accounts.findById(userId, id).map(Persisted::value).orElseThrow(() -> new AccountNotFoundException(id)));
            CreditCard card = r.cardId() == null ? null : cardsById.computeIfAbsent(r.cardId(),
                    id -> cards.findById(userId, id).map(Persisted::value).orElseThrow(() -> new CardNotFoundException(id)));
            return new RecurrenceView(item, category, account, card, RecurrenceView.nextOccurrenceOf(r, today),
                    RecurrenceView.monthlyEstimateOf(r, today), r.isEnded(today));
        }).toList();
    }

    public RecurrenceView one(UUID userId, Versioned<RecurringTransaction> item, LocalDate today) {
        return of(userId, List.of(item), today).get(0);
    }
}
