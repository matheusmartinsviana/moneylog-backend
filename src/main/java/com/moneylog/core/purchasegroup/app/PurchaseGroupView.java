package com.moneylog.core.purchasegroup.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record PurchaseGroupView(UUID id, String name, boolean interest, Status status, Money total, Money remaining, Money myRemaining,
                                Money installmentAmountPerMonth, LocalDate lastDueDate, Divergence divergence, List<MemberView> members,
                                long version, Instant createdAt, Instant updatedAt) {

    public enum Status {
        ACTIVE,
        DONE
    }

    public record Divergence(Money declared, Money computed, Money difference) {
    }

    public record MemberView(UUID transactionId, UUID cardId, String cardName, String description, int totalInstallments,
                             int remainingInstallments, Money installmentAmount, Money total, Money remaining, Money myRemaining,
                             LocalDate lastDueDate) {
    }

    public PurchaseGroupView {
        members = List.copyOf(members);
    }
}
