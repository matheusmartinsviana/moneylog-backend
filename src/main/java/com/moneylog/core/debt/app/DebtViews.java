package com.moneylog.core.debt.app;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtKind;
import com.moneylog.core.debt.domain.EffectiveRate;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;

public final class DebtViews {

    private DebtViews() {
    }

    public static List<DebtView> of(PersonGateway people, DebtGateway debts, CategoryGateway categories, UUID userId,
                                    List<Versioned<Debt>> items, LocalDate today) {
        if (items.isEmpty()) {
            return List.of();
        }
        Map<UUID, Person> persons = people.findByIds(userId, items.stream().map(d -> d.value().personId()).filter(java.util.Objects::nonNull).distinct().toList());
        List<UUID> settlementIds = items.stream().flatMap(d -> d.value().settlements().stream()).map(s -> s.transactionId()).toList();
        Map<UUID, LocalDate> dates = debts.settlementDates(userId, settlementIds);
        Map<UUID, String> categoryNames = new HashMap<>();
        for (Versioned<Debt> item : items) {
            UUID categoryId = item.value().categoryId();
            if (categoryId != null && !categoryNames.containsKey(categoryId)) {
                categoryNames.put(categoryId, categories.findById(userId, categoryId).map(Category::name).orElse(null));
            }
        }
        Map<UUID, List<Money>> schedules = debts.installmentSchedules(userId, items.stream().map(Versioned::value)
                .filter(d -> d.kind() == DebtKind.CARD_CASH_OUT && d.originTransactionId() != null)
                .map(Debt::originTransactionId).toList());
        return items.stream().map(item -> {
            Debt debt = item.value();
            Person person = debt.personId() == null ? null : persons.get(debt.personId());
            if (person == null && debt.personId() != null) {
                throw new PersonNotFoundException(debt.personId());
            }
            EffectiveRate rate = debt.originTransactionId() == null ? null : EffectiveRate
                    .irr(debt.principal(), schedules.getOrDefault(debt.originTransactionId(), List.of())).orElse(null);
            return DebtView.of(item, person, categoryNames.get(debt.categoryId()), dates, today, rate);
        }).toList();
    }

    public static DebtView one(PersonGateway people, DebtGateway debts, CategoryGateway categories, UUID userId, Versioned<Debt> item,
                               LocalDate today) {
        return of(people, debts, categories, userId, List.of(item), today).get(0);
    }
}
