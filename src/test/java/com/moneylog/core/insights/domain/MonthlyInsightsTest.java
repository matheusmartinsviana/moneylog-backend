package com.moneylog.core.insights.domain;

import static com.moneylog.core.insights.domain.InsightFacts.OCT;
import static com.moneylog.core.insights.domain.InsightFacts.TODAY;
import static com.moneylog.core.insights.domain.InsightFacts.brl;
import static com.moneylog.core.insights.domain.InsightFacts.closed;
import static com.moneylog.core.insights.domain.InsightFacts.closedMonth;
import static com.moneylog.core.insights.domain.InsightFacts.has;
import static com.moneylog.core.insights.domain.InsightFacts.insufficient;
import static com.moneylog.core.insights.domain.InsightFacts.month;
import static com.moneylog.core.insights.domain.InsightFacts.only;
import static com.moneylog.core.insights.domain.InsightFacts.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.insights.domain.Insight.Severity;
import com.moneylog.core.insights.domain.InsufficientData.Reason;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

@DisplayName("Insights mensais (T287 a T289): fórmulas do catálogo com dados sintéticos")
class MonthlyInsightsTest {

    private static final Competence SEP = Competence.of(2026, 9);

    private static List<MonthSnapshot> savers() {
        return closed(SEP, 3, "5000.00", "4500.00");
    }

    @Test
    @DisplayName("renda 5.000 e consumo 4.000 com média de 10% nos 3 fechados: s = 20%, Δpp = +10 e sai POSITIVE")
    void deveEmitirPoupancaEmAlta() {
        InsightSink sink = closedMonth(SEP).current(snapshot(SEP, "5000.00", "4000.00")).history(savers()).run();

        Insight insight = only(sink, "SAVINGS_RATE");
        assertThat(insight.severity()).isEqualTo(Severity.POSITIVE);
        assertThat(insight.key()).isEqualTo("insight.savings.rate.up");
        assertThat(insight.params()).containsEntry("rate", "20.00").containsEntry("average", "10.00").containsEntry("deltaPp", "10.00");
        assertThat(insight.period().value()).isEqualTo("2026-09");
    }

