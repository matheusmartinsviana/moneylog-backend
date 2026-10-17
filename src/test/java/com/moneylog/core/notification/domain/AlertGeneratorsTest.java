package com.moneylog.core.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.notification.domain.AlertInputs.CardInfo;
import com.moneylog.core.notification.domain.AlertInputs.DocumentComparison;
import com.moneylog.core.notification.domain.AlertInputs.OverdueReimbursement;
import com.moneylog.core.notification.domain.AlertInputs.RealEstateDue;
import com.moneylog.core.notification.domain.AlertInputs.SubscriptionInfo;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.util.Ids;

@DisplayName("I3: geradores dos alertas novos, bordas de limiar e relógio fixo")
class AlertGeneratorsTest {

    private static final UUID USER = Ids.USER_ID;
    private static final UUID REF = new UUID(0, 91);

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();

    private GenerationContext context(LocalDate today, NotificationAlertSettings alerts, AlertInputs inputs) {
        NotificationSettings settings = NotificationSettings.restore(USER, true, LocalTime.of(8, 0), SummaryFrequency.DAILY, Map.of(), QuietHours.DEFAULT,
                false, null, alerts, 0);
        CommitmentSchedule schedule = CommitmentSchedule.of(scenario.records(), List.of(), today);
        return new GenerationContext(USER, today, LocalTime.of(8, 30), settings, schedule, List.of(), List.of(), Money.of("10000.00"),
                Money.zero(CurrencyCode.BRL), inputs);
    }

    private static AlertInputs with(List<DocumentComparison> documents, List<SubscriptionInfo> subscriptions, List<OverdueReimbursement> overdue,
                                    List<RealEstateDue> dues, Money cash) {
        return new AlertInputs(Map.of(), List.of(), cash, dues, false, List.of(), List.of(), subscriptions, overdue, documents);
    }

    private static List<NotificationDraft> of(List<NotificationDraft> drafts, NotificationKind kind) {
        return drafts.stream().filter(d -> d.kind() == kind).toList();
    }

    @Test
    @DisplayName("divergência de documento: com tolerância 0 qualquer diferença avisa; a diferença igual à tolerância não avisa e 1 centavo acima avisa")
    void divergenciaDeDocumento() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        NotificationAlertSettings zero = NotificationAlertSettings.DEFAULTS;
        NotificationAlertSettings fifty = new NotificationAlertSettings(null, 3, Money.of("0.50"));
        AlertInputs equal = with(List.of(new DocumentComparison("ASSET", REF, "Saldo do FGTS", Money.of("100.00"), Money.of("100.00"))), List.of(), List.of(),
                List.of(), null);
        AlertInputs cent = with(List.of(new DocumentComparison("ASSET", REF, "Saldo do FGTS", Money.of("100.00"), Money.of("100.01"))), List.of(), List.of(),
                List.of(), null);
        AlertInputs half = with(List.of(new DocumentComparison("COMMITMENT_ITEM", REF, "Parcela 2", Money.of("100.00"), Money.of("100.50"))), List.of(),
                List.of(), List.of(), null);
        AlertInputs above = with(List.of(new DocumentComparison("COMMITMENT_ITEM", REF, "Parcela 2", Money.of("100.00"), Money.of("100.51"))), List.of(),
                List.of(), List.of(), null);

