package com.moneylog.core.notification.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.finance.domain.Bucket;
import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.finance.domain.CommitmentKind;
import com.moneylog.core.finance.domain.Horizon;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;

public final class NotificationGenerators {

    public static final int GOAL_DEADLINE_DAYS = 30;
    public static final int COMMITTED_NEXT_MONTH_DAY = 25;
    public static final int OVERDUE_REPEAT_DAYS = 7;

    private NotificationGenerators() {
    }

    public static List<NotificationDraft> generate(GenerationContext ctx) {
        List<NotificationDraft> drafts = new ArrayList<>();
        NotificationSettings settings = ctx.settings();
        if (settings.isEnabled(NotificationKind.DAILY_SUMMARY)) {
            dailySummary(ctx).ifPresent(drafts::add);
        }
        if (settings.isEnabled(NotificationKind.INVOICE_DUE)) {
            drafts.addAll(invoices(ctx));
        }
        if (settings.isEnabled(NotificationKind.DEBT_PAYABLE_DUE)) {
            drafts.addAll(debtsPayable(ctx));
        }
        if (settings.isEnabled(NotificationKind.DEBT_COLLECT)) {
            drafts.addAll(debtsToCollect(ctx));
        }
        if (settings.isEnabled(NotificationKind.RECURRENCE_PENDING)) {
            drafts.addAll(recurrences(ctx));
        }
        drafts.addAll(reminders(ctx));
        if (settings.isEnabled(NotificationKind.GOAL_DEADLINE)) {
            drafts.addAll(goals(ctx));
        }
        if (settings.isEnabled(NotificationKind.COMMITTED_NEXT_MONTH)) {
            committedNextMonth(ctx).ifPresent(drafts::add);
        }
        return drafts;
    }

    static List<NotificationDraft> invoices(GenerationContext ctx) {
        List<NotificationDraft> result = new ArrayList<>();
        for (Commitment c : ctx.schedule().items()) {
            if (c.kind() != CommitmentKind.INVOICE || c.dueDate() == null) {
                continue;
            }
            countdown(ctx, c, new int[] {3, 1, 0}, NotificationKind.INVOICE_DUE, "CARD", "notification.invoice").ifPresent(result::add);
        }
        return result;
    }

    static List<NotificationDraft> debtsPayable(GenerationContext ctx) {
        List<NotificationDraft> result = new ArrayList<>();
        for (Commitment c : ctx.schedule().items()) {
            if (c.kind() == CommitmentKind.DEBT_PAYABLE && c.dueDate() != null) {
                countdown(ctx, c, new int[] {3, 1, 0}, NotificationKind.DEBT_PAYABLE_DUE, "DEBT", "notification.debt.payable").ifPresent(result::add);
            }
        }
        return result;
    }

    static List<NotificationDraft> debtsToCollect(GenerationContext ctx) {
        List<NotificationDraft> result = new ArrayList<>();
        for (Commitment c : ctx.schedule().items()) {
            if (c.kind() == CommitmentKind.DEBT_RECEIVABLE && c.dueDate() != null) {
                countdown(ctx, c, new int[] {0}, NotificationKind.DEBT_COLLECT, "DEBT", "notification.debt.collect").ifPresent(result::add);
            }
        }
        return result;
    }

