package com.moneylog.core.notification.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.finance.domain.CommitmentKind;
import com.moneylog.core.notification.domain.AlertInputs.CardInfo;
import com.moneylog.core.notification.domain.AlertInputs.RealEstateDue;
import com.moneylog.core.notification.domain.AlertInputs.SubscriptionInfo;

final class AlertGenerators {

    static final int REAL_ESTATE_WINDOW_DAYS = 30;

    private AlertGenerators() {
    }

    static List<NotificationDraft> generate(GenerationContext ctx) {
        NotificationSettings settings = ctx.settings();
        List<NotificationDraft> drafts = new ArrayList<>();
        if (settings.isEnabled(NotificationKind.INVOICE_DUE_AUTODEBIT_OFF)) {
            drafts.addAll(invoicesWithoutAutoDebit(ctx));
        }
        if (settings.isEnabled(NotificationKind.NEGATIVE_MONTH_PROJECTED)) {
            negativeMonth(ctx).ifPresent(drafts::add);
        }
        if (settings.isEnabled(NotificationKind.NEW_DEBT_BEFORE_FINANCING)) {
            drafts.addAll(newDebtBeforeFinancing(ctx));
        }
        if (settings.isEnabled(NotificationKind.CARD_CASH_OUT_COST)) {
            drafts.addAll(cardCashOutCost(ctx));
        }
        if (settings.isEnabled(NotificationKind.SUBSCRIPTION_OVERLAP)) {
            drafts.addAll(subscriptionOverlap(ctx));
        }
        if (settings.isEnabled(NotificationKind.SUBSCRIPTION_UNUSED)) {
            drafts.addAll(subscriptionUnused(ctx));
        }
        if (settings.isEnabled(NotificationKind.WEEKLY_CHARGE)) {
            drafts.addAll(weeklyCharge(ctx));
        }
        if (settings.isEnabled(NotificationKind.THIRD_PARTY_OVERDUE)) {
            drafts.addAll(thirdPartyOverdue(ctx));
        }
        if (settings.isEnabled(NotificationKind.BALANCE_BELOW_REAL_ESTATE)) {
            balanceBelowRealEstate(ctx).ifPresent(drafts::add);
        }
        if (settings.isEnabled(NotificationKind.DOCUMENT_DIVERGENCE)) {
            drafts.addAll(documentDivergence(ctx));
        }
        return drafts;
    }

    static List<NotificationDraft> invoicesWithoutAutoDebit(GenerationContext ctx) {
        List<NotificationDraft> result = new ArrayList<>();
        int lead = ctx.settings().alerts().invoiceDueLeadDays();
        for (Commitment c : ctx.schedule().items()) {
            if (c.kind() != CommitmentKind.INVOICE || c.dueDate() == null) {
                continue;
            }
            CardInfo card = ctx.alerts().cards().get(c.refId());
            long days = ChronoUnit.DAYS.between(ctx.today(), c.dueDate());
            if (card == null || !Boolean.FALSE.equals(card.autoDebit()) || days < 0 || days > lead) {
                continue;
            }
            result.add(new NotificationDraft(NotificationKind.INVOICE_DUE_AUTODEBIT_OFF, "CARD", c.refId(), ctx.today(),
                    "notification.invoice.autodebitOff",
                    Map.of("cardName", card.name(), "dueDate", c.dueDate().toString(), "amount", c.amount().toPlainString()), c.dueDate()));
        }
        return result;
    }

    static Optional<NotificationDraft> negativeMonth(GenerationContext ctx) {
        return ctx.alerts().negativeMonths().stream().min(Comparator.comparing(m -> m.competence().toString()))
                .map(m -> new NotificationDraft(NotificationKind.NEGATIVE_MONTH_PROJECTED, "USER", ctx.userId(), ctx.today(),
                        "notification.month.negative", Map.of("competence", m.competence().toString(), "surplus", m.surplus().toPlainString()),
                        null));
    }

    static List<NotificationDraft> newDebtBeforeFinancing(GenerationContext ctx) {
        if (!ctx.alerts().financingPending()) {
            return List.of();
        }
        return ctx.alerts().newDebts().stream()
                .map(d -> new NotificationDraft(NotificationKind.NEW_DEBT_BEFORE_FINANCING, "TRANSACTION", d.transactionId(), ctx.today(),
                        "notification.debt.beforeFinancing",
                        Map.of("amount", d.amount().toPlainString(), "installments", String.valueOf(d.installments())), null))
                .toList();
    }

    static List<NotificationDraft> cardCashOutCost(GenerationContext ctx) {
        return ctx.alerts().cashOutCosts().stream()
                .map(c -> new NotificationDraft(NotificationKind.CARD_CASH_OUT_COST, "DEBT", c.debtId(), ctx.today(), "notification.cardCashOut.cost",
                        Map.of("monthlyRateBps", String.valueOf(c.monthlyRateBps()), "installmentAmount", c.installmentAmount().toPlainString(),
                                "installments", String.valueOf(c.installments())), null))
                .toList();
    }

