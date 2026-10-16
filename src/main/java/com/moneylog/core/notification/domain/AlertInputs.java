package com.moneylog.core.notification.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record AlertInputs(Map<UUID, CardInfo> cards, List<MonthSurplus> negativeMonths, Money cashBalance, List<RealEstateDue> realEstateDues,
                          boolean financingPending, List<NewDebt> newDebts, List<CashOutCost> cashOutCosts, List<SubscriptionInfo> subscriptions,
                          List<OverdueReimbursement> overdueReimbursements, List<DocumentComparison> documents) {

    public static final AlertInputs EMPTY = new AlertInputs(Map.of(), List.of(), null, List.of(), false, List.of(), List.of(), List.of(), List.of(),
            List.of());

    public AlertInputs {
        cards = Map.copyOf(cards);
        negativeMonths = List.copyOf(negativeMonths);
        realEstateDues = List.copyOf(realEstateDues);
        newDebts = List.copyOf(newDebts);
        cashOutCosts = List.copyOf(cashOutCosts);
        subscriptions = List.copyOf(subscriptions);
        overdueReimbursements = List.copyOf(overdueReimbursements);
        documents = List.copyOf(documents);
    }

    public record CardInfo(String name, Boolean autoDebit) {
    }

    public record MonthSurplus(Competence competence, Money surplus) {
    }

    public record RealEstateDue(UUID commitmentId, LocalDate dueDate, Money amount) {
    }

    public record NewDebt(UUID transactionId, Money amount, int installments) {
    }

    public record CashOutCost(UUID debtId, long monthlyRateBps, Money installmentAmount, int installments) {
    }

    public record SubscriptionInfo(UUID id, String name, String overlapGroup, LocalDate lastUsedOn, boolean weekly, boolean createdToday) {
    }

    public record OverdueReimbursement(UUID transactionId, String personName, Money amount, LocalDate invoiceDueDate) {
    }

    public record DocumentComparison(String refType, UUID refId, String label, Money informed, Money documented) {
    }
}
