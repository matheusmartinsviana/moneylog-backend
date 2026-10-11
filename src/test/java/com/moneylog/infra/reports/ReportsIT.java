package com.moneylog.infra.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;

@DisplayName("Relatórios: série de entradas e saídas e consumo por categoria (T184, T185)")
class ReportsIT extends E4ItBase {

    @MockitoSpyBean
    private FinancialRecordsGateway recordsGateway;

    private String tech;
    private String xp;
    private String xpInvest;

    private String expense(String amount, String date, String description, String category) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description + "\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + category + "\",\"method\":\"PIX\"}";
    }

    private String incomeOf(String amount, String date, String description) {
        return "{\"type\":\"INCOME\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description + "\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + salary + "\",\"method\":\"PIX\"}";
    }

    private void seed() throws Exception {
        tech = category("Tecnologia", CategoryKind.EXPENSE);
        xp = createdBody(post("/v1/accounts", "{\"name\":\"XP\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},"
                + "\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        xpInvest = createdBody(post("/v1/accounts", "{\"name\":\"XP Invest\",\"type\":\"INVESTMENT\",\"openingBalance\":{\"amount\":\"0.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        created(incomeOf("3000.00", "2026-10-05", "Salário"));
        JsonNode first = created(expense("100.00", "2026-10-05", "Mercado", food));
        created("{\"type\":\"REFUND\",\"amount\":\"50.00\",\"date\":\"2026-10-09\",\"description\":\"Reembolso\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"refundOfTransactionId\":\"" + first.get("id").asString() + "\"}");
        created(expense("100.00", "2026-10-10", "Restaurante", food));
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1200.00\",\"date\":\"2026-10-10\",\"description\":\"Notebook\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + tech + "\",\"installments\":6}");
        created("{\"type\":\"TRANSFER\",\"amount\":\"1000.00\",\"date\":\"2026-10-10\",\"description\":\"Transf\",\"fromAccountId\":\"" + nubank
                + "\",\"toAccountId\":\"" + xp + "\"}");
        created("{\"type\":\"INVESTMENT\",\"amount\":\"10000.00\",\"date\":\"2026-10-10\",\"description\":\"CDB\",\"cashAccountId\":\"" + nubank
                + "\",\"investmentAccountId\":\"" + xpInvest + "\",\"direction\":\"CONTRIBUTION\"}");
        loan("300.00", "2026-10-10", joao, null);
        String invoicePayment = "/v1/invoices/";
        JsonNode invoices = okBody(get("/v1/cards/" + card + "/invoices"));
        String invoiceId = null;
        for (JsonNode invoice : invoices.get("items")) {
            if (invoice.get("status").asString().equals("OPEN")) {
                invoiceId = invoice.get("id").asString();
            }
        }
        assertThat(invoiceId).as("a fatura aberta com a compra").isNotNull();
        okBody(post(invoicePayment + invoiceId + "/payments", "{\"amount\":\"150.00\",\"date\":\"2026-10-10\",\"accountId\":\"" + nubank + "\"}")
                .header("Idempotency-Key", UUID.randomUUID().toString()));
        created(expense("80.00", "2026-10-20", "Agendada", food));
    }

    private JsonNode series(String period) throws Exception {
        return okBody(get("/v1/reports/income-expenses?period=" + period));
    }

    private static String amount(JsonNode money) {
        return money.get("amount").asString();
    }

    private static JsonNode pointAt(JsonNode series, String day) {
        for (JsonNode p : series.get("points")) {
            if (p.get("periodStart").asString().compareTo(day) <= 0 && p.get("periodEnd").asString().compareTo(day) >= 0) {
                return p;
            }
        }
        throw new AssertionError("sem ponto em " + day);
    }

    @Test
    @DisplayName("CA1 e CA3: D7 tem 7 pontos densos; só receita e consumo (parcela no dia dela, reembolso abate); fatura paga, transferência, aplicação e empréstimo ficam fora; agendada não aparece")
    void deveTrazerSoReceitaEConsumo() throws Exception {
        seed();

        JsonNode d7 = series("D7");

        assertThat(d7.get("period").asString()).isEqualTo("D7");
        assertThat(d7.get("from").asString()).isEqualTo("2026-10-04");
        assertThat(d7.get("to").asString()).isEqualTo("2026-10-10");
        assertThat(d7.get("basis").asString()).isEqualTo("DATE");
        assertThat(d7.get("granularity").asString()).isEqualTo("DAY");
        assertThat(d7.get("points")).hasSize(7);
        assertThat(d7.get("points").get(6).get("partial").asBoolean()).isTrue();
        assertThat(d7.get("points").get(5).get("partial").asBoolean()).isFalse();
        assertThat(amount(pointAt(d7, "2026-10-05").get("income"))).isEqualTo("3000.00");
        assertThat(amount(pointAt(d7, "2026-10-05").get("expenses"))).isEqualTo("100.00");
        assertThat(amount(pointAt(d7, "2026-10-09").get("expenses"))).as("dia só com reembolso: negativo").isEqualTo("-50.00");
        assertThat(amount(pointAt(d7, "2026-10-09").get("net"))).isEqualTo("50.00");
        assertThat(amount(pointAt(d7, "2026-10-10").get("expenses"))).as("Pix 100 + a parcela 1/6 de 200").isEqualTo("300.00");
        assertThat(amount(pointAt(d7, "2026-10-10").get("income"))).isEqualTo("0.00");
        assertThat(amount(d7.get("totals").get("income"))).isEqualTo("3000.00");
        assertThat(amount(d7.get("totals").get("expenses"))).as("100 − 50 + 100 + 200 (sem a agendada de 20/10)").isEqualTo("350.00");
        assertThat(amount(d7.get("totals").get("net"))).isEqualTo("2650.00");
    }

    @Test
    @DisplayName("CA3 e CA3a: D30 tem 30 pontos, M3 de 13 a 15 pontos recortados, M12 tem 12 competências e o mês corrente (inteiro, partial) inclui a agendada de 20/10")
    void deveTerAsGranularidades() throws Exception {
        seed();

        JsonNode d30 = series("D30");
        assertThat(d30.get("points")).hasSize(30);
        assertThat(d30.get("from").asString()).isEqualTo("2026-09-11");

        JsonNode m3 = series("M3");
        assertThat(m3.get("granularity").asString()).isEqualTo("WEEK");
        assertThat(m3.get("from").asString()).isEqualTo("2026-07-10");
        assertThat(m3.get("points").size()).isBetween(13, 15);
        assertThat(m3.get("points").get(0).get("periodStart").asString()).isEqualTo("2026-07-10");
        assertThat(m3.get("points").get(m3.get("points").size() - 1).get("periodEnd").asString()).isEqualTo("2026-10-10");
        assertThat(amount(m3.get("totals").get("expenses"))).isEqualTo("350.00");

        JsonNode m12 = series("M12");
        assertThat(m12.get("points")).hasSize(12);
        assertThat(m12.get("basis").asString()).isEqualTo("COMPETENCE");
        assertThat(m12.get("granularity").asString()).isEqualTo("MONTH");
        assertThat(m12.get("from").asString()).isEqualTo("2025-11-01");
        assertThat(m12.get("to").asString()).isEqualTo("2026-10-31");
        JsonNode october = m12.get("points").get(11);
        assertThat(october.get("periodStart").asString()).isEqualTo("2026-10-01");
        assertThat(october.get("partial").asBoolean()).isTrue();
        assertThat(amount(october.get("expenses"))).as("350 + a agendada de 80").isEqualTo("430.00");
        assertThat(amount(m12.get("points").get(10).get("expenses"))).isEqualTo("0.00");
        assertThat(amount(m12.get("totals").get("expenses"))).as("as parcelas 2/6 a 6/6 (nov a mar) ficam fora da janela, que termina no mês corrente")
                .isEqualTo("430.00");
    }

    @Test
    @DisplayName("CA2: cada ponto mensal bate, campo a campo, com o dashboard, com entries e com o resumo mensal da mesma competência")
    void deveBaterComDashboardEntriesEResumo() throws Exception {
        seed();
        JsonNode m12 = series("M12");

        for (JsonNode point : m12.get("points")) {
            String month = point.get("periodStart").asString().substring(0, 7);
            String lastDay = YearMonth.parse(month).atEndOfMonth().toString();
            JsonNode dashboard = okBody(get("/v1/dashboard/summary?asOf=" + lastDay)).get("month");
            assertThat(amount(point.get("income"))).as("dashboard income " + month).isEqualTo(amount(dashboard.get("income")));
            assertThat(amount(point.get("expenses"))).as("dashboard consumption " + month).isEqualTo(amount(dashboard.get("consumption")));
            assertThat(amount(point.get("expenses"))).as("entries consumo " + month)
                    .isEqualTo(amount(okBody(get("/v1/entries?competence=" + month + "&lens=CONSUMPTION")).get("totals").get("total")));
            assertThat(amount(point.get("income"))).as("entries receita " + month)
                    .isEqualTo(amount(okBody(get("/v1/entries?competence=" + month + "&lens=INCOME")).get("totals").get("total")));
            JsonNode summary = okBody(get("/v1/summaries/" + month));
            assertThat(amount(point.get("income"))).as("resumo income " + month).isEqualTo(amount(summary.get("income")));
            assertThat(amount(point.get("expenses"))).as("resumo expenses " + month).isEqualTo(amount(summary.get("expenses")));
            assertThat(amount(point.get("net"))).as("resumo net " + month).isEqualTo(amount(summary.get("net")));
        }
    }

    @Test
    @DisplayName("CA4: movimentação estornada não conta, e a série não altera o razão")
    void deveIgnorarEstornadasENaoAlterarORazao() throws Exception {
        JsonNode pix = created(expense("100.00", "2026-10-10", "Engano", food));
        created(expense("40.00", "2026-10-10", "Certa", food));
        mockMvc.perform(post("/v1/transactions/" + pix.get("id").asString() + "/void", "{\"reason\":\"engano\"}")).andExpect(status().isOk());
        long postings = count("ledger_postings");
        long transactions = count("transactions");

        JsonNode d7 = series("D7");
        okBody(get("/v1/reports/spending-by-category"));

        assertThat(amount(d7.get("totals").get("expenses"))).isEqualTo("40.00");
        assertThat(count("ledger_postings")).isEqualTo(postings);
        assertThat(count("transactions")).isEqualTo(transactions);
    }

    @Test
    @DisplayName("fronteira do dia em America/Sao_Paulo: 23:59:59 ainda é 10/10 e 00:00:00 já é 11/10 (relógio injetado)")
    void deveRespeitarOFusoNaFronteiraDoDia() throws Exception {
        created(expense("10.00", "2026-10-10", "Noite", food));
        created(expense("20.00", "2026-10-11", "Madrugada", food));

        advanceTo(Instant.parse("2026-10-11T02:59:59Z"));
        JsonNode before = series("D7");
        assertThat(before.get("to").asString()).isEqualTo("2026-10-10");
        assertThat(amount(before.get("totals").get("expenses"))).as("o lançamento de 11/10 ainda é futuro").isEqualTo("10.00");

        advanceTo(Instant.parse("2026-10-11T03:00:00Z"));
        JsonNode after = series("D7");
        assertThat(after.get("to").asString()).isEqualTo("2026-10-11");
        assertThat(after.get("from").asString()).isEqualTo("2026-10-05");
        assertThat(amount(pointAt(after, "2026-10-10").get("expenses"))).isEqualTo("10.00");
        assertThat(amount(pointAt(after, "2026-10-11").get("expenses"))).isEqualTo("20.00");
        assertThat(after.get("points").get(6).get("partial").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("fronteira de mês e de ano: hoje = 01/01 e 31/12 atravessam a virada sem ponto duplicado ou faltando")
    void deveAtravessarAViradaDoAno() throws Exception {
        created(expense("10.00", "2026-12-31", "Ceia", food));
        created(expense("20.00", "2027-01-01", "Ano novo", food));

        advanceTo(Instant.parse("2027-01-01T15:00:00Z"));
        JsonNode d7 = series("D7");
        assertThat(d7.get("from").asString()).isEqualTo("2026-12-26");
        assertThat(d7.get("points")).hasSize(7);
        assertThat(amount(pointAt(d7, "2026-12-31").get("expenses"))).isEqualTo("10.00");
        assertThat(amount(pointAt(d7, "2027-01-01").get("expenses"))).isEqualTo("20.00");
        JsonNode m12 = series("M12");
        assertThat(m12.get("points").get(0).get("periodStart").asString()).isEqualTo("2026-02-01");
        assertThat(amount(m12.get("points").get(11).get("expenses"))).isEqualTo("20.00");
        assertThat(amount(m12.get("points").get(10).get("expenses"))).isEqualTo("10.00");

        advanceTo(Instant.parse("2026-12-31T15:00:00Z"));
        assertThat(amount(series("D7").get("totals").get("expenses"))).as("01/01 ainda é futuro em 31/12").isEqualTo("10.00");
    }

    @ParameterizedTest(name = "period={0}")
    @ValueSource(strings = {"X", "d7", "D8", "", "M6"})
    @DisplayName("CA5: period desconhecido → 400 REPORT_PERIOD_INVALID")
    void deveRecusarPeriodoDesconhecido(String period) throws Exception {
        mockMvc.perform(get("/v1/reports/income-expenses?period=" + period)).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly()).andExpect(jsonPath("$.code").value("REPORT_PERIOD_INVALID"));
    }

    @Test
    @DisplayName("CA5: period ausente → 400 REPORT_PERIOD_INVALID; sem token → 401; Cache-Control: no-store nas duas rotas")
    void deveValidarAEntradaEOCache() throws Exception {
        mockMvc.perform(get("/v1/reports/income-expenses")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly()).andExpect(jsonPath("$.code").value("REPORT_PERIOD_INVALID"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/reports/income-expenses?period=D7"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/reports/spending-by-category"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/reports/income-expenses?period=D7")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(header().string("Cache-Control", "no-store, private"));
        mockMvc.perform(get("/v1/reports/spending-by-category")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    @Test
    @DisplayName("CA5: outro usuário nunca vê meus lançamentos; sem lançamentos a série é de zeros (não 404)")
    void deveIsolarPorUsuario() throws Exception {
        created(expense("77.00", "2026-10-10", "Meu", food));
        UUID other = UUID.randomUUID();
        committed(() -> com.moneylog.util.SqlFixtures.insertUser(jdbc, other, "other-" + other + "@example.com"));
        String otherBearer = auth.bearer(other);

        JsonNode series = body(mockMvc.perform(getAs(otherBearer, "/v1/reports/income-expenses?period=D30")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        JsonNode spending = body(mockMvc.perform(getAs(otherBearer, "/v1/reports/spending-by-category")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());

        assertThat(series.get("points")).hasSize(30);
        assertThat(amount(series.get("totals").get("expenses"))).isEqualTo("0.00");
        assertThat(spending.get("items")).isEmpty();
        assertThat(spending.get("other").isNull()).isTrue();
        assertThat(amount(spending.get("total"))).isEqualTo("0.00");
    }

    @Test
    @DisplayName("T185: top 5 de 8 categorias em ordem decrescente, other soma as 3 restantes; total = month.consumption = entries.totals.total; share com 4 casas")
    void deveListarAsMaioresCategorias() throws Exception {
        String[] amounts = {"10.00", "80.00", "30.00", "70.00", "20.00", "60.00", "40.00", "50.00"};
        for (int i = 0; i < amounts.length; i++) {
            created(expense(amounts[i], "2026-10-10", "Gasto " + i, category("Categoria " + i, CategoryKind.EXPENSE)));
        }

        JsonNode spending = okBody(get("/v1/reports/spending-by-category?limit=5"));

        assertThat(spending.get("basis").asString()).isEqualTo("COMPETENCE");
        assertThat(spending.get("from").asString()).isEqualTo("2026-10-01");
        assertThat(spending.get("to").asString()).isEqualTo("2026-10-31");
        List<String> items = new ArrayList<>();
        spending.get("items").forEach(i -> items.add(i.get("category").get("name").asString() + "=" + amount(i.get("amount")) + "@" + i.get("share").asString()));
        assertThat(items).containsExactly("Categoria 1=80.00@0.2222", "Categoria 3=70.00@0.1944", "Categoria 5=60.00@0.1667",
                "Categoria 7=50.00@0.1389", "Categoria 6=40.00@0.1111");
        assertThat(amount(spending.get("other").get("amount"))).isEqualTo("60.00");
        assertThat(spending.get("other").get("categories").asInt()).isEqualTo(3);
        assertThat(spending.get("other").get("share").asString()).isEqualTo("0.1667");
        assertThat(amount(spending.get("total"))).isEqualTo("360.00");
        assertThat(amount(spending.get("base"))).isEqualTo("360.00");
        assertThat(amount(spending.get("total"))).isEqualTo(amount(okBody(get("/v1/dashboard/summary")).get("month").get("consumption")));
        assertThat(amount(spending.get("total"))).isEqualTo(amount(okBody(get("/v1/entries?competence=2026-10&lens=CONSUMPTION")).get("totals").get("total")));
        assertThat(amount(spending.get("total"))).isEqualTo(amount(okBody(get("/v1/summaries/2026-10")).get("expenses")));
    }

    @Test
    @DisplayName("T185: reembolso de mês seguinte deixa a categoria líquida negativa → fora dos itens e de other, base = Σ positivas, Σ share ≤ 1,0000; padrão limit 5 e month atual")
    void deveTirarACategoriaNegativa() throws Exception {
        String a = category("A", CategoryKind.EXPENSE);
        String b = category("B", CategoryKind.EXPENSE);
        String c = category("C", CategoryKind.EXPENSE);
        created(expense("100.00", "2026-10-10", "a", a));
        created(expense("50.00", "2026-10-10", "b", b));
        JsonNode september = created(expense("20.00", "2026-09-20", "c", c));
        created("{\"type\":\"REFUND\",\"amount\":\"20.00\",\"date\":\"2026-10-10\",\"description\":\"Reembolso\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + c + "\",\"refundOfTransactionId\":\"" + september.get("id").asString() + "\"}");

        JsonNode spending = okBody(get("/v1/reports/spending-by-category"));

        assertThat(spending.get("items")).hasSize(2);
        assertThat(spending.get("other").isNull()).isTrue();
        assertThat(amount(spending.get("total"))).as("o total líquido considera a categoria negativa").isEqualTo("130.00");
        assertThat(amount(spending.get("base"))).isEqualTo("150.00");
        assertThat(spending.get("items").get(0).get("share").asString()).isEqualTo("0.6667");
        assertThat(spending.get("items").get(1).get("share").asString()).isEqualTo("0.3333");
        assertThat(amount(spending.get("total"))).isEqualTo(amount(okBody(get("/v1/entries?competence=2026-10&lens=CONSUMPTION")).get("totals").get("total")));
        JsonNode septemberView = okBody(get("/v1/reports/spending-by-category?month=2026-09"));
        assertThat(septemberView.get("items")).hasSize(1);
        assertThat(septemberView.get("items").get(0).get("share").asString()).isEqualTo("1.0000");
    }

    @Test
    @DisplayName("T185: consumo de dívida (S-137) entra na categoria dele como em /v1/entries; sem consumo → items [] e other null")
    void deveContarAsDividasDeConsumo() throws Exception {
        JsonNode empty = okBody(get("/v1/reports/spending-by-category"));
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.get("other").isNull()).isTrue();

        createdBody(post("/v1/debts", "{\"personId\":\"" + joao + "\",\"direction\":\"PAYABLE\",\"principal\":\"80.00\",\"description\":\"Jantar\","
                + "\"date\":\"2026-10-10\",\"categoryId\":\"" + food + "\"}"));

        JsonNode spending = okBody(get("/v1/reports/spending-by-category"));
        assertThat(spending.get("items")).hasSize(1);
        assertThat(amount(spending.get("items").get(0).get("amount"))).isEqualTo("80.00");
        assertThat(amount(spending.get("total"))).isEqualTo(amount(okBody(get("/v1/entries?competence=2026-10&lens=CONSUMPTION")).get("totals").get("total")));
    }

    @ParameterizedTest(name = "month={0}")
    @ValueSource(strings = {"2026-13", "202610", "abc", "1999-12", "2101-01", "2026-1"})
    @DisplayName("T185: month inválido → 400 REPORT_MONTH_INVALID")
    void deveRecusarMesInvalido(String month) throws Exception {
        mockMvc.perform(get("/v1/reports/spending-by-category?month=" + month)).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly()).andExpect(jsonPath("$.code").value("REPORT_MONTH_INVALID"));
    }

    @ParameterizedTest(name = "limit={0}")
    @ValueSource(strings = {"0", "11", "-1", "x"})
    @DisplayName("T185: limit fora de 1 a 10 → 400 VALIDATION_FAILED")
    void deveRecusarLimitInvalido(String limit) throws Exception {
        mockMvc.perform(get("/v1/reports/spending-by-category?limit=" + limit)).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest(name = "semente {0}")
    @ValueSource(longs = {1, 2, 3, 4})
    @DisplayName("T190: com dados aleatórios, o ponto de cada mês bate com dashboard, entries e resumo; o total por categoria bate com entries.totals.total e os share ficam na tolerância")
    void devePropriedadeComDadosAleatorios(long seed) throws Exception {
        Random random = new Random(seed);
        List<String> categories = new ArrayList<>(List.of(food, subscriptions, bills));
        String lastExpense = null;
        for (int i = 0; i < 40; i++) {
            LocalDate date = LocalDate.of(2026, 8, 1).plusDays(random.nextInt(150));
            String amount = (1 + random.nextInt(50_000)) / 100 + "." + String.format("%02d", random.nextInt(100));
            if (amount.startsWith("0.00")) {
                amount = "1.00";
            }
            int kind = random.nextInt(100);
            if (kind < 45) {
                lastExpense = created(expense(amount, date.toString(), "Gasto " + i, categories.get(random.nextInt(categories.size())))).get("id").asString();
            } else if (kind < 60) {
                created(incomeOf(amount, date.toString(), "Receita " + i));
            } else if (kind < 75) {
                created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Compra " + i + "\",\"cardId\":\""
                        + card + "\",\"categoryId\":\"" + categories.get(random.nextInt(categories.size())) + "\",\"installments\":" + (1 + random.nextInt(6)) + "}");
            } else if (kind < 85 && lastExpense != null) {
                JsonNode original = okBody(get("/v1/transactions/" + lastExpense));
                if (original.get("status").asString().equals("ACTIVE") && original.get("amount").get("amount").asString().compareTo("1.00") > 0) {
                    created("{\"type\":\"REFUND\",\"amount\":\"1.00\",\"date\":\"" + (date.isBefore(LocalDate.parse(original.get("date").asString()))
                            ? original.get("date").asString() : date.toString()) + "\",\"description\":\"Reembolso " + i + "\",\"accountId\":\"" + nubank
                            + "\",\"categoryId\":\"" + original.get("category").get("id").asString() + "\",\"refundOfTransactionId\":\"" + lastExpense + "\"}");
                    lastExpense = null;
                }
            } else {
                created("{\"type\":\"TRANSFER\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Transf\",\"fromAccountId\":\"" + nubank
                        + "\",\"toAccountId\":\"" + createAccountOnce() + "\"}");
            }
        }
        JsonNode m12 = series("M12");
        for (JsonNode point : m12.get("points")) {
            String month = point.get("periodStart").asString().substring(0, 7);
            String lastDay = YearMonth.parse(month).atEndOfMonth().toString();
            JsonNode dashboard = okBody(get("/v1/dashboard/summary?asOf=" + lastDay)).get("month");
            assertThat(amount(point.get("expenses"))).as(month + " consumo").isEqualTo(amount(dashboard.get("consumption")));
            assertThat(amount(point.get("income"))).as(month + " receita").isEqualTo(amount(dashboard.get("income")));
            JsonNode entries = okBody(get("/v1/entries?competence=" + month + "&lens=CONSUMPTION")).get("totals");
            assertThat(amount(point.get("expenses"))).as(month + " entries").isEqualTo(amount(entries.get("total")));
            JsonNode summary = okBody(get("/v1/summaries/" + month));
            assertThat(amount(point.get("expenses"))).isEqualTo(amount(summary.get("expenses")));
            assertThat(amount(point.get("income"))).isEqualTo(amount(summary.get("income")));
            JsonNode spending = okBody(get("/v1/reports/spending-by-category?month=" + month + "&limit=2"));
            assertThat(amount(spending.get("total"))).as(month + " total por categoria").isEqualTo(amount(entries.get("total")));
            long positives = 0;
            for (JsonNode byCategory : entries.get("byCategory")) {
                long cents = cents(byCategory.get("amount"));
                if (cents > 0) {
                    positives += cents;
                }
            }
            assertThat(cents(spending.get("base"))).as(month + " base").isEqualTo(positives);
            long shown = 0;
            double shares = 0;
            for (JsonNode item : spending.get("items")) {
                shown += cents(item.get("amount"));
                shares += Double.parseDouble(item.get("share").asString());
            }
            if (!spending.get("other").isNull()) {
                shown += cents(spending.get("other").get("amount"));
                shares += Double.parseDouble(spending.get("other").get("share").asString());
            }
            assertThat(shown).as(month + " itens + other = base").isEqualTo(positives);
            if (positives > 0) {
                assertThat(Math.abs(shares - 1.0)).as(month + " Σ share").isLessThanOrEqualTo((spending.get("items").size() + 1) * 0.00005 + 1e-9);
            }
        }
    }

    private String secondAccount;

    private String createAccountOnce() throws Exception {
        if (secondAccount == null) {
            secondAccount = createdBody(post("/v1/accounts", "{\"name\":\"Segunda\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"0.00\","
                    + "\"currency\":\"BRL\"},\"openingDate\":\"2026-08-01\"}")).get("id").asString();
        }
        return secondAccount;
    }

    private static long cents(JsonNode money) {
        return new java.math.BigDecimal(money.get("amount").asString()).movePointRight(2).longValueExact();
    }

    @Test
    @DisplayName("T184/T185: os dois relatórios leem o razão uma vez só, num instantâneo REPEATABLE_READ somente leitura (ADR-008)")
    void deveLerNumInstantaneoUnico() throws Exception {
        AtomicReference<Integer> isolation = new AtomicReference<>();
        AtomicReference<Boolean> readOnly = new AtomicReference<>();
        Mockito.clearInvocations(recordsGateway);
        Mockito.doAnswer(invocation -> {
            isolation.set(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.callRealMethod();
        }).when(recordsGateway).load(Mockito.any());

        series("D30");
        assertThat(isolation.get()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(readOnly.get()).isTrue();
        Mockito.verify(recordsGateway, Mockito.times(1)).load(userId);

        isolation.set(null);
        okBody(get("/v1/reports/spending-by-category"));
        assertThat(isolation.get()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        Mockito.verify(recordsGateway, Mockito.times(2)).load(userId);
    }
}
