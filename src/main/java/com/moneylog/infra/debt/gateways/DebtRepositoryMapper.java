package com.moneylog.infra.debt.gateways;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtSettlement;
import com.moneylog.core.debt.domain.DebtState;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.infra.debt.persistence.DebtEntity;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa.DebtLinkRow;

public final class DebtRepositoryMapper {

    private DebtRepositoryMapper() {
    }

    public static Debt toDomain(DebtEntity e, List<DebtLinkRow> links, List<LedgerEntry> entries) {
        CurrencyCode currency = CurrencyCode.of(e.getCurrency());
        List<DebtSettlement> settlements = links.stream()
                .filter(link -> !link.getId().equals(e.getOriginTransactionId()))
                .map(link -> new DebtSettlement(link.getId(), Money.ofCents(link.getAmountCents(), currency),
                        link.getStatus() == TransactionStatus.ACTIVE))
                .toList();
        DebtState state = new DebtState(e.getId(), e.getUserId(), e.getPersonId(), e.getDirection(), e.getOrigin(),
                Money.ofCents(e.getPrincipalCents(), currency), e.getDescription(), e.getDebtDate(), e.getExpectedDate(),
                e.getOriginTransactionId(), e.getClosure(), e.getClosureDate(), e.getClosureReason(), categoryId(e), e.getCreditorName());
        return Debt.restore(state, settlements, entries);
    }

    private static UUID categoryId(DebtEntity e) {
        return e.getCategoryId();
    }

    public static DebtEntity toEntity(Debt debt, java.time.Instant now) {
        DebtState s = debt.state();
        return new DebtEntity(s.id(), s.userId(), s.personId(), s.direction(), s.origin(), s.principal().cents(),
                s.principal().currency().value(), s.description(), s.date(), s.expectedDate(), s.originTransactionId(), s.closure(),
                s.closureDate(), s.closureReason(), s.categoryId(), s.creditorName(), now);
    }
}
