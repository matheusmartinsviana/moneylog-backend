package com.moneylog.core.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.util.Ids;

/**
 * Geradores sobre o cenário do plano (T128): fatura do Roxinho de 1.283,00 vence 05/11; o João deve 300,00 (previsão 05/11); eu devo 200,00 ao Pedro
 * (05/11); Internet de 119,00 todo dia 10. Mesmos {@code CommitmentSchedule} do dashboard.
 */
@DisplayName("Geradores de notificação (T128, T129)")
class NotificationGeneratorsTest {

    private static final UUID USER = Ids.USER_ID;
    private static final LocalTime MORNING = LocalTime.of(8, 30);

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final RecurringTransaction internet = RecurringTransaction.ofAccount(new UUID(0, 77), USER, TransactionType.EXPENSE, Money.of("119.00"),
            "Internet", scenario.alimentacao.id(), scenario.nubank.id(), PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, LocalDate.of(2026, 10, 10)));

    GenerationContext context(LocalDate today, NotificationSettings settings, List<Goal> goals, List<Reminder> reminders) {
        CommitmentSchedule schedule = CommitmentSchedule.of(scenario.records(), List.of(internet), today);
        return new GenerationContext(USER, today, MORNING, settings, schedule, goals, reminders, Money.of("10217.00"), schedule.committed(
                com.moneylog.core.finance.domain.Horizon.D7));
    }

    private GenerationContext context(LocalDate today) {
        return context(today, NotificationSettings.defaults(USER), List.of(), List.of());
    }

    private void invoiceAndDebts() {
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        scenario.caso6Emprestimo();
        scenario.caso8DividaSemMovimentacao();
    }

    private static List<NotificationDraft> of(List<NotificationDraft> drafts, NotificationKind kind) {
        return drafts.stream().filter(d -> d.kind() == kind).toList();
    }

    @Test
    @DisplayName("fatura de 1.283,00 vence 05/11: 02/11 'vence em 3 dias', 04/11 'amanhã', 05/11 'hoje', 06/11 'atrasada'; 03/11 e 07/11 nada")
    void faturaPorDia() {
        invoiceAndDebts();
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 2))), NotificationKind.INVOICE_DUE)).singleElement().satisfies(d -> {
            assertThat(d.titleKey()).isEqualTo("notification.invoice.due3");
            assertThat(d.params()).containsEntry("amount", "1283.00").containsEntry("dueDate", "2026-11-05").containsEntry("name", scenario.roxinho.name());
            assertThat(d.dueOn()).isEqualTo(LocalDate.of(2026, 11, 5));
            assertThat(d.refId()).isEqualTo(scenario.roxinho.id());
        });
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 3))), NotificationKind.INVOICE_DUE)).isEmpty();
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 4))), NotificationKind.INVOICE_DUE).get(0).titleKey())
                .isEqualTo("notification.invoice.due1");
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 5))), NotificationKind.INVOICE_DUE).get(0).titleKey())
                .isEqualTo("notification.invoice.due0");
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 6))), NotificationKind.INVOICE_DUE).get(0).titleKey())
                .isEqualTo("notification.invoice.overdue");
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 7))), NotificationKind.INVOICE_DUE)).isEmpty();
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 12))), NotificationKind.INVOICE_DUE)).hasSize(1);
    }

    @Test
    @DisplayName("fatura paga antes do dia não gera nada")
    void faturaPaga() {
        invoiceAndDebts();
        scenario.caso3PagarFatura("1283.00");
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 2))), NotificationKind.INVOICE_DUE)).isEmpty();
    }

    @Test
    @DisplayName("cobrar o João (a receber): no dia e atrasada; eu devo ao Pedro: avisa 3, 1 e 0 dias antes")
    void dividas() {
        invoiceAndDebts();
        List<NotificationDraft> day = NotificationGenerators.generate(context(LocalDate.of(2026, 11, 5)));
        assertThat(of(day, NotificationKind.DEBT_COLLECT)).singleElement().satisfies(d -> {
            assertThat(d.titleKey()).isEqualTo("notification.debt.collect.due0");
            assertThat(d.params()).containsEntry("amount", "300.00");
        });
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 4))), NotificationKind.DEBT_COLLECT)).isEmpty();
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 6))), NotificationKind.DEBT_COLLECT).get(0).titleKey())
                .isEqualTo("notification.debt.collect.overdue");
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 2))), NotificationKind.DEBT_PAYABLE_DUE).get(0).titleKey())
                .isEqualTo("notification.debt.payable.due3");
    }

    @Test
    @DisplayName("dívida quitada não gera cobrança")
    void quitada() {
        scenario.caso6Emprestimo();
        scenario.caso7Devolucao();
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 5))), NotificationKind.DEBT_COLLECT)).isEmpty();
    }

    @Test
    @DisplayName("recorrência Internet (todo dia 10) pendente: aparece no dia, 'atrasada' no 1º dia e depois só a cada 7 dias")
    void recorrencia() {
        Map<LocalDate, String> expected = new java.util.LinkedHashMap<>();
        expected.put(LocalDate.of(2026, 10, 10), "notification.recurrence.today");
        expected.put(LocalDate.of(2026, 10, 11), "notification.recurrence.overdue");
        for (LocalDate date : expected.keySet()) {
            assertThat(of(NotificationGenerators.generate(context(date)), NotificationKind.RECURRENCE_PENDING)).as(date.toString()).singleElement()
                    .satisfies(d -> assertThat(d.titleKey()).isEqualTo(expected.get(date)));
        }
        for (LocalDate quiet : List.of(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16))) {
            assertThat(of(NotificationGenerators.generate(context(quiet)), NotificationKind.RECURRENCE_PENDING)).as(quiet.toString()).isEmpty();
        }
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 10, 17))), NotificationKind.RECURRENCE_PENDING)).hasSize(1);
        // chegou o dia da ocorrência seguinte: avisa "hoje" mesmo havendo a anterior pendente
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 10))), NotificationKind.RECURRENCE_PENDING)).singleElement()
                .satisfies(d -> assertThat(d.titleKey()).isEqualTo("notification.recurrence.today"));
    }

    @Test
    @DisplayName("tipo desligado nas configurações não gera")
    void tipoDesligado() {
        invoiceAndDebts();
        NotificationSettings settings = NotificationSettings.defaults(USER);
        settings.update(true, LocalTime.of(8, 0), SummaryFrequency.DAILY, Map.of(NotificationKind.INVOICE_DUE, false), QuietHours.DEFAULT, false, null);
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 2), settings, List.of(), List.of())), NotificationKind.INVOICE_DUE))
                .isEmpty();
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 2), settings, List.of(), List.of())),
                NotificationKind.DEBT_PAYABLE_DUE)).hasSize(1);
    }

    @Test
    @DisplayName("COMMITTED_NEXT_MONTH: só no dia 25, uma vez, com o total do mês seguinte vindo do CommitmentSchedule")
    void proximoMes() {
        invoiceAndDebts();
        LocalDate day25 = LocalDate.of(2026, 10, 25);
        List<NotificationDraft> drafts = of(NotificationGenerators.generate(context(day25)), NotificationKind.COMMITTED_NEXT_MONTH);
        assertThat(drafts).singleElement().satisfies(d -> {
            assertThat(d.params()).containsEntry("month", "2026-11");
            CommitmentSchedule schedule = CommitmentSchedule.of(scenario.records(), List.of(internet), day25);
            Money november = Money.sum(schedule.between(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)).stream()
                    .filter(c -> c.direction() == com.moneylog.core.sk.money.FlowDirection.OUT).map(c -> c.amount()).toList(),
                    com.moneylog.core.sk.money.CurrencyCode.BRL);
            assertThat(d.params()).containsEntry("total", november.toPlainString());
            assertThat(d.refId()).isEqualTo(USER);
        });
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 10, 24))), NotificationKind.COMMITTED_NEXT_MONTH)).isEmpty();
    }

    @Test
    @DisplayName("GOAL_DEADLINE: 30 dias antes do prazo de meta não atingida; meta atingida ou sem prazo não")
    void metas() {
        Goal goal = Goal.create(new UUID(0, 31), USER, "Reserva de emergência", Money.of("10000.00"), LocalDate.of(2026, 12, 1), GoalPriority.HIGH, null);
        Goal noDeadline = Goal.create(new UUID(0, 32), USER, "Sem prazo", Money.of("10000.00"), null, GoalPriority.LOW, null);
        NotificationSettings settings = NotificationSettings.defaults(USER);
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 1), settings, List.of(goal, noDeadline), List.of())),
                NotificationKind.GOAL_DEADLINE)).singleElement().satisfies(d -> {
                    assertThat(d.params()).containsEntry("name", "Reserva de emergência").containsEntry("progress", "0.0000");
                    assertThat(d.refId()).isEqualTo(goal.id());
                });
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 2), settings, List.of(goal), List.of())), NotificationKind.GOAL_DEADLINE))
                .isEmpty();
    }

    @Test
    @DisplayName("lembrete manual: no dia (e na hora, se houver); concluído não gera; adiado muda de dia")
    void lembretes() {
        Instant now = Instant.parse("2026-11-05T12:00:00Z");
        Reminder cobrar = Reminder.create(new UUID(0, 41), USER, "Cobrar João", LocalDate.of(2026, 11, 5), LocalTime.of(9, 0), null, "DEBT", new UUID(0, 42),
                now);
        Reminder done = Reminder.create(new UUID(0, 43), USER, "Feito", LocalDate.of(2026, 11, 5), null, null, null, null, now);
        done.done(now);
        NotificationSettings settings = NotificationSettings.defaults(USER);
        LocalDate day = LocalDate.of(2026, 11, 5);
        // 08:30, antes das 09:00: ainda não
        assertThat(of(NotificationGenerators.generate(context(day, settings, List.of(), List.of(cobrar, done))), NotificationKind.REMINDER)).isEmpty();
        CommitmentSchedule schedule = CommitmentSchedule.of(scenario.records(), List.of(), day);
        GenerationContext nineFifteen = new GenerationContext(USER, day, LocalTime.of(9, 15), settings, schedule, List.of(), List.of(cobrar, done),
                Money.of("0.00"), Money.of("0.00"));
        List<NotificationDraft> drafts = of(NotificationGenerators.generate(nineFifteen), NotificationKind.REMINDER);
        assertThat(drafts).singleElement().satisfies(d -> {
            assertThat(d.refId()).isEqualTo(cobrar.id());
            assertThat(d.params()).containsEntry("title", "Cobrar João").containsEntry("linkType", "DEBT");
        });
        cobrar.snooze(LocalDate.of(2026, 11, 6));
        assertThat(of(NotificationGenerators.generate(nineFifteen), NotificationKind.REMINDER)).isEmpty();
    }

    @Test
    @DisplayName("resumo diário (T129): chave daily.summary com disponível e comprometido 7d do dashboard; só a partir do horário; 1x por dia")
    void resumoDiario() {
        invoiceAndDebts();
        LocalDate day = LocalDate.of(2026, 11, 2);
        NotificationSettings settings = NotificationSettings.defaults(USER);
        settings.update(true, LocalTime.of(8, 30), SummaryFrequency.DAILY, Map.of(), QuietHours.DEFAULT, false, "Viana");
        List<NotificationDraft> summary = of(NotificationGenerators.generate(context(day, settings, List.of(), List.of())), NotificationKind.DAILY_SUMMARY);
        assertThat(summary).singleElement().satisfies(d -> {
            assertThat(d.titleKey()).isEqualTo("daily.summary");
            assertThat(d.params()).containsEntry("name", "Viana").containsEntry("available", "10217.00").containsKey("committed7d");
            assertThat(d.refId()).isEqualTo(USER);
            assertThat(d.identity()).isEqualTo(NotificationKind.DAILY_SUMMARY + "|" + USER + "|" + day);
        });
        settings.update(true, LocalTime.of(8, 31), SummaryFrequency.DAILY, Map.of(), QuietHours.DEFAULT, false, "Viana");
        assertThat(of(NotificationGenerators.generate(context(day, settings, List.of(), List.of())), NotificationKind.DAILY_SUMMARY)).isEmpty();
    }

    @Test
    @DisplayName("resumo: ONLY_DUE sem nada vencendo em 7 dias não gera; OFF nunca; sem nome, sem 'name'")
    void resumoFrequencias() {
        NotificationSettings onlyDue = NotificationSettings.defaults(USER);
        onlyDue.update(true, LocalTime.of(8, 0), SummaryFrequency.ONLY_DUE, Map.of(), QuietHours.DEFAULT, false, null);
        // 01/10: a Internet só vence em 10/10 (fora dos 7 dias) e não há fatura nem dívida
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 10, 1), onlyDue, List.of(), List.of())), NotificationKind.DAILY_SUMMARY))
                .isEmpty();
        invoiceAndDebts();
        List<NotificationDraft> withDue = of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 2), onlyDue, List.of(), List.of())),
                NotificationKind.DAILY_SUMMARY);
        assertThat(withDue).hasSize(1);
        assertThat(withDue.get(0).params()).doesNotContainKey("name");
        NotificationSettings off = NotificationSettings.defaults(USER);
        off.update(true, LocalTime.of(8, 0), SummaryFrequency.OFF, Map.of(), QuietHours.DEFAULT, false, null);
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 2), off, List.of(), List.of())), NotificationKind.DAILY_SUMMARY))
                .isEmpty();
    }

    @Test
    @DisplayName("mensagem diária: mesma entrada, mesma saída; meta ativa com prazo mais próximo vira goalName/goalProgress; sem meta, sem a linha")
    void mensagemDiaria() {
        Goal goal = Goal.create(new UUID(0, 31), USER, "Reserva", Money.of("10000.00"), LocalDate.of(2027, 1, 1), GoalPriority.HIGH, null);
        LocalDate today = LocalDate.of(2026, 11, 2);
        DailyMessage a = DailyMessage.of("Viana", Money.of("10217.00"), Money.of("620.00"), List.of(goal), today);
        DailyMessage b = DailyMessage.of("Viana", Money.of("10217.00"), Money.of("620.00"), List.of(goal), today);
        assertThat(a).isEqualTo(b);
        assertThat(a.key()).isEqualTo("daily.summary");
        assertThat(a.params()).containsEntry("name", "Viana").containsEntry("available", "10217.00").containsEntry("committed7d", "620.00")
                .containsEntry("goalName", "Reserva").containsEntry("goalProgress", "0.0000");
        DailyMessage none = DailyMessage.of(null, Money.of("1.00"), Money.of("0.00"), List.of(), today);
        assertThat(none.params()).containsOnlyKeys("available", "committed7d");
    }

    @Test
    @DisplayName("QuietHours atravessa a meia-noite")
    void silencio() {
        QuietHours quiet = QuietHours.DEFAULT;
        assertThat(quiet.contains(LocalTime.of(23, 0))).isTrue();
        assertThat(quiet.contains(LocalTime.of(6, 59))).isTrue();
        assertThat(quiet.contains(LocalTime.of(7, 0))).isFalse();
        assertThat(quiet.contains(LocalTime.of(21, 59))).isFalse();
        assertThat(new QuietHours(LocalTime.of(13, 0), LocalTime.of(14, 0)).contains(LocalTime.of(13, 30))).isTrue();
        assertThat(new QuietHours(LocalTime.of(8, 0), LocalTime.of(8, 0)).contains(LocalTime.of(8, 0))).isFalse();
    }
}