    @Test
    @DisplayName("2 meses fechados: não emite poupança e registra insufficient HISTORY")
    void naoDeveEmitirPoupancaComPoucoHistorico() {
        InsightSink sink = closedMonth(SEP).current(snapshot(SEP, "5000.00", "4000.00")).history(closed(SEP, 2, "5000.00", "4500.00")).run();

        assertThat(has(sink, "SAVINGS_RATE")).isFalse();
        assertThat(sink.insufficient()).anySatisfy(d -> {
            assertThat(d.type()).isEqualTo("SAVINGS_RATE");
            assertThat(d.reason()).isEqualTo(Reason.HISTORY);
            assertThat(d.missing()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("poupança negativa é ALERT; queda de 5 pontos ou mais é ATTENTION; variação pequena é INFO")
    void deveClassificarAPoupanca() {
        assertThat(only(closedMonth(SEP).current(snapshot(SEP, "1000.00", "1500.00")).history(closed(SEP, 3, "1000.00", "900.00")).run(),
                "SAVINGS_RATE").severity()).isEqualTo(Severity.ALERT);
        Insight down = only(closedMonth(SEP).current(snapshot(SEP, "5000.00", "4750.00")).history(savers()).run(), "SAVINGS_RATE");
        assertThat(down.severity()).isEqualTo(Severity.ATTENTION);
        assertThat(down.key()).isEqualTo("insight.savings.rate.down");
        assertThat(only(closedMonth(SEP).current(snapshot(SEP, "5000.00", "4550.00")).history(savers()).run(), "SAVINGS_RATE").severity())
                .isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("renda zero no mês: insufficient NO_INCOME")
    void naoDeveEmitirPoupancaSemRenda() {
        InsightSink sink = closedMonth(SEP).current(snapshot(SEP, "0.00", "100.00")).history(savers()).run();

        assertThat(insufficient(sink, "SAVINGS_RATE", Reason.NO_INCOME)).isTrue();
    }

    @Test
    @DisplayName("renda com valor decimal: -5 pontos exatos já é ATTENTION (limite inclusivo)")
    void deveSerInclusivoNoLimiteDeCincoPontos() {
        Insight insight = only(closedMonth(SEP).current(snapshot(SEP, "5000.00", "4750.00")).history(savers()).run(), "SAVINGS_RATE");

        assertThat(insight.params().get("deltaPp")).isEqualTo("-5.00");
        assertThat(insight.severity()).isEqualTo(Severity.ATTENTION);
    }

    private static InsightFacts.Builder october(int day) {
        return month(OCT, LocalDate.of(2026, 10, day));
    }

    private static String[] constant(int days, String amount) {
        String[] out = new String[days];
        java.util.Arrays.fill(out, amount);
        return out;
    }

    @Test
    @DisplayName("100,00 por dia nos 20 primeiros dias de outubro: fecha em 3.100,00 e, com renda recebida de 3.000,00, sai ALERT (over)")
    void deveProjetarOFechamento() {
        InsightSink sink = october(20).current(snapshot(OCT, "3000.00", "2000.00")).history(closed(OCT, 3, "3000.00", "2500.00"))
                .budget("3000.00", "2000.00", "0.00", "0.00", "0.00").daily(constant(20, "100.00")).run();

        Insight projection = only(sink, "MONTH_PROJECTION");
        assertThat(projection.severity()).isEqualTo(Severity.ALERT);
        assertThat(projection.key()).isEqualTo("insight.projection.over");
        assertThat(projection.params()).containsEntry("closing", "3100.00").containsEntry("lower", "3100.00").containsEntry("upper", "3100.00")
                .containsEntry("daysLeft", "11");
        assertThat(projection.basis()).isEqualTo(Insight.Basis.PROJECTION);
    }

    @Test
    @DisplayName("intervalo de 80%: gasto alternando 50/150 dá ±217,78 em volta de 3.100,00; com renda 3.200,00 o limite superior passa e sai ATTENTION (near)")
    void deveCalcularOIntervaloDe80Porcento() {
        String[] daily = new String[20];
        for (int i = 0; i < 20; i++) {
            daily[i] = i % 2 == 0 ? "50.00" : "150.00";
        }

        Insight projection = only(october(20).current(snapshot(OCT, "3200.00", "2000.00")).history(closed(OCT, 3, "3200.00", "2500.00"))
                .budget("3200.00", "2000.00", "0.00", "0.00", "0.00").daily(daily).run(), "MONTH_PROJECTION");

        assertThat(projection.severity()).isEqualTo(Severity.ATTENTION);
        assertThat(projection.key()).isEqualTo("insight.projection.near");
        assertThat(new BigDecimal(projection.params().get("upper"))).isBetween(new BigDecimal("3317.70"), new BigDecimal("3317.85"));
        assertThat(new BigDecimal(projection.params().get("lower"))).isBetween(new BigDecimal("2882.15"), new BigDecimal("2882.30"));
    }

    @Test
    @DisplayName("ocorrência de recorrência ainda prevista entra no fechamento uma vez; sem estourar a renda não emite")
    void deveSomarAOcorrenciaPrevista() {
        InsightSink sink = october(20).current(snapshot(OCT, "5000.00", "2000.00")).history(closed(OCT, 3, "5000.00", "2500.00"))
                .budget("5000.00", "2000.00", "0.00", "1000.00", "0.00").daily(constant(20, "100.00")).run();

        assertThat(has(sink, "MONTH_PROJECTION")).as("3.100 + 1.000 = 4.100 < 5.000").isFalse();
        assertThat(insufficient(sink, "MONTH_PROJECTION", Reason.PARTIAL_MONTH)).isFalse();
    }

    @Test
    @DisplayName("dia 5 do mês: não projeta e registra insufficient PARTIAL_MONTH; poupança e desvio também não saem")
    void naoDeveProjetarNoInicioDoMes() {
        InsightSink sink = october(5).current(snapshot(OCT, "5000.00", "400.00")).history(closed(OCT, 6, "5000.00", "4000.00"))
                .budget("5000.00", "400.00", "0.00", "0.00", "0.00").daily("100.00", "100.00", "100.00", "50.00", "150.00").run();

        assertThat(has(sink, "MONTH_PROJECTION")).isFalse();
        assertThat(insufficient(sink, "MONTH_PROJECTION", Reason.PARTIAL_MONTH)).isTrue();
        assertThat(has(sink, "SAVINGS_RATE")).isFalse();
        assertThat(has(sink, "SPEND_VS_BASELINE")).isFalse();
        assertThat(insufficient(sink, "SPEND_VS_BASELINE", Reason.PARTIAL_MONTH)).isTrue();
    }

    @Test
    @DisplayName("menos de 5 dias com gasto: não projeta")
    void naoDeveProjetarComPoucosDiasDeGasto() {
        String[] daily = constant(20, "0.00");
        daily[3] = "100.00";
        daily[9] = "100.00";

        InsightSink sink = october(20).current(snapshot(OCT, "3000.00", "200.00")).history(closed(OCT, 3, "3000.00", "2500.00"))
                .budget("3000.00", "200.00", "0.00", "0.00", "0.00").daily(daily).run();

        assertThat(insufficient(sink, "MONTH_PROJECTION", Reason.PARTIAL_MONTH)).isTrue();
    }

    private static List<MonthSnapshot> sixMonths() {
        long[] cents = {4000, 4100, 3900, 4050, 3950, 4000};
        List<MonthSnapshot> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            list.add(snapshot(SEP.minusMonths(6 - i), "5000.00", cents[i] + ".00"));
        }
        return list;
    }

    @Test
    @DisplayName("consumo de 6.000 contra mediana de 4.000 nos 6 fechados: z robusto 6,75 e sai ALERT")
    void deveEmitirDesvioAlto() {
        Insight insight = only(closedMonth(SEP).current(snapshot(SEP, "5000.00", "6000.00")).history(sixMonths()).run(), "SPEND_VS_BASELINE");

        assertThat(insight.severity()).isEqualTo(Severity.ALERT);
        assertThat(insight.key()).isEqualTo("insight.spend.high");
        assertThat(insight.params()).containsEntry("median", "4000.00").containsEntry("deltaAmount", "2000.00").containsEntry("deltaPercent", "50.00")
                .containsEntry("z", "6.75");
        assertThat(insight.confidence().n()).isEqualTo(6);
    }

    @Test
    @DisplayName("z entre 2,5 e 3,5 é ATTENTION; z abaixo de 2,5 não emite; queda forte é POSITIVE")
    void deveClassificarODesvio() {
        assertThat(only(closedMonth(SEP).current(snapshot(SEP, "5000.00", "4800.00")).history(sixMonths()).run(), "SPEND_VS_BASELINE").severity())
                .isEqualTo(Severity.ATTENTION);
        assertThat(has(closedMonth(SEP).current(snapshot(SEP, "5000.00", "4500.00")).history(sixMonths()).run(), "SPEND_VS_BASELINE")).isFalse();
        assertThat(only(closedMonth(SEP).current(snapshot(SEP, "5000.00", "3000.00")).history(sixMonths()).run(), "SPEND_VS_BASELINE").severity())
                .isEqualTo(Severity.POSITIVE);
    }

    @Test
    @DisplayName("z alto mas desvio de só R$ 50 (abaixo da materialidade de R$ 100): não emite")
    void naoDeveEmitirSemMaterialidade() {
        InsightSink sink = closedMonth(SEP).current(snapshot(SEP, "500.00", "150.00")).history(closed(SEP, 4, "500.00", "100.00")).run();

        assertThat(has(sink, "SPEND_VS_BASELINE")).isFalse();
    }

    @Test
    @DisplayName("menos de 3 meses fechados: insufficient HISTORY com quantos faltam")
    void naoDeveEmitirDesvioComPoucoHistorico() {
        InsightSink sink = closedMonth(SEP).current(snapshot(SEP, "5000.00", "6000.00")).history(closed(SEP, 2, "5000.00", "4000.00")).run();

        assertThat(sink.insufficient()).anySatisfy(d -> {
            assertThat(d.type()).isEqualTo("SPEND_VS_BASELINE");
            assertThat(d.missing()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("mês corrente usa o fechamento projetado, não o parcial; antes do dia 10 não emite")
    void deveUsarOFechamentoProjetadoNoMesCorrente() {
        InsightSink sink = october(20).current(snapshot(OCT, "3000.00", "2000.00")).history(closed(OCT, 6, "3000.00", "1000.00"))
                .budget("3000.00", "2000.00", "0.00", "0.00", "0.00").daily(constant(20, "100.00")).run();

        Insight insight = only(sink, "SPEND_VS_BASELINE");
        assertThat(insight.params()).containsEntry("amount", "3100.00").containsEntry("projected", "true");
    }

    private static final UUID FOOD = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID FUN = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    private static MonthSnapshot cat(Competence month, UUID category, String amount) {
        return snapshot(month, "5000.00", amount, Map.of(category, brl(amount)));
    }

    private static List<MonthSnapshot> categoryHistory(UUID category, String... amounts) {
        List<MonthSnapshot> list = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            list.add(cat(SEP.minusMonths(amounts.length - i), category, amounts[i]));
        }
        return list;
    }

    private static InsightFacts.Builder withCategories(MonthSnapshot current, List<MonthSnapshot> history) {
        InsightFacts.Builder b = closedMonth(SEP).current(current).history(history);
        b.names.put(FOOD, "Alimentação");
        b.names.put(FUN, "Lazer");
        return b;
    }

    @Test
    @DisplayName("limiar exato (20% e R$ 50,00) contra a mediana de 3 meses: emite, ATTENTION")
    void deveEmitirNoLimiteExato() {
        Insight insight = only(withCategories(cat(SEP, FOOD, "300.00"), categoryHistory(FOOD, "250.00", "250.00", "250.00")).run(), "CATEGORY_DELTA");

        assertThat(insight.severity()).isEqualTo(Severity.ATTENTION);
        assertThat(insight.key()).isEqualTo("insight.category.up");
        assertThat(insight.params()).containsEntry("previous", "250.00").containsEntry("deltaAmount", "50.00").containsEntry("deltaPercent", "20.0")
                .containsEntry("baseline", "MEDIAN_3").containsEntry("categoryName", "Alimentação");
        assertThat(insight.subjectId()).isEqualTo(FOOD.toString());
    }

    @Test
    @DisplayName("R$ 49,99 de diferença: não emite")
    void naoDeveEmitirAbaixoDeCinquentaReais() {
        assertThat(has(withCategories(cat(SEP, FOOD, "299.99"), categoryHistory(FOOD, "250.00", "250.00", "250.00")).run(), "CATEGORY_DELTA")).isFalse();
    }

    @Test
    @DisplayName("alta de R$ 300 com z robusto alto e diferença de pelo menos R$ 200: ALERT")
    void deveEscalarParaAlert() {
        Insight insight = only(withCategories(cat(SEP, FOOD, "500.00"), categoryHistory(FOOD, "200.00", "200.00", "200.00")).run(), "CATEGORY_DELTA");

        assertThat(insight.severity()).isEqualTo(Severity.ALERT);
    }

    @Test
    @DisplayName("queda grande em mês fechado é POSITIVE; no mês corrente a queda não é dita (o mês está incompleto)")
    void deveDizerQuedaSoEmMesFechado() {
        List<MonthSnapshot> history = categoryHistory(FOOD, "500.00", "500.00", "500.00");

        assertThat(only(withCategories(cat(SEP, FOOD, "300.00"), history).run(), "CATEGORY_DELTA").severity()).isEqualTo(Severity.POSITIVE);

        List<MonthSnapshot> octHistory = new ArrayList<>();
        for (int back = 3; back >= 1; back--) {
            octHistory.add(cat(OCT.minusMonths(back), FOOD, "500.00"));
        }
        InsightSink sink = october(12).current(cat(OCT, FOOD, "100.00")).history(octHistory).run();
        assertThat(has(sink, "CATEGORY_DELTA")).isFalse();
    }

    @Test
    @DisplayName("reembolso que leva a categoria a valor líquido negativo conta como zero na mediana")
    void deveLimitarAZeroOValorLiquidoNegativo() {
        List<MonthSnapshot> history = new ArrayList<>(List.of(
                snapshot(SEP.minusMonths(3), "5000.00", "-50.00", Map.of(FOOD, brl("-50.00"))),
                cat(SEP.minusMonths(2), FOOD, "300.00"), cat(SEP.minusMonths(1), FOOD, "300.00")));

        Insight insight = only(withCategories(cat(SEP, FOOD, "400.00"), history).run(), "CATEGORY_DELTA");

        assertThat(insight.params()).containsEntry("previous", "300.00").containsEntry("deltaAmount", "100.00");
        assertThat(has(withCategories(snapshot(SEP, "5000.00", "-80.00", Map.of(FOOD, brl("-80.00"))), history).run(), "CATEGORY_DELTA")).isFalse();
    }

    @Test
    @DisplayName("com 1 mês fechado compara com o mês anterior pelos limiares originais (10% e R$ 50)")
    void deveCompararComOMesAnteriorQuandoHaPoucoHistorico() {
        Insight insight = only(withCategories(cat(SEP, FOOD, "684.50"), categoryHistory(FOOD, "500.00")).run(), "CATEGORY_DELTA");

        assertThat(insight.params()).containsEntry("deltaAmount", "184.50").containsEntry("deltaPercent", "36.9").containsEntry("baseline", "PREVIOUS_MONTH");
        assertThat(insight.severity()).isEqualTo(Severity.ATTENTION);
        assertThat(has(withCategories(cat(SEP, FOOD, "550.00"), categoryHistory(FOOD, "500.00")).run(), "CATEGORY_DELTA")).isTrue();
        assertThat(has(withCategories(cat(SEP, FOOD, "549.99"), categoryHistory(FOOD, "500.00")).run(), "CATEGORY_DELTA")).isFalse();
    }

    @Test
    @DisplayName("2 meses fechados que não incluem o mês anterior: insufficient HISTORY")
    void naoDeveCompararComMesDistante() {
        List<MonthSnapshot> far = List.of(cat(SEP.minusMonths(5), FOOD, "500.00"), cat(SEP.minusMonths(4), FOOD, "500.00"));

        InsightSink sink = withCategories(cat(SEP, FOOD, "900.00"), far).run();

        assertThat(has(sink, "CATEGORY_DELTA")).isFalse();
        assertThat(insufficient(sink, "CATEGORY_DELTA", Reason.HISTORY)).isTrue();
    }

    @Test
    @DisplayName("sem nenhum mês fechado: insufficient HISTORY")
    void naoDeveEmitirSemHistorico() {
        assertThat(insufficient(withCategories(cat(SEP, FOOD, "900.00"), List.of()).run(), "CATEGORY_DELTA", Reason.HISTORY)).isTrue();
    }

    @Test
    @DisplayName("dinheiro livre = recebido − gasto − a acontecer − reservado: 5.000 − 2.000 − (500 + 300) − 200 = 2.000 (INFO)")
    void deveCalcularODinheiroLivre() {
        Insight insight = only(october(10).current(snapshot(OCT, "5000.00", "2500.00")).budget("5000.00", "2000.00", "500.00", "300.00", "200.00")
                .daily("0.00").run(), "FREE_MONEY");

        assertThat(insight.params()).containsEntry("free", "2000.00").containsEntry("upcoming", "800.00");
        assertThat(insight.severity()).isEqualTo(Severity.INFO);
        assertThat(insight.key()).isEqualTo("insight.freeMoney.ok");
    }

    @Test
    @DisplayName("livre abaixo de 15% da renda recebida é ATTENTION; negativo é ALERT; sem renda recebida não emite")
    void deveClassificarODinheiroLivre() {
        assertThat(only(october(10).budget("5000.00", "4200.00", "100.00", "100.00", "0.00").current(snapshot(OCT, "5000.00", "4300.00")).run(),
                "FREE_MONEY").severity()).isEqualTo(Severity.ATTENTION);
        Insight negative = only(october(10).budget("5000.00", "5200.00", "0.00", "0.00", "0.00").current(snapshot(OCT, "5000.00", "5200.00")).run(),
                "FREE_MONEY");
        assertThat(negative.severity()).isEqualTo(Severity.ALERT);
        assertThat(negative.key()).isEqualTo("insight.freeMoney.negative");
        InsightSink none = october(10).budget("0.00", "100.00", "0.00", "0.00", "0.00").run();
        assertThat(has(none, "FREE_MONEY")).isFalse();
        assertThat(insufficient(none, "FREE_MONEY", Reason.NO_INCOME)).isTrue();
    }

    @Test
    @DisplayName("fixos 1.500 + parcelas 900 com renda 4.000: índice 60%, ATTENTION; a parcela entra uma vez só")
    void deveCalcularOIndiceDeComprometimento() {
        Insight insight = only(closedMonth(SEP).current(snapshot(SEP, "4000.00", "3000.00")).recurringOut("1500.00").installments("900.00").run(),
                "COMMITMENT_INDEX");

        assertThat(insight.severity()).isEqualTo(Severity.ATTENTION);
        assertThat(insight.params()).containsEntry("percent", "60.00").containsEntry("fixed", "1500.00").containsEntry("installments", "900.00");
    }

    @Test
    @DisplayName("70% ou mais é ALERT; 50% exatos já é ATTENTION; abaixo disso não emite; renda zero é insufficient")
    void deveRespeitarOsLimiaresDoComprometimento() {
        assertThat(only(closedMonth(SEP).current(snapshot(SEP, "4000.00", "3000.00")).recurringOut("2000.00").installments("800.00").run(),
                "COMMITMENT_INDEX").severity()).isEqualTo(Severity.ALERT);
        assertThat(only(closedMonth(SEP).current(snapshot(SEP, "4000.00", "3000.00")).recurringOut("1200.00").installments("800.00").run(),
                "COMMITMENT_INDEX").severity()).isEqualTo(Severity.ATTENTION);
        assertThat(has(closedMonth(SEP).current(snapshot(SEP, "4000.00", "3000.00")).recurringOut("1200.00").installments("799.99").run(),
                "COMMITMENT_INDEX")).isFalse();
        assertThat(insufficient(closedMonth(SEP).current(snapshot(SEP, "0.00", "3000.00")).recurringOut("100.00").run(), "COMMITMENT_INDEX",
                Reason.NO_INCOME)).isTrue();
    }

    @Test
    @DisplayName("serviço da dívida: (parcelas + pagamentos de dívida) / renda: 30% ATTENTION, 40% ALERT; sem dívida não emite")
    void deveCalcularOServicoDaDivida() {
        Insight attention = only(closedMonth(SEP).current(snapshot(SEP, "4000.00", "3000.00")).installments("900.00").debt("400.00", "9000.00").run(),
                "DEBT_SERVICE");
        assertThat(attention.severity()).isEqualTo(Severity.ATTENTION);
        assertThat(attention.params()).containsEntry("percent", "32.50").containsEntry("debtTotal", "9000.00").containsEntry("debtToAnnualIncome", "18.75");
        assertThat(only(closedMonth(SEP).current(snapshot(SEP, "4000.00", "3000.00")).installments("1200.00").debt("400.00", "9000.00").run(),
                "DEBT_SERVICE").severity()).isEqualTo(Severity.ALERT);
        InsightSink none = closedMonth(SEP).current(snapshot(SEP, "4000.00", "3000.00")).run();
        assertThat(has(none, "DEBT_SERVICE")).isFalse();
        assertThat(insufficient(none, "DEBT_SERVICE", Reason.NO_INCOME)).isFalse();
        assertThat(insufficient(closedMonth(SEP).current(snapshot(SEP, "0.00", "3000.00")).installments("100.00").run(), "DEBT_SERVICE",
                Reason.NO_INCOME)).isTrue();
    }

    @Test
    @DisplayName("parcelas com pico no mês 4: ATTENTION informa peakMonth = mês + 4 e o aumento")
    void deveAcharOPicoDeParcelas() {
        Insight insight = only(october(10).load("500.00", "500.00", "500.00", "500.00", "900.00", "600.00", "500.00", "500.00", "500.00", "500.00",
                "500.00", "500.00").run(), "INSTALLMENT_LOAD");

        assertThat(insight.severity()).isEqualTo(Severity.ATTENTION);
        assertThat(insight.params()).containsEntry("peakMonth", "2027-02").containsEntry("peak", "900.00").containsEntry("increase", "400.00");
    }

    @Test
    @DisplayName("queda de 30% ou mais nos próximos 3 meses é POSITIVE; sem parcelas é insufficient NO_DATA")
    void deveAvisarDaQuedaDeParcelas() {
        Insight insight = only(october(10).load("1000.00", "900.00", "800.00", "600.00", "300.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00",
                "0.00").run(), "INSTALLMENT_LOAD");
        assertThat(insight.severity()).isEqualTo(Severity.POSITIVE);
        assertThat(insight.params()).containsEntry("laterMonth", "2027-01").containsEntry("dropPercent", "40.00");
        InsightSink none = october(10).load("0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00").run();
        assertThat(insufficient(none, "INSTALLMENT_LOAD", Reason.NO_DATA)).isTrue();
    }

    @Test
    @DisplayName("pico pequeno (aumento abaixo de R$ 100) não emite")
    void naoDeveEmitirPicoSemMaterialidade() {
        assertThat(has(october(10).load("50.00", "50.00", "50.00", "50.00", "99.00", "50.00", "50.00", "50.00", "50.00", "50.00", "50.00", "50.00")
                .run(), "INSTALLMENT_LOAD")).isFalse();
    }

    private static List<MonthSnapshot> essentials(String essential) {
        return closed(OCT, 3, "5000.00", "4000.00").stream()
                .map(m -> snapshot(m.month(), "5000.00", "4000.00", essential)).toList();
    }

    @Test
    @DisplayName("saldo 6.000 e essencial médio 2.000: cobertura 3,0 meses, sem alerta; com 1.500 de saldo, 0,75 mês ALERT")
    void deveCalcularACoberturaDaReserva() {
        assertThat(has(october(10).history(essentials("2000.00")).cash("6000.00").run(), "EMERGENCY_COVERAGE")).isFalse();
        Insight alert = only(october(10).history(essentials("2000.00")).cash("1500.00").run(), "EMERGENCY_COVERAGE");
        assertThat(alert.severity()).isEqualTo(Severity.ALERT);
        assertThat(alert.params()).containsEntry("months", "0.75").containsEntry("essentialMonthly", "2000.00").containsEntry("runwayMonths", "0.38");
    }

    @Test
    @DisplayName("abaixo de 3 meses é ATTENTION; 6 meses ou mais é POSITIVE; saldo negativo cobre 0 mês")
    void deveClassificarACobertura() {
        assertThat(only(october(10).history(essentials("2000.00")).cash("5000.00").run(), "EMERGENCY_COVERAGE").severity())
                .isEqualTo(Severity.ATTENTION);
        Insight ok = only(october(10).history(essentials("2000.00")).cash("12000.00").run(), "EMERGENCY_COVERAGE");
        assertThat(ok.severity()).isEqualTo(Severity.POSITIVE);
        assertThat(ok.key()).isEqualTo("insight.coverage.ok");
        assertThat(only(october(10).history(essentials("2000.00")).cash("-300.00").run(), "EMERGENCY_COVERAGE").params()).containsEntry("months", "0.00");
    }

    @Test
    @DisplayName("essencial zero ou histórico curto: insufficient; mês que não é o corrente: não se aplica")
    void naoDeveCalcularCoberturaSemBase() {
        assertThat(insufficient(october(10).history(essentials("0.00")).cash("5000.00").run(), "EMERGENCY_COVERAGE", Reason.NO_DATA)).isTrue();
        assertThat(insufficient(october(10).history(closed(OCT, 2, "5000.00", "4000.00")).cash("5000.00").run(), "EMERGENCY_COVERAGE",
                Reason.HISTORY)).isTrue();
        InsightSink past = closedMonth(SEP).history(essentials("2000.00")).cash("1.00").run();
        assertThat(has(past, "EMERGENCY_COVERAGE")).isFalse();
    }

    private static List<MonthSnapshot> incomes(String... values) {
        List<MonthSnapshot> list = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            list.add(snapshot(OCT.minusMonths(values.length - i), values[i], "100.00"));
        }
        return list;
    }

    @Test
    @DisplayName("renda [3000,3000,3000,3000]: CV 0 e não emite; [2000,4000,1500,4500]: CV 0,4249 e ATTENTION")
    void deveAvaliarAEstabilidadeDaRenda() {
        assertThat(has(october(10).history(incomes("3000.00", "3000.00", "3000.00", "3000.00")).run(), "INCOME_STABILITY")).isFalse();
        Insight insight = only(october(10).history(incomes("2000.00", "4000.00", "1500.00", "4500.00")).run(), "INCOME_STABILITY");
        assertThat(insight.severity()).isEqualTo(Severity.ATTENTION);
        assertThat(insight.params()).containsEntry("cv", "0.4249").containsEntry("average", "3000.00");
        assertThat(insufficient(october(10).history(incomes("2000.00", "4000.00", "1500.00")).run(), "INCOME_STABILITY", Reason.HISTORY)).isTrue();
    }

    @Test
    @DisplayName("meta fora do ritmo: ATTENTION com o ritmo necessário vindo do plano (não recalculado); no ritmo com sequência de 3: POSITIVE")
    void deveRepetirOPlanoDasMetas() {
        UUID behind = UUID.randomUUID();
        UUID ahead = UUID.randomUUID();
        InsightSink sink = october(10).goals(
                new MonthFacts.GoalFact(behind, "Viagem", true, false, brl("350.00"), brl("700.00"), 8, 0),
                new MonthFacts.GoalFact(ahead, "Reserva", true, true, brl("200.00"), brl("0.00"), 5, 3),
                new MonthFacts.GoalFact(UUID.randomUUID(), "Sem plano", false, false, brl("0.00"), brl("0.00"), 0, 0)).run();

        List<Insight> goals = sink.insights().stream().filter(i -> i.type().equals("GOAL_PACE")).toList();
        assertThat(goals).hasSize(2);
        Insight late = goals.stream().filter(i -> i.severity() == Severity.ATTENTION).findFirst().orElseThrow();
        assertThat(late.params()).containsEntry("requiredPace", "350.00").containsEntry("gap", "700.00").containsEntry("goalName", "Viagem");
        assertThat(late.subjectId()).isEqualTo(behind.toString());
        assertThat(goals.stream().filter(i -> i.severity() == Severity.POSITIVE)).hasSize(1);
        assertThat(insufficient(october(10).goals(new MonthFacts.GoalFact(UUID.randomUUID(), "X", false, false, brl("0.00"), brl("0.00"), 0, 0))
                .run(), "GOAL_PACE", Reason.NO_PLAN)).isTrue();
    }

    private static MonthSnapshot categories(String... amounts) {
        Map<UUID, Money> map = new java.util.HashMap<>();
        for (int i = 0; i < amounts.length; i++) {
            map.put(new UUID(0, i + 1), brl(amounts[i]));
        }
        return snapshot(SEP, "5000.00", "1000.00", map);
    }

    @Test
    @DisplayName("3 maiores categorias com 60% ou mais do consumo (mínimo de 4 categorias): INFO de prioridade mínima")
    void deveAvisarDaConcentracao() {
        Insight insight = only(closedMonth(SEP).current(categories("400.00", "300.00", "200.00", "100.00")).run(), "CONCENTRATION");
        assertThat(insight.severity()).isEqualTo(Severity.INFO);
        assertThat(insight.params()).containsEntry("share", "90.00").containsEntry("top3", "900.00").containsEntry("total", "1000.00");
        assertThat(has(closedMonth(SEP).current(categories("200.00", "200.00", "200.00", "200.00", "200.00")).run(), "CONCENTRATION")).as("60% exatos").isTrue();
        assertThat(has(closedMonth(SEP).current(categories("100.00", "100.00", "100.00", "100.00", "100.00", "100.00")).run(), "CONCENTRATION")).isFalse();
        assertThat(has(closedMonth(SEP).current(categories("400.00", "300.00", "300.00")).run(), "CONCENTRATION")).as("menos de 4 categorias").isFalse();
    }

    @Test
    @DisplayName("a soma das 3 maiores nunca passa do consumo positivo por categoria (reembolso líquido fica fora)")
    void naoDeveContarReembolsoNoDenominador() {
        Insight insight = only(closedMonth(SEP).current(categories("400.00", "300.00", "200.00", "100.00", "-80.00")).run(), "CONCENTRATION");

        assertThat(new BigDecimal(insight.params().get("top3"))).isLessThanOrEqualTo(new BigDecimal(insight.params().get("total")));
    }

    @Test
    @DisplayName("uso do limite: 70% ATTENTION, 90% ALERT; cartão sem limite não emite")
    void deveCalcularOUsoDoLimite() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        InsightSink sink = october(10).cards(new MonthFacts.CardFact(a, "Roxinho", brl("10000.00"), brl("7000.00")),
                new MonthFacts.CardFact(b, "Cinza", brl("1000.00"), brl("900.00")),
                new MonthFacts.CardFact(c, "Sem limite", null, brl("0.00"))).run();

        List<Insight> usage = sink.insights().stream().filter(i -> i.type().equals("CARD_LIMIT_USAGE")).toList();
        assertThat(usage).hasSize(2);
        assertThat(usage).anySatisfy(i -> {
            assertThat(i.severity()).isEqualTo(Severity.ATTENTION);
            assertThat(i.params()).containsEntry("percent", "70.00").containsEntry("cardName", "Roxinho");
        });
        assertThat(usage).anySatisfy(i -> assertThat(i.severity()).isEqualTo(Severity.ALERT));
        InsightSink onlyUnlimited = october(10).cards(new MonthFacts.CardFact(c, "Sem limite", null, brl("0.00"))).run();
        assertThat(has(onlyUnlimited, "CARD_LIMIT_USAGE")).isFalse();
        assertThat(insufficient(onlyUnlimited, "CARD_LIMIT_USAGE", Reason.NO_LIMIT)).isTrue();
    }

    @Test
    @DisplayName("mês futuro: nada é calculado (insufficient ALL FUTURE_MONTH)")
    void naoDeveCalcularMesFuturo() {
        InsightSink sink = month(Competence.of(2027, 3), TODAY).run();

        assertThat(sink.insights()).isEmpty();
        assertThat(insufficient(sink, "ALL", Reason.FUTURE_MONTH)).isTrue();
    }

    @Test
    @DisplayName("mesma entrada, mesma saída (determinismo)")
    void deveSerDeterministico() {
        InsightFacts.Builder b = october(20).current(snapshot(OCT, "3000.00", "2000.00")).history(closed(OCT, 3, "3000.00", "2500.00"))
                .budget("3000.00", "2000.00", "0.00", "0.00", "0.00").daily(constant(20, "100.00"));

        assertThat(b.run().insights()).isEqualTo(b.run().insights());
    }
}
