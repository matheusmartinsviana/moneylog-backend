package com.moneylog.core.notification.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sem spam (S-175): tira o que já foi gerado hoje (idempotência), ordena por prioridade e respeita o teto de {@value #MAX_PER_DAY} por dia; o que passar
 * vira <b>uma</b> notificação agrupada "N lembretes".
 */
public final class NotificationPlanner {

    public static final int MAX_PER_DAY = 5;

    private static final List<NotificationKind> PRIORITY = List.of(NotificationKind.DAILY_SUMMARY, NotificationKind.INVOICE_DUE,
            NotificationKind.DEBT_PAYABLE_DUE, NotificationKind.DEBT_COLLECT, NotificationKind.RECURRENCE_PENDING, NotificationKind.REMINDER,
            NotificationKind.GOAL_DEADLINE, NotificationKind.COMMITTED_NEXT_MONTH, NotificationKind.GROUPED);

    private NotificationPlanner() {
    }

    /**
     * @param existingIdentities identidades {@code kind|refId|onDate} já gravadas
     * @param createdToday       quantas notificações já existem hoje para o usuário
     */
    public static List<NotificationDraft> plan(UUID userId, LocalDate today, List<NotificationDraft> drafts, Set<String> existingIdentities,
                                               int createdToday) {
        List<NotificationDraft> fresh = drafts.stream().filter(d -> !existingIdentities.contains(d.identity()))
                .distinct()
                .sorted(Comparator.comparingInt((NotificationDraft d) -> PRIORITY.indexOf(d.kind()))
                        .thenComparing(d -> d.dueOn() == null ? LocalDate.MAX : d.dueOn()).thenComparing(NotificationDraft::identity))
                .toList();
        int remaining = MAX_PER_DAY - createdToday;
        if (remaining <= 0 || fresh.isEmpty()) {
            return List.of();
        }
        if (fresh.size() <= remaining) {
            return fresh;
        }
        List<NotificationDraft> result = new ArrayList<>(fresh.subList(0, remaining - 1));
        int overflow = fresh.size() - (remaining - 1);
        NotificationDraft grouped = new NotificationDraft(NotificationKind.GROUPED, "USER", userId, today, "notification.grouped",
                Map.of("count", String.valueOf(overflow)), null);
        if (!existingIdentities.contains(grouped.identity())) {
            result.add(grouped);
        }
        return result;
    }
}