    private static java.util.Optional<NotificationDraft> countdown(GenerationContext ctx, Commitment c, int[] warnDays, NotificationKind kind,
                                                                  String refType, String keyPrefix) {
        long days = ChronoUnit.DAYS.between(ctx.today(), c.dueDate());
        String suffix;
        if (days < 0) {
            long late = -days;
            if (late != 1 && late % OVERDUE_REPEAT_DAYS != 0) {
                return java.util.Optional.empty();
            }
            suffix = "overdue";
        } else if (contains(warnDays, (int) days)) {
            suffix = "due" + days;
        } else {
            return java.util.Optional.empty();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("name", nz(c.label()));
        params.put("amount", c.amount().toPlainString());
        params.put("dueDate", c.dueDate().toString());
        params.put("daysLate", String.valueOf(Math.max(0, -days)));
        return java.util.Optional.of(new NotificationDraft(kind, refType, c.refId(), ctx.today(), keyPrefix + "." + suffix, params, c.dueDate()));
    }

    private static String nz(String text) {
        return text == null ? "" : text;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    static List<NotificationDraft> recurrences(GenerationContext ctx) {
        Map<UUID, Commitment> todays = new LinkedHashMap<>();
        Map<UUID, Commitment> oldest = new LinkedHashMap<>();
        for (Commitment c : ctx.schedule().items()) {
            if (c.kind() != CommitmentKind.RECURRENCE || c.dueDate() == null || c.dueDate().isAfter(ctx.today())) {
                continue;
            }
            if (c.dueDate().equals(ctx.today())) {
                todays.putIfAbsent(c.refId(), c);
            } else {
                oldest.merge(c.refId(), c, (a, b) -> a.dueDate().isBefore(b.dueDate()) ? a : b);
            }
        }
        List<NotificationDraft> result = new ArrayList<>();
        todays.values().forEach(c -> result.add(recurrenceDraft(ctx, c, "notification.recurrence.today")));
        for (Commitment c : oldest.values()) {
            long late = ChronoUnit.DAYS.between(c.dueDate(), ctx.today());
            if (!todays.containsKey(c.refId()) && (late == 1 || late % OVERDUE_REPEAT_DAYS == 0)) {
                result.add(recurrenceDraft(ctx, c, "notification.recurrence.overdue"));
            }
        }
        return result;
    }

    private static NotificationDraft recurrenceDraft(GenerationContext ctx, Commitment c, String key) {
        long late = ChronoUnit.DAYS.between(c.dueDate(), ctx.today());
        return new NotificationDraft(NotificationKind.RECURRENCE_PENDING, "RECURRENCE", c.refId(), ctx.today(), key,
                Map.of("name", nz(c.label()), "amount", c.amount().toPlainString(), "dueDate", c.dueDate().toString(), "daysLate", String.valueOf(late)),
                c.dueDate());
    }

    static java.util.Optional<NotificationDraft> committedNextMonth(GenerationContext ctx) {
        if (ctx.today().getDayOfMonth() != COMMITTED_NEXT_MONTH_DAY) {
            return java.util.Optional.empty();
        }
        YearMonth next = YearMonth.from(ctx.today()).plusMonths(1);
        Money total = Money.sum(ctx.schedule().between(next.atDay(1), next.atEndOfMonth()).stream()
                .filter(c -> c.direction() == FlowDirection.OUT).map(Commitment::amount).toList(), CurrencyCode.BRL);
        if (!total.isPositive()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new NotificationDraft(NotificationKind.COMMITTED_NEXT_MONTH, "USER", ctx.userId(), ctx.today(),
                "notification.committed.nextMonth", Map.of("month", next.toString(), "total", total.toPlainString()), null));
    }

    static List<NotificationDraft> goals(GenerationContext ctx) {
        List<NotificationDraft> result = new ArrayList<>();
        for (Goal goal : ctx.goals()) {
            if (goal.isMetric() || goal.deadline() == null || goal.status(ctx.today()) != GoalStatus.ACTIVE) {
                continue;
            }
            if (ChronoUnit.DAYS.between(ctx.today(), goal.deadline()) == GOAL_DEADLINE_DAYS) {
                result.add(new NotificationDraft(NotificationKind.GOAL_DEADLINE, "GOAL", goal.id(), ctx.today(), "notification.goal.deadline30",
                        Map.of("name", goal.name(), "deadline", goal.deadline().toString(), "progress", goal.progress(ctx.today()).ratioText()),
                        goal.deadline()));
            }
        }
        return result;
    }

    static List<NotificationDraft> reminders(GenerationContext ctx) {
        List<NotificationDraft> result = new ArrayList<>();
        for (Reminder r : ctx.openReminders()) {
            boolean due = r.remindOn().equals(ctx.today()) && (r.remindAt() == null || !ctx.localTime().isBefore(r.remindAt()));
            if (due && !r.isDone()) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("title", r.title());
                if (r.linkType() != null) {
                    params.put("linkType", r.linkType());
                    params.put("linkId", r.linkId().toString());
                }
                result.add(new NotificationDraft(NotificationKind.REMINDER, "REMINDER", r.id(), ctx.today(), "notification.reminder", params,
                        r.remindOn()));
            }
        }
        return result;
    }

    static java.util.Optional<NotificationDraft> dailySummary(GenerationContext ctx) {
        NotificationSettings settings = ctx.settings();
        if (ctx.localTime().isBefore(settings.summaryTime())) {
            return java.util.Optional.empty();
        }
        if (settings.summaryFrequency() == SummaryFrequency.ONLY_DUE && !hasDueWithin7Days(ctx)) {
            return java.util.Optional.empty();
        }
        DailyMessage message = DailyMessage.of(ctx.settings().displayName(), ctx.available(), ctx.committed7d(), ctx.goals(), ctx.today());
        return java.util.Optional.of(new NotificationDraft(NotificationKind.DAILY_SUMMARY, "USER", ctx.userId(), ctx.today(), message.key(),
                message.params(), null));
    }

    private static boolean hasDueWithin7Days(GenerationContext ctx) {
        LocalDate limit = Horizon.D7.endFrom(ctx.today());
        return ctx.schedule().items().stream().anyMatch(c -> c.direction() == FlowDirection.OUT && c.dueDate() != null
                && (c.bucket() == Bucket.OVERDUE || !c.dueDate().isAfter(limit)));
    }
}
