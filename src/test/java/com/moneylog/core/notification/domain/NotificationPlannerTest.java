package com.moneylog.core.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NotificationPlanner: sem spam (S-175)")
class NotificationPlannerTest {

    private static final UUID USER = new UUID(0, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private static NotificationDraft draft(NotificationKind kind, int ref) {
        return new NotificationDraft(kind, "DEBT", new UUID(1, ref), TODAY, "notification.test", Map.of(), TODAY);
    }

    private static List<NotificationDraft> drafts(int count) {
        List<NotificationDraft> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(draft(NotificationKind.DEBT_COLLECT, i));
        }
        return list;
    }

    @Test
    @DisplayName("até 5 por dia passam inteiras")
    void ate5() {
        assertThat(NotificationPlanner.plan(USER, TODAY, drafts(5), Set.of(), 0)).hasSize(5);
    }

    @Test
    @DisplayName("mais de 5: 4 + uma notificação agrupada 'N lembretes' com as excedentes")
    void agrupa() {
        List<NotificationDraft> planned = NotificationPlanner.plan(USER, TODAY, drafts(8), Set.of(), 0);
        assertThat(planned).hasSize(5);
        NotificationDraft grouped = planned.get(4);
        assertThat(grouped.kind()).isEqualTo(NotificationKind.GROUPED);
        assertThat(grouped.params()).containsEntry("count", "4");
        assertThat(grouped.refId()).isEqualTo(USER);
        assertThat(planned.stream().filter(d -> d.kind() != NotificationKind.GROUPED)).hasSize(4);
    }

    @Test
    @DisplayName("idempotência: o que já foi gerado hoje sai e conta no teto; reexecutar não cria nada novo")
    void idempotente() {
        List<NotificationDraft> all = drafts(3);
        Set<String> existing = all.stream().map(NotificationDraft::identity).collect(Collectors.toSet());
        assertThat(NotificationPlanner.plan(USER, TODAY, all, existing, 3)).isEmpty();
        // duas novas com 3 já criadas: cabem exatamente (3 + 2 = 5)
        List<NotificationDraft> more = new ArrayList<>(all);
        more.add(draft(NotificationKind.INVOICE_DUE, 10));
        more.add(draft(NotificationKind.INVOICE_DUE, 11));
        assertThat(NotificationPlanner.plan(USER, TODAY, more, existing, 3)).hasSize(2);
        // teto atingido: nada
        assertThat(NotificationPlanner.plan(USER, TODAY, more, existing, 5)).isEmpty();
    }

    @Test
    @DisplayName("ordem de prioridade: resumo, fatura, dívidas, recorrência, lembrete, meta, mês seguinte")
    void prioridade() {
        List<NotificationDraft> shuffled = List.of(draft(NotificationKind.GOAL_DEADLINE, 1), draft(NotificationKind.INVOICE_DUE, 2),
                draft(NotificationKind.DAILY_SUMMARY, 3), draft(NotificationKind.REMINDER, 4));
        assertThat(NotificationPlanner.plan(USER, TODAY, shuffled, Set.of(), 0)).extracting(NotificationDraft::kind).containsExactly(
                NotificationKind.DAILY_SUMMARY, NotificationKind.INVOICE_DUE, NotificationKind.REMINDER, NotificationKind.GOAL_DEADLINE);
    }

    @Test
    @DisplayName("rascunhos duplicados na mesma geração viram um")
    void duplicados() {
        NotificationDraft d = draft(NotificationKind.INVOICE_DUE, 1);
        assertThat(NotificationPlanner.plan(USER, TODAY, List.of(d, d), Set.of(), 0)).hasSize(1);
    }
}
