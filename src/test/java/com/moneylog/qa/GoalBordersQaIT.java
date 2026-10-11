package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T98: bordas de metas, resumo mensal e projeções")
class GoalBordersQaIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager tm;
    @Autowired
    private MutableClock clock;

    private CardQaWorld world;
    private Actor a;
    private final LocalDate today = LocalDate.of(2026, 10, 10);

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("e5b");
    }

    private void noContractViolations() {
        assertThat(world.contractViolations).as("contrato").isEmpty();
    }

    private static String ratio(long n, long d) {
        return BigDecimal.valueOf(n).divide(BigDecimal.valueOf(d), 4, RoundingMode.HALF_UP).toPlainString();
    }

    private JsonNode goalWith(String target, String deadline, String reserved) throws Exception {
        String id = GoalQa.goal(a, "G " + UUID.randomUUID().toString().substring(0, 8), target, deadline);
        if (reserved != null) {
            CardQaWorld.ok(GoalQa.reserve(a, id, a.nubank, reserved, today));
        }
        return GoalQa.getGoal(a, id);
    }

    private static String money(JsonNode g, String field) {
        return g.get(field).isNull() ? null : g.get(field).get("amount").asString();
    }

    @Test
    @DisplayName("monthlyNeeded: prazo no mês corrente (1 mês), hoje (1 mês), ontem (null), arredondamento para cima, atingida (0,00), prazo longínquo e meta de 1 centavo")
    void monthlyNeeded() throws Exception {
        assertThat(money(goalWith("1000.00", "2026-10-31", null), "monthlyNeeded")).as("prazo no mês corrente").isEqualTo("1000.00");
        assertThat(money(goalWith("1000.00", "2026-10-10", null), "monthlyNeeded")).as("prazo hoje").isEqualTo("1000.00");
        assertThat(goalWith("1000.00", "2026-10-09", null).get("monthlyNeeded").isNull()).as("prazo ontem = vencido").isTrue();
        assertThat(goalWith("1000.00", null, null).get("monthlyNeeded").isNull()).as("sem prazo").isTrue();
        assertThat(money(goalWith("1000.00", "2026-12-31", null), "monthlyNeeded")).as("3 meses, 1000/3 para cima").isEqualTo("333.34");
        assertThat(money(goalWith("1000.00", "2027-01-31", null), "monthlyNeeded")).as("4 meses").isEqualTo("250.00");
        assertThat(money(goalWith("20000.00", "2027-12-31", "8500.00"), "monthlyNeeded")).as("exemplo do plano").isEqualTo("766.67");
        assertThat(money(goalWith("0.01", "2100-12-31", null), "monthlyNeeded")).as("1 centavo em 1.000 meses não vira zero").isEqualTo("0.01");
        JsonNode achievedExpired = goalWith("100.00", "2026-10-01", "100.00");
        assertThat(achievedExpired.get("status").asString()).isEqualTo("ACHIEVED");
        assertThat(money(achievedExpired, "monthlyNeeded")).as("atingida vale antes do prazo vencido").isEqualTo("0.00");
        JsonNode over = goalWith("100.00", null, "250.00");
        assertThat(over.get("progress").asString()).as("progress sem teto").isEqualTo("2.5000");
        assertThat(money(over, "remaining")).isEqualTo("0.00");
        assertThat(money(over, "monthlyNeeded")).isEqualTo("0.00");
        assertThat(over.get("status").asString()).isEqualTo("ACHIEVED");
        CardQaWorld.ok(GoalQa.reserve(a, over.get("id").asString(), a.nubank, "-200.00", today));
        JsonNode back = GoalQa.getGoal(a, over.get("id").asString());
        assertThat(back.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(back.get("progress").asString()).isEqualTo("0.5000");
        assertThat(goalWith("3.00", null, "1.00").get("progress").asString()).isEqualTo("0.3333");
        assertThat(goalWith("0.03", null, "0.02").get("progress").asString()).isEqualTo("0.6667");
        assertThat(goalWith("2000.00", null, "0.01").get("progress").asString()).isEqualTo(ratio(1, 200_000));
        noContractViolations();
    }

    @Test
    @DisplayName("teto S-78: alvo e reserva de 999.999.999,99 passam; 1.000.000.000,00 é 422; duas reservas no teto somam sem estourar (progress enorme continua no contrato)")
    void teto() throws Exception {
        JsonNode ok = GoalQa.createGoal(a, "Teto", "999999999.99", null).node();
        assertThat(ok.get("target").get("amount").asString()).isEqualTo("999999999.99");
        Res above = GoalQa.createGoal(a, "Acima", "1000000000.00", null);
        assertThat(above.status()).isEqualTo(422);
        assertThat(above.code()).isEqualTo("AMOUNT_ABOVE_LIMIT");
        Res huge = a.raw(HttpMethod.POST, "/v1/goals", GoalQa.goalBody("Enorme", "9999999999999.99", null, "LOW"), null);
        assertThat(huge.status()).as(huge.toString()).isIn(400, 422);
        String id = ok.get("id").asString();
        assertThat(GoalQa.reserve(a, id, a.nubank, "999999999.99", today).status()).isEqualTo(200);
        assertThat(GoalQa.reserve(a, id, a.nubank, "999999999.99", today).status()).as("a Σ passa do teto de um valor, mas cada reserva está no limite").isEqualTo(200);
        JsonNode g = GoalQa.getGoal(a, id);
        assertThat(g.get("current").get("amount").asString()).isEqualTo("1999999999.98");
        assertThat(GoalQa.reserve(a, id, a.nubank, "1000000000.00", today).code()).isEqualTo("AMOUNT_ABOVE_LIMIT");
        String tiny = GoalQa.goal(a, "Mínima", "0.01", null);
        CardQaWorld.ok(GoalQa.reserve(a, tiny, a.nubank, "999999999.99", today));
        assertThat(GoalQa.getGoal(a, tiny).get("progress").asString()).isEqualTo("99999999999.0000");
        a.getOk("/v1/goals");
        a.getOk("/v1/dashboard/summary");
        noContractViolations();
    }

    @Test
    @DisplayName("reserva igual ao saldo não é descoberta; 1 centavo a mais é; despesa depois torna descoberta; liberar volta; reserva futura só conta na data")
    void descobertaFutura() throws Exception {
        String goal = GoalQa.goal(a, "Tudo", "50000.00", null);
        CardQaWorld.ok(GoalQa.reserve(a, goal, a.nubank, "20000.00", today));
        assertThat(a.getOk("/v1/accounts/" + a.nubank).get("uncovered").asBoolean()).as("igual ao saldo").isFalse();
        assertThat(GoalQa.getGoal(a, goal).get("uncovered").asBoolean()).isFalse();
        CardQaWorld.ok(GoalQa.reserve(a, goal, a.nubank, "0.01", today));
        assertThat(a.getOk("/v1/accounts/" + a.nubank).get("uncovered").asBoolean()).as("1 centavo a mais").isTrue();
        assertThat(GoalQa.getGoal(a, goal).get("uncovered").asBoolean()).isTrue();
        JsonNode dash = a.getOk("/v1/dashboard/summary");
        assertThat(dash.get("balances").get("reserved").get("amount").asString()).isEqualTo("20000.01");
        assertThat(dash.get("balances").get("available").get("amount").asString()).isEqualTo("-0.01");
        CardQaWorld.ok(GoalQa.reserve(a, goal, a.nubank, "-0.01", today));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"1.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\""
                + a.nubank + "\",\"categoryId\":\"" + a.food + "\",\"method\":\"PIX\"}", GoalQa.key()));
        assertThat(a.getOk("/v1/accounts/" + a.nubank).get("uncovered").asBoolean()).as("despesa depois da reserva").isTrue();
        String future = GoalQa.goal(a, "Futuro", "1000.00", null);
        CardQaWorld.ok(GoalQa.reserve(a, future, a.nubank, "300.00", today.plusDays(5)));
        JsonNode g = GoalQa.getGoal(a, future);
        assertThat(g.get("reservations")).hasSize(1);
        assertThat(money(g, "current")).isEqualTo("0.00");
        assertThat(g.get("progress").asString()).isEqualTo("0.0000");
        JsonNode asOf = a.getOk("/v1/dashboard/summary?asOf=" + today.plusDays(5));
        assertThat(asOf.get("balances").get("reserved").get("amount").asString()).isEqualTo("20300.00");
        Res early = GoalQa.reserve(a, future, a.nubank, "-100.00", today);
        assertThat(early.status()).isEqualTo(422);
        assertThat(early.code()).isEqualTo("GOAL_RELEASE_EXCEEDS_RESERVED");
        assertThat(GoalQa.reserve(a, future, a.nubank, "-100.00", today.plusDays(5)).status()).isEqualTo(200);
        a.at(Instant.parse("2026-10-15T15:00:00Z"));
        assertThat(money(GoalQa.getGoal(a, future), "current")).isEqualTo("200.00");
        noContractViolations();
    }

    @Test
    @DisplayName("conta arquivada: aceita liberação, não reserva nova; meta arquivada sai do reservado e volta ao desarquivar; reserva em conta arquivada continua em reservedAmount e no dashboard")
    void arquivadas() throws Exception {
        String second = GoalQa.account(a, "Segunda", "CHECKING", "0.00");
        String goal = GoalQa.goal(a, "Arq", "5000.00", null);
        CardQaWorld.ok(GoalQa.reserve(a, goal, second, "100.00", today));
        CardQaWorld.ok(GoalQa.reserve(a, goal, a.nubank, "400.00", today));
        assertThat(a.getOk("/v1/dashboard/summary").get("balances").get("reserved").get("amount").asString()).isEqualTo("500.00");
        Res refused = a.call(HttpMethod.POST, "/v1/accounts/" + second + "/archive", null, null);
        assertThat(refused.status()).as(refused.toString()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("ACCOUNT_HAS_RESERVATIONS");
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/goals/" + goal + "/archive", null, null));
        Res archive = a.call(HttpMethod.POST, "/v1/accounts/" + second + "/archive", null, null);
        assertThat(archive.status()).as(archive.toString()).isEqualTo(200);
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/goals/" + goal + "/unarchive", null, null));
        Res nova = GoalQa.reserve(a, goal, second, "1.00", today);
        assertThat(nova.status()).isEqualTo(422);
        assertThat(nova.code()).isEqualTo("ACCOUNT_ARCHIVED");
        JsonNode account = a.getOk("/v1/accounts/" + second);
        JsonNode dash = a.getOk("/v1/dashboard/summary");
        System.out.println("QA-E5-ARCH conta arquivada reservedAmount=" + account.get("reservedAmount").get("amount").asString() + " uncovered=" + account.get("uncovered").asBoolean()
                + " dashboard.reserved=" + dash.get("balances").get("reserved").get("amount").asString() + " meta.current=" + GoalQa.getGoal(a, goal).get("current").get("amount").asString());
        assertThat(dash.get("balances").get("reserved").get("amount").asString())
                .as("dashboard.reserved = Σ reservedAmount das contas de caixa (inclusive a arquivada)").isEqualTo("500.00");
        assertThat(account.get("reservedAmount").get("amount").asString()).isEqualTo("100.00");
        assertThat(GoalQa.reserve(a, goal, second, "-100.00", today).status()).as("liberar em conta arquivada").isEqualTo(200);
        assertThat(a.getOk("/v1/dashboard/summary").get("balances").get("reserved").get("amount").asString()).isEqualTo("400.00");
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/goals/" + goal + "/archive", null, null));
        assertThat(a.getOk("/v1/dashboard/summary").get("balances").get("reserved").get("amount").asString()).isEqualTo("0.00");
        assertThat(a.getOk("/v1/accounts/" + a.nubank).get("reservedAmount").get("amount").asString()).isEqualTo("0.00");
        Res onArchived = GoalQa.reserve(a, goal, a.nubank, "1.00", today);
        assertThat(onArchived.code()).isEqualTo("GOAL_ARCHIVED");
        Res releaseOnArchived = GoalQa.reserve(a, goal, a.nubank, "-1.00", today);
        assertThat(releaseOnArchived.code()).as("liberar numa meta arquivada").isEqualTo("GOAL_ARCHIVED");
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/goals/" + goal + "/unarchive", null, null));
        assertThat(a.getOk("/v1/dashboard/summary").get("balances").get("reserved").get("amount").asString()).isEqualTo("400.00");
        noContractViolations();
    }

    @Test
    @DisplayName("a Idempotency-Key de uma reserva que falhou (404, 422) não fica queimada: a mesma chave com um corpo válido funciona")
    void chaveDeFalhaNaoQueima() throws Exception {
        String goal = GoalQa.goal(a, "Falha", "5000.00", null);
        String key = GoalQa.key();
        Res notFound = GoalQa.reserveKey(a, UUID.randomUUID().toString(), a.nubank, "10.00", today, key);
        assertThat(notFound.status()).isEqualTo(404);
        Res ok = GoalQa.reserveKey(a, goal, a.nubank, "10.00", today, key);
        assertThat(ok.status()).as("chave usada numa requisição que falhou com 404: " + ok).isEqualTo(200);
        String key2 = GoalQa.key();
        Res exceeds = GoalQa.reserveKey(a, goal, a.nubank, "-999.00", today, key2);
        assertThat(exceeds.status()).isEqualTo(422);
        Res ok2 = GoalQa.reserveKey(a, goal, a.nubank, "20.00", today, key2);
        assertThat(ok2.status()).as("chave usada numa requisição que falhou com 422: " + ok2).isEqualTo(200);
        String key3 = GoalQa.key();
        assertThat(GoalQa.reserveKey(a, goal, a.nubank, "0.00", today, key3).status()).isEqualTo(422);
        assertThat(GoalQa.reserveKey(a, goal, a.nubank, "5.00", today, key3).status()).isEqualTo(200);
        assertThat(money(GoalQa.getGoal(a, goal), "current")).isEqualTo("35.00");
        noContractViolations();
    }

    @Test
    @DisplayName("fuso America/Sao_Paulo: às 23:30 de 31/12 em SP ainda é 2026 (prazo 31/12 vale 1 mês); 00:30 de 1/1 já é 2027 (prazo vencido) e a reserva datada 1/1 passa a contar")
    void fuso() throws Exception {
        a.at(Instant.parse("2027-01-01T02:30:00Z"));
        LocalDate d = LocalDate.of(2026, 12, 31);
        String goal = GoalQa.goal(a, "Fuso", "1000.00", "2026-12-31");
        CardQaWorld.ok(GoalQa.reserve(a, goal, a.nubank, "10.00", d.plusDays(1)));
        JsonNode g = GoalQa.getGoal(a, goal);
        assertThat(money(g, "monthlyNeeded")).as("23:30 em SP de 31/12 ainda é 2026").isEqualTo("1000.00");
        assertThat(money(g, "current")).as("a reserva de 1/1 é futura às 23:30 de 31/12").isEqualTo("0.00");
        assertThat(a.getOk("/v1/dashboard/summary").get("asOf").asString()).isEqualTo("2026-12-31");
        a.at(Instant.parse("2027-01-01T03:30:00Z"));
        JsonNode g2 = GoalQa.getGoal(a, goal);
        assertThat(g2.get("monthlyNeeded").isNull()).as("00:30 de 1/1 em SP: prazo vencido").isTrue();
        assertThat(money(g2, "current")).isEqualTo("10.00");
        assertThat(a.getOk("/v1/summaries/2027-01").get("partial").asBoolean()).isTrue();
        assertThat(a.getOk("/v1/summaries/2026-12").get("partial").asBoolean()).isFalse();
        noContractViolations();
    }

    private String expense(String amount, String date, String description) throws Exception {
        return CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\""
                + description + "\",\"accountId\":\"" + a.nubank + "\",\"categoryId\":\"" + a.food + "\",\"method\":\"PIX\"}", GoalQa.key())).node().get("id").asString();
    }

    @Test
    @DisplayName("resumo: virada de ano (31/12 e 1/1), fevereiro bissexto (29/02/2028) e comum (28/02/2027), previousMonth atravessa o ano, mês 2000-01 e 2100-12")
    void resumoViradaEFevereiro() throws Exception {
        a.at(Instant.parse("2028-05-10T15:00:00Z"));
        expense("10.00", "2026-12-31", "Fim de ano");
        expense("20.00", "2027-01-01", "Ano novo");
        expense("30.00", "2027-02-28", "Fev comum");
        expense("40.00", "2027-03-01", "Mar");
        expense("50.00", "2028-02-29", "Bissexto");
        expense("60.00", "2028-03-01", "Mar 28");
        assertThat(a.getOk("/v1/summaries/2026-12").get("expenses").get("amount").asString()).isEqualTo("10.00");
        JsonNode jan = a.getOk("/v1/summaries/2027-01");
        assertThat(jan.get("expenses").get("amount").asString()).isEqualTo("20.00");
        assertThat(jan.get("comparison").get("previousMonth").get("competence").asString()).isEqualTo("2026-12");
        assertThat(jan.get("comparison").get("previousMonth").get("expenses").get("amount").asString()).isEqualTo("10.00");
        assertThat(jan.get("comparison").get("expensesDelta").get("amount").get("amount").asString()).isEqualTo("10.00");
        assertThat(jan.get("comparison").get("expensesDelta").get("percent").asString()).isEqualTo("1.0000");
        assertThat(a.getOk("/v1/summaries/2027-02").get("expenses").get("amount").asString()).isEqualTo("30.00");
        assertThat(a.getOk("/v1/summaries/2027-03").get("expenses").get("amount").asString()).isEqualTo("40.00");
        assertThat(a.getOk("/v1/summaries/2028-02").get("expenses").get("amount").asString()).isEqualTo("50.00");
        assertThat(a.getOk("/v1/summaries/2028-03").get("expenses").get("amount").asString()).isEqualTo("60.00");
        assertThat(a.getOk("/v1/summaries/2028-03").get("comparison").get("previousMonth").get("expenses").get("amount").asString()).isEqualTo("50.00");
        JsonNode feb27 = a.getOk("/v1/summaries/2027-02");
        assertThat(feb27.get("comparison").get("incomeDelta").get("percent").isNull()).isTrue();
        assertThat(feb27.get("comparison").get("investmentsDelta").get("percent").isNull()).isTrue();
        assertThat(feb27.get("comparison").get("incomeDelta").get("amount").get("amount").asString()).isEqualTo("0.00");
        JsonNode first = a.getOk("/v1/summaries/2000-01");
        assertThat(first.get("comparison").get("previousMonth").get("competence").asString()).isEqualTo("1999-12");
        JsonNode last = a.getOk("/v1/summaries/2100-12");
        assertThat(last.get("isProjection").asBoolean()).isTrue();
        assertThat(last.get("partial").asBoolean()).isTrue();
        assertThat(last.get("expenses").get("amount").asString()).isEqualTo("0.00");
        noContractViolations();
    }

    @Test
    @DisplayName("mês inexistente ou fora do formato: 400 SUMMARY_MONTH_INVALID em todos; horizonte inválido: 400; includeTrend fora de true/false (documenta a leniência do Spring)")
    void entradasInvalidas() throws Exception {
        for (String month : List.of("2026-13", "2026-00", "1999-12", "2101-01", "2026-1", "2026-10-01", "abc", "202610", "2026-1a", "0000-01", "9999-99", "-001-01",
                "+2026-10", "2026-010", "20261-0")) {
            Res r = a.raw(HttpMethod.GET, "/v1/summaries/" + month, null, null);
            assertThat(r.status()).as(month + " -> " + r).isEqualTo(400);
            assertThat(r.code()).as(month).isEqualTo("SUMMARY_MONTH_INVALID");
        }
        for (String raw : List.of("%D9%A2%D9%A0%D9%A2%D9%A6-%D9%A1%D9%A0", "2026-10%00", "2026%2F10")) {
            var rejected = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/summaries/" + raw)
                    .header(org.springframework.http.HttpHeaders.AUTHORIZATION, a.bearer)).andReturn().getResponse();
            assertThat(rejected.getStatus()).as(raw).isEqualTo(400);
            assertThat(rejected.getContentType()).as(raw).contains("application/problem+json");
            JsonNode problem = new tools.jackson.databind.ObjectMapper().readTree(rejected.getContentAsString());
            assertThat(problem.get("code").asString()).as(raw).isEqualTo("SUMMARY_MONTH_INVALID");
            assertThat(problem.get("status").asInt()).isEqualTo(400);
            assertThat(problem.get("traceId").asString()).isNotBlank().isEqualTo(rejected.getHeader("X-Trace-Id"));
            assertThat(rejected.getContentAsString()).as("não ecoa o caminho recusado").doesNotContain("%").doesNotContain(raw);
        }
        for (String path : List.of("/v1/summaries/", "/v1/summaries", "/v1/summaries/2026-10/", "/v1/summaries/2026-10/x")) {
            var r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .header(org.springframework.http.HttpHeaders.AUTHORIZATION, a.bearer)).andReturn().getResponse();
            assertThat(r.getStatus()).as(path).isBetween(400, 499);
        }
        for (String h : List.of("D7", "d30", "", "M18", "D30;D90", "%00", "30", "M6%20", "NULL", "M6,M12")) {
            Res r = a.raw(HttpMethod.GET, "/v1/projections?horizon=" + h, null, null);
            System.out.println("QA-E5-HORIZON '" + h + "' -> " + r.status() + " " + r.code());
            assertThat(r.status()).as("horizonte '" + h + "' -> " + r).isBetween(h.isEmpty() ? 200 : 400, h.isEmpty() ? 200 : 499);
        }
        for (String t : List.of("yes", "1", "on", "TRUE", "True", "no", "0", "off", "sim", "")) {
            Res r = a.raw(HttpMethod.GET, "/v1/projections?horizon=M6&includeTrend=" + t, null, null);
            System.out.println("QA-E5-TREND includeTrend='" + t + "' -> " + r.status() + " " + r.code());
            assertThat(r.status()).as("includeTrend=" + t).isLessThan(500);
        }
        assertThat(a.raw(HttpMethod.GET, "/v1/projections?horizon=M6&horizon=M12", null, null).status()).isLessThan(500);
        assertThat(a.raw(HttpMethod.GET, "/v1/projections?horizon=M6&foo=bar", null, null).status()).isLessThan(500);
        noContractViolations();
    }

    private void assertProjectionConsistent(LocalDate now, String tag) throws Exception {
        JsonNode summary = a.getOk("/v1/dashboard/summary");
        assertThat(summary.get("asOf").asString()).as(tag).isEqualTo(now.toString());
        long inAccounts = CardQaWorld.cents(summary.get("balances").get("inAccounts"));
        for (String h : List.of("D30", "D90", "M6", "M12")) {
            LocalDate end = switch (h) {
                case "D30" -> now.plusDays(30);
                case "D90" -> now.plusDays(90);
                case "M6" -> now.plusMonths(6);
                default -> now.plusMonths(12);
            };
            JsonNode p = a.getOk("/v1/projections?horizon=" + h);
            JsonNode series = p.get("series");
            java.time.YearMonth month = java.time.YearMonth.from(now);
            long running = inAccounts;
            for (int i = 0; i < series.size(); i++) {
                assertThat(series.get(i).get("competence").asString()).as(tag + " " + h + " ponto " + i).isEqualTo(month.plusMonths(i).toString());
                running += CardQaWorld.cents(series.get(i).get("inflows")) - CardQaWorld.cents(series.get(i).get("outflows"));
                assertThat(CardQaWorld.cents(series.get(i).get("balance"))).as(tag + " " + h + " saldo do ponto " + i).isEqualTo(running);
            }
            assertThat(series.size()).as(tag + " " + h + " pontos").isEqualTo((int) java.time.temporal.ChronoUnit.MONTHS.between(month, java.time.YearMonth.from(end)) + 1);
            assertThat(series.get(series.size() - 1).get("periodEnd").asString()).as(tag + " " + h + " fim").isEqualTo(end.toString());
            assertThat(series.get(series.size() - 1).get("balance").get("amount").asString()).as(tag + " " + h + " = projected").isEqualTo(
                    summary.get("projected").get(h.toLowerCase()).get("amount").asString());
            if (end.isAfter(LocalDate.of(2100, 12, 31))) {
                continue;
            }
            JsonNode up = a.getOk("/v1/dashboard/upcoming?from=" + now + "&to=" + end);
            assertThat(inAccounts + CardQaWorld.cents(up.get("totals").get("in")) - CardQaWorld.cents(up.get("totals").get("out")))
                    .as(tag + " " + h + " = em contas + /upcoming").isEqualTo(CardQaWorld.cents(series.get(series.size() - 1).get("balance")));
        }
    }

    @Test
    @DisplayName("projeção em 31/08, 31/01 de ano bissexto, 29/02, 31/12 e 30/11: a série mensal, o fim do horizonte e o saldo final batem com o dashboard e com /upcoming (recorrência mensal no dia 31, fatura e parcelas)")
    void projecaoEmDatasDificeis() throws Exception {
        String rec = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "100.00", "Dia 31", a.food, DebtQa.accountTarget(a, "EXPENSE"),
                "MONTHLY", "2026-10-31", null), null)).node().get("id").asString();
        assertThat(rec).isNotBlank();
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("INCOME", "3000.00", "Salário dia 29", a.income, DebtQa.accountTarget(a, "INCOME"),
                "MONTHLY", "2026-10-29", null), null));
        CardQaWorld.ok(a.purchase("1200.00", 6, "2026-10-10", "Notebook"));
        for (String iso : List.of("2026-10-10T15:00:00Z", "2026-11-30T15:00:00Z", "2026-12-31T15:00:00Z", "2027-08-31T15:00:00Z", "2028-01-31T15:00:00Z",
                "2028-02-29T15:00:00Z", "2028-12-31T15:00:00Z", "2100-01-31T15:00:00Z")) {
            a.at(Instant.parse(iso));
            LocalDate now = Instant.parse(iso).atZone(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDate();
            assertProjectionConsistent(now, iso);
        }
        a.at(Instant.parse("2026-10-10T15:00:00Z"));
        JsonNode p = a.getOk("/v1/projections?horizon=M12");
        long outTotal = 0;
        for (JsonNode pt : p.get("series")) {
            outTotal += CardQaWorld.cents(pt.get("outflows"));
        }
        assertThat(outTotal).as("Σ saídas = 12 ocorrências de 100,00 + 6 parcelas de 200,00").isEqualTo(12 * 10_000 + 120_000);
        noContractViolations();
    }

    private long trendOracle(Actor who, String... months) {
        String in = String.join("','", months);
        Long v = jdbc.queryForObject("select coalesce(sum(lp.amount_cents),0) from ledger_postings lp join transactions t on t.id = lp.transaction_id "
                + "left join transaction_installments ti on ti.id = lp.installment_id where lp.user_id = ? and lp.active and lp.book_type = 'EXPENSE' "
                + "and lp.competence in ('" + in + "') and t.source <> 'RECURRENCE' and t.type in ('EXPENSE','CARD_PURCHASE') and (ti.id is null or ti.number = 1)",
                Long.class, who.id);
        return v == null ? 0 : v;
    }

    @Test
    @DisplayName("tendência: sem histórico e com menos de 3 meses completos = INSUFFICIENT_HISTORY (sem inventar média); com 3 meses = média de consumo novo (sem parcelas 2..N, recorrência nem reembolso); série base idêntica")
    void tendencia() throws Exception {
        JsonNode empty = a.getOk("/v1/projections?horizon=M6&includeTrend=true");
        assertThat(empty.get("trend").get("available").asBoolean()).isFalse();
        assertThat(empty.get("trend").get("reason").asString()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(empty.get("trend").get("estimate").asBoolean()).isTrue();
        Actor late = world.new Actor("e5late");
        String hist = GoalQa.account(late, "Hist", "CHECKING", "5000.00", "2026-06-01");
        expenseOn(late, hist, "100.00", "2026-07-02", "Julho");
        expenseOn(late, hist, "100.00", "2026-08-02", "Agosto");
        expenseOn(late, hist, "100.00", "2026-09-02", "Setembro");
        JsonNode partial = late.getOk("/v1/projections?horizon=M6&includeTrend=true");
        assertThat(partial.get("trend").get("available").asBoolean()).as("1ª movimentação em 02/07 (jul parcial)").isFalse();
        Actor full = world.new Actor("e5full");
        String hist2 = GoalQa.account(full, "Hist", "CHECKING", "5000.00", "2026-06-01");
        expenseOn(full, hist2, "300.00", "2026-07-01", "Julho");
        expenseOn(full, hist2, "600.00", "2026-08-15", "Agosto");
        expenseOn(full, hist2, "900.00", "2026-09-30", "Setembro");
        expenseOn(full, hist2, "7777.00", "2026-10-02", "Mês corrente fora da média");
        CardQaWorld.ok(full.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "500.00", "Fixa", full.food, "\"accountId\":\"" + hist2 + "\",\"method\":\"PIX\"",
                "MONTHLY", "2026-08-20", null), null));
        String recId = DebtQa.all(full, "/v1/recurrences").get(0).get("id").asString();
        CardQaWorld.ok(DebtQa.realize(full, recId, "2026-09-20", null));
        CardQaWorld.ok(full.purchase("150.00", 1, "2026-08-10", "À vista"));
        CardQaWorld.ok(full.purchase("300.00", 3, "2026-07-10", "Parcelada"));
        Res refund = full.cardRefund("20.00", "2026-09-05", null);
        long expected = trendOracle(full, "2026-07", "2026-08", "2026-09");
        JsonNode t = full.getOk("/v1/projections?horizon=M12&includeTrend=true");
        JsonNode base = full.getOk("/v1/projections?horizon=M12");
        assertThat(t.get("series")).as("série base idêntica").isEqualTo(base.get("series"));
        JsonNode trend = t.get("trend");
        assertThat(trend.get("available").asBoolean()).isTrue();
        long avg = BigDecimal.valueOf(expected).divide(BigDecimal.valueOf(3), 0, RoundingMode.HALF_UP).longValueExact();
        System.out.println("QA-E5-TREND esperado Σ=" + expected + " média=" + avg + " obtido=" + trend.get("monthlyAverage").get("amount").asString() + " reembolso=" + refund.status());
        assertThat(CardQaWorld.cents(trend.get("monthlyAverage"))).as("média do consumo novo dos 3 meses completos").isEqualTo(avg);
        assertThat(trend.get("basedOn")).hasSize(3);
        assertThat(trend.get("basedOn").get(0).asString()).isEqualTo("2026-07");
        assertThat(trend.get("series")).hasSize(base.get("series").size());
        assertThat(trend.get("series").get(0).get("estimatedSpending").get("amount").asString()).as("o mês corrente conta 0").isEqualTo("0.00");
        assertThat(expected).isGreaterThanOrEqualTo(180_000);
        noContractViolations();
    }

    private void expenseOn(Actor who, String account, String amount, String date, String description) throws Exception {
        CardQaWorld.ok(who.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description
                + "\",\"accountId\":\"" + account + "\",\"categoryId\":\"" + who.food + "\",\"method\":\"PIX\"}", GoalQa.key()));
    }

    @Test
    @DisplayName("BUG-QA-28: uma saída já lançada para daqui a 5 dias tem de aparecer em projected.d30 (hoje não aparece nem no caixa nem nos compromissos)")
    void movimentacaoFuturaNaProjecao() throws Exception {
        long before = CardQaWorld.cents(a.getOk("/v1/dashboard/summary").get("projected").get("d30"));
        expense("1000.00", "2026-10-15", "Já lançada para 15/10");
        JsonNode s = a.getOk("/v1/dashboard/summary");
        long after = CardQaWorld.cents(s.get("projected").get("d30"));
        System.out.println("QA-E5-FUTURE projected.d30 antes=" + before + " depois=" + after);
        assertThat(after).as("projected.d30 deve refletir a saída de 1.000,00 que cai dentro dos 30 dias").isEqualTo(before - 100_000);
        JsonNode p = a.getOk("/v1/projections?horizon=D30");
        assertThat(CardQaWorld.cents(p.get("series").get(p.get("series").size() - 1).get("balance"))).isEqualTo(before - 100_000);
    }

    @Test
    @DisplayName("BUG-QA-28: realizar uma ocorrência adiantada não pode aumentar o projetado nem o disponível")
    void realizarOcorrenciaFuturaNaoAumentaOProjetado() throws Exception {
        String rec = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "119.00", "Internet", a.food, DebtQa.accountTarget(a, "EXPENSE"),
                "MONTHLY", "2026-10-15", null), null)).node().get("id").asString();
        JsonNode before = a.getOk("/v1/dashboard/summary");
        CardQaWorld.ok(DebtQa.realize(a, rec, "2026-10-15", null));
        JsonNode after = a.getOk("/v1/dashboard/summary");
        assertThat(after.get("projected").get("d30").get("amount").asString()).isEqualTo(before.get("projected").get("d30").get("amount").asString());
        assertThat(after.get("balances").get("available").get("amount").asString()).isEqualTo(before.get("balances").get("available").get("amount").asString());
    }
}