    static List<NotificationDraft> subscriptionOverlap(GenerationContext ctx) {
        Map<String, List<SubscriptionInfo>> groups = new LinkedHashMap<>();
        for (SubscriptionInfo s : ctx.alerts().subscriptions()) {
            if (s.overlapGroup() != null) {
                groups.computeIfAbsent(s.overlapGroup().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(s);
            }
        }
        List<NotificationDraft> result = new ArrayList<>();
        for (List<SubscriptionInfo> members : groups.values()) {
            if (members.size() < 2) {
                continue;
            }
            SubscriptionInfo anchor = members.stream().min(Comparator.comparing(SubscriptionInfo::id)).orElseThrow();
            result.add(new NotificationDraft(NotificationKind.SUBSCRIPTION_OVERLAP, "RECURRENCE", anchor.id(), ctx.today(),
                    "notification.subscription.overlap", Map.of("group", anchor.overlapGroup()), null));
        }
        return result;
    }

    static List<NotificationDraft> subscriptionUnused(GenerationContext ctx) {
        Integer threshold = ctx.settings().alerts().subscriptionUnusedDays();
        if (threshold == null) {
            return List.of();
        }
        List<NotificationDraft> result = new ArrayList<>();
        for (SubscriptionInfo s : ctx.alerts().subscriptions()) {
            if (s.lastUsedOn() == null) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(s.lastUsedOn(), ctx.today());
            if (days > threshold) {
                result.add(new NotificationDraft(NotificationKind.SUBSCRIPTION_UNUSED, "RECURRENCE", s.id(), ctx.today(),
                        "notification.subscription.unused", Map.of("name", nz(s.name()), "days", String.valueOf(days)), null));
            }
        }
        return result;
    }

    static List<NotificationDraft> weeklyCharge(GenerationContext ctx) {
        return ctx.alerts().subscriptions().stream().filter(s -> s.weekly() && s.createdToday())
                .map(s -> new NotificationDraft(NotificationKind.WEEKLY_CHARGE, "RECURRENCE", s.id(), ctx.today(), "notification.subscription.weekly",
                        Map.of("name", nz(s.name())), null))
                .toList();
    }

    static List<NotificationDraft> thirdPartyOverdue(GenerationContext ctx) {
        return ctx.alerts().overdueReimbursements().stream().filter(o -> ctx.today().isAfter(o.invoiceDueDate()))
                .map(o -> new NotificationDraft(NotificationKind.THIRD_PARTY_OVERDUE, "TRANSACTION", o.transactionId(), ctx.today(),
                        "notification.thirdParty.overdue", Map.of("personName", nz(o.personName()), "amount", o.amount().toPlainString()),
                        o.invoiceDueDate()))
                .toList();
    }

    static Optional<NotificationDraft> balanceBelowRealEstate(GenerationContext ctx) {
        if (ctx.alerts().cashBalance() == null) {
            return Optional.empty();
        }
        LocalDate limit = ctx.today().plusDays(REAL_ESTATE_WINDOW_DAYS);
        return ctx.alerts().realEstateDues().stream().filter(d -> !d.dueDate().isBefore(ctx.today()))
                .min(Comparator.comparing(RealEstateDue::dueDate).thenComparing(RealEstateDue::commitmentId))
                .filter(d -> !d.dueDate().isAfter(limit) && ctx.alerts().cashBalance().isLessThan(d.amount()))
                .map(d -> new NotificationDraft(NotificationKind.BALANCE_BELOW_REAL_ESTATE, "COMMITMENT", d.commitmentId(), ctx.today(),
                        "notification.balance.belowRealEstate",
                        Map.of("balance", ctx.alerts().cashBalance().toPlainString(), "due", d.amount().toPlainString()), d.dueDate()));
    }

    static List<NotificationDraft> documentDivergence(GenerationContext ctx) {
        List<NotificationDraft> result = new ArrayList<>();
        for (AlertInputs.DocumentComparison d : ctx.alerts().documents()) {
            var difference = d.informed().subtract(d.documented()).abs();
            if (difference.isGreaterThan(ctx.settings().alerts().documentDivergenceTolerance())) {
                UUID ref = d.refId();
                result.add(new NotificationDraft(NotificationKind.DOCUMENT_DIVERGENCE, d.refType(), ref, ctx.today(),
                        "notification.document.divergence", Map.of("label", nz(d.label()), "difference", difference.toPlainString(),
                                "informed", d.informed().toPlainString(), "documented", d.documented().toPlainString()), null));
            }
        }
        return result;
    }

    private static String nz(String text) {
        return text == null ? "" : text;
    }
}