        assertThat(of(NotificationGenerators.generate(context(today, zero, equal)), NotificationKind.DOCUMENT_DIVERGENCE)).isEmpty();
        assertThat(of(NotificationGenerators.generate(context(today, zero, cent)), NotificationKind.DOCUMENT_DIVERGENCE)).singleElement()
                .satisfies(d -> assertThat(d.params()).containsEntry("difference", "0.01").containsEntry("label", "Saldo do FGTS"));
        assertThat(of(NotificationGenerators.generate(context(today, fifty, half)), NotificationKind.DOCUMENT_DIVERGENCE)).isEmpty();
        assertThat(of(NotificationGenerators.generate(context(today, fifty, above)), NotificationKind.DOCUMENT_DIVERGENCE)).hasSize(1);
    }

    @Test
    @DisplayName("assinatura sem uso: só dispara com N definido e quando passam de N dias (N dias exatos não); sem lastUsedOn nunca")
    void assinaturaSemUso() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        AlertInputs inputs = with(List.of(), List.of(
                new SubscriptionInfo(new UUID(0, 1), "Antiga", null, today.minusDays(31), false, false),
                new SubscriptionInfo(new UUID(0, 2), "Limite", null, today.minusDays(30), false, false),
                new SubscriptionInfo(new UUID(0, 3), "Sem marca", null, null, false, false)), List.of(), List.of(), null);

        assertThat(of(NotificationGenerators.generate(context(today, NotificationAlertSettings.DEFAULTS, inputs)), NotificationKind.SUBSCRIPTION_UNUSED))
                .as("sem N o alerta fica inativo").isEmpty();
        assertThat(of(NotificationGenerators.generate(context(today, new NotificationAlertSettings(30, 3, Money.zero(CurrencyCode.BRL)), inputs)),
                NotificationKind.SUBSCRIPTION_UNUSED)).singleElement().satisfies(d -> assertThat(d.params()).containsEntry("name", "Antiga").containsEntry("days", "31"));
    }

    @Test
    @DisplayName("sobreposição ignora caixa alta e baixa do grupo, exige 2 ou mais e gera um alerta por grupo; semanal só no dia da criação")
    void sobreposicaoESemanal() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        AlertInputs inputs = with(List.of(), List.of(
                new SubscriptionInfo(new UUID(0, 1), "A", "Video", null, false, false),
                new SubscriptionInfo(new UUID(0, 2), "B", "video", null, false, false),
                new SubscriptionInfo(new UUID(0, 3), "C", "musica", null, false, false),
                new SubscriptionInfo(new UUID(0, 4), "Semanal hoje", null, null, true, true),
                new SubscriptionInfo(new UUID(0, 5), "Semanal antiga", null, null, true, false)), List.of(), List.of(), null);

        List<NotificationDraft> drafts = NotificationGenerators.generate(context(today, NotificationAlertSettings.DEFAULTS, inputs));

        assertThat(of(drafts, NotificationKind.SUBSCRIPTION_OVERLAP)).hasSize(1);
        assertThat(of(drafts, NotificationKind.WEEKLY_CHARGE)).singleElement().satisfies(d -> assertThat(d.params()).containsEntry("name", "Semanal hoje"));
    }

    @Test
    @DisplayName("saldo abaixo do imóvel: só a próxima obrigação em até 30 dias (inclusive), saldo igual ao valor não avisa, 31 dias não avisa")
    void saldoAbaixoDoImovel() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        UUID commitment = new UUID(0, 7);

        assertThat(of(NotificationGenerators.generate(context(today, NotificationAlertSettings.DEFAULTS,
                with(List.of(), List.of(), List.of(), List.of(new RealEstateDue(commitment, today.plusDays(30), Money.of("2000.00"))), Money.of("1999.99")))),
                NotificationKind.BALANCE_BELOW_REAL_ESTATE)).hasSize(1);
        assertThat(of(NotificationGenerators.generate(context(today, NotificationAlertSettings.DEFAULTS,
                with(List.of(), List.of(), List.of(), List.of(new RealEstateDue(commitment, today.plusDays(30), Money.of("2000.00"))), Money.of("2000.00")))),
                NotificationKind.BALANCE_BELOW_REAL_ESTATE)).isEmpty();
        assertThat(of(NotificationGenerators.generate(context(today, NotificationAlertSettings.DEFAULTS,
                with(List.of(), List.of(), List.of(), List.of(new RealEstateDue(commitment, today.plusDays(31), Money.of("2000.00"))), Money.of("1.00")))),
                NotificationKind.BALANCE_BELOW_REAL_ESTATE)).isEmpty();
        assertThat(of(NotificationGenerators.generate(context(today, NotificationAlertSettings.DEFAULTS,
                with(List.of(), List.of(), List.of(), List.of(new RealEstateDue(commitment, today.minusDays(1), Money.of("2000.00"))), Money.of("1.00")))),
                NotificationKind.BALANCE_BELOW_REAL_ESTATE)).as("vencida ontem não é a próxima").isEmpty();
    }

    @Test
    @DisplayName("reembolso atrasado só depois do vencimento da fatura (no dia ainda não); cartão sem autoDebit definido (null) nunca gera o aviso de débito automático")
    void reembolsoEAutoDebit() {
        LocalDate due = LocalDate.of(2026, 11, 5);
        AlertInputs inputs = with(List.of(), List.of(), List.of(new OverdueReimbursement(REF, "Ana", Money.of("50.00"), due)), List.of(), null);

        assertThat(of(NotificationGenerators.generate(context(due, NotificationAlertSettings.DEFAULTS, inputs)), NotificationKind.THIRD_PARTY_OVERDUE)).isEmpty();
        assertThat(of(NotificationGenerators.generate(context(due.plusDays(1), NotificationAlertSettings.DEFAULTS, inputs)), NotificationKind.THIRD_PARTY_OVERDUE)).hasSize(1);
        AlertInputs unknownCard = new AlertInputs(Map.of(scenario.roxinho.id(), new CardInfo(scenario.roxinho.name(), null)), List.of(), null, List.of(), false,
                List.of(), List.of(), List.of(), List.of(), List.of());
        scenario.caso2CompraParcelada();
        assertThat(of(NotificationGenerators.generate(context(LocalDate.of(2026, 11, 2), NotificationAlertSettings.DEFAULTS, unknownCard)),
                NotificationKind.INVOICE_DUE_AUTODEBIT_OFF)).isEmpty();
    }
}
