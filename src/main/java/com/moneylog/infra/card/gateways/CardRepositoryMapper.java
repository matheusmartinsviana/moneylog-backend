package com.moneylog.infra.card.gateways;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

import com.moneylog.core.card.domain.BillingRule;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.DayOfMonthRule;
import com.moneylog.infra.card.persistence.BillingRuleEntity;
import com.moneylog.infra.card.persistence.CardEntity;

/** {@code CreditCard} ⇄ {@code CardEntity} + {@code BillingRuleEntity}. */
public final class CardRepositoryMapper {

    private CardRepositoryMapper() {
    }

    public static CreditCard toDomain(CardEntity e, List<BillingRuleEntity> rules) {
        List<BillingRule> domainRules = rules.stream()
                .map(r -> new BillingRule(YearMonth.parse(r.getEffectiveFrom()), DayOfMonthRule.of(r.getClosingDay()),
                        DayOfMonthRule.of(r.getDueDay())))
                .sorted(Comparator.comparing(BillingRule::effectiveFrom)).toList();
        return CreditCard.restore(e.getId(), e.getUserId(), e.getName(), e.getInstitution(),
                e.getLimitCents() == null ? null : Money.ofCents(e.getLimitCents(), CurrencyCode.of(e.getCurrency())), e.getDefaultPaymentAccountId(), domainRules,
                e.getStatus(), e.getVersion(), e.isClosingConfirmed());
    }

    public static Persisted<CreditCard> toPersisted(CardEntity e, List<BillingRuleEntity> rules) {
        return new Persisted<>(toDomain(e, rules), e.getCreatedAt(), e.getUpdatedAt());
    }
}
