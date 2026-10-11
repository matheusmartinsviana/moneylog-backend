package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

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
@DisplayName("QA T82: bordas de dívidas, divisão e recorrências")
class DebtBordersQaIT {

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
    private String joao;
    private String maria;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("e4b");
        joao = DebtQa.person(a, "João");
        maria = DebtQa.person(a, "Maria");
    }

    private static String cash(Res r) {
        return r.node().get("impact").get("cash").get("amount").asString();
    }

    private static String consumption(Res r) {
        JsonNode c = r.node().get("impact").get("consumption");
        return c.size() == 0 ? "0.00" : c.get(0).get("amount").get("amount").asString();
    }

    private String recurrence(String type, String amount, String freq, String start, String extra) throws Exception {
        return recurrenceRes(type, amount, freq, start, extra).node().get("id").asString();
    }

    private Res recurrenceRes(String type, String amount, String freq, String start, String extra) throws Exception {
        String target = "CARD_PURCHASE".equals(type) ? "\"cardId\":\"" + a.card + "\"" : DebtQa.accountTarget(a, type);
        return CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody(type, amount, "Rec", "INCOME".equals(type) ? a.income : a.food, target, freq, start, extra), null));
    }

    private List<String> dates(String recurrenceId, String from, String to, String status) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode o : DebtQa.all(a, "/v1/recurrences/occurrences?from=" + from + "&to=" + to + (status == null ? "" : "&status=" + status))) {
            if (o.get("recurrenceId").asString().equals(recurrenceId)) {
                out.add(o.get("date").asString());
            }
        }
        return out;
    }

    @Test
    @DisplayName("PR 01 casos 6, 7 e 8: empréstimo 300 (caixa −300, consumo 0, patrimônio 0), recebimento 300 (caixa +300, sem renda), dívida sem movimentação 200 (caixa 0, consumo 0, patrimônio −200)")
    void casos6e7e8() throws Exception {
        Res loan = CardQaWorld.ok(DebtQa.loan(a, "300.00", "2026-10-10", joao, "2026-11-05"));
        assertThat(cash(loan)).isEqualTo("-300.00");
        assertThat(loan.node().get("impact").get("consumption")).isEmpty();
        assertThat(loan.node().get("impact").get("netWorth").get("amount").asString()).isEqualTo("0.00");
        JsonNode s = a.getOk("/v1/dashboard/summary");
        assertThat(s.get("month").get("consumption").get("amount").asString()).isEqualTo("0.00");
        assertThat(s.get("month").get("income").get("amount").asString()).isEqualTo("0.00");
        assertThat(s.get("netWorth").get("receivables").get("amount").asString()).isEqualTo("300.00");
        assertThat(s.get("netWorth").get("total").get("amount").asString()).isEqualTo("20000.00");
        Res back = CardQaWorld.ok(DebtQa.received(a, "300.00", "2026-10-10", loan.node().get("debt").get("id").asString()));
        assertThat(cash(back)).isEqualTo("300.00");
        s = a.getOk("/v1/dashboard/summary");
        assertThat(s.get("month").get("income").get("amount").asString()).isEqualTo("0.00");
        assertThat(s.get("month").get("consumption").get("amount").asString()).isEqualTo("0.00");
        assertThat(s.get("netWorth").get("receivables").get("amount").asString()).isEqualTo("0.00");
        assertThat(a.getOk("/v1/accounts/" + a.nubank).get("currentBalance").get("amount").asString()).isEqualTo("20000.00");

        Res plain = CardQaWorld.ok(DebtQa.plainDebt(a, maria, "PAYABLE", "200.00", "2026-10-10", "2026-11-05", null));
        s = a.getOk("/v1/dashboard/summary");
        assertThat(a.getOk("/v1/accounts/" + a.nubank).get("currentBalance").get("amount").asString()).isEqualTo("20000.00");
        assertThat(s.get("month").get("consumption").get("amount").asString()).isEqualTo("0.00");
        assertThat(s.get("netWorth").get("total").get("amount").asString()).isEqualTo("19800.00");
        assertThat(a.getOk("/v1/dashboard/upcoming?from=2026-10-10&to=2026-11-10").get("items").get(0).get("kind").asString()).isEqualTo("DEBT_PAYABLE");
        assertThat(plain.node().get("outstanding").get("amount").asString()).isEqualTo("200.00");
        assertThat(a.ledgerSum()).isZero();
    }

    @Test
    @DisplayName("100,00 em 3 partes: 33,33 x 3 sobra 0,01 de consumo; 33,34 + 33,33 + 33,33 = 100,00 é recusado; parte única de 99,99 passa e 100,00 não")
    void cemEmTres() throws Exception {
        Res ok = CardQaWorld.ok(DebtQa.sharedExpense(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("33.33", joao), DebtQa.shareJson("33.33", maria), DebtQa.shareJson("33.33", joao))));
        assertThat(cash(ok)).isEqualTo("-100.00");
        assertThat(consumption(ok)).isEqualTo("0.01");
        assertThat(ok.node().get("shares")).hasSize(3);
        assertThat(a.ledgerSum()).isZero();
        assertThat(DebtQa.sharedExpense(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("33.34", joao), DebtQa.shareJson("33.33", maria), DebtQa.shareJson("33.33", joao))).code())
                .isEqualTo("SHARES_EXCEED_AMOUNT");
        assertThat(DebtQa.sharedExpense(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("100.00", joao))).code()).isEqualTo("SHARES_EXCEED_AMOUNT");
        Res single = CardQaWorld.ok(DebtQa.sharedExpense(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("99.99", joao))));
        assertThat(consumption(single)).isEqualTo("0.01");
        JsonNode entries = a.getOk("/v1/entries?competence=2026-10");
        assertThat(entries.get("totals").get("total").get("amount").asString()).isEqualTo("0.02");
        assertThat(a.getOk("/v1/dashboard/summary").get("month").get("consumption").get("amount").asString()).isEqualTo("0.02");
    }

    @Test
    @DisplayName("1 a 20 partes com centavos que sobram: 20 partes de 0,01 em 0,21; 21 partes é recusada (400 ou 422, nunca 5xx); nenhuma parte zero")
    void ateVinteDaPartes() throws Exception {
        for (int n : new int[] {1, 2, 3, 7, 19, 20}) {
            List<String> shares = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                shares.add(DebtQa.shareJson("0.01", i % 2 == 0 ? joao : maria));
            }
            Res r = CardQaWorld.ok(DebtQa.sharedExpense(a, CardQaWorld.money(n + 1), "2026-10-10", shares));
            assertThat(r.node().get("shares")).as("n=" + n).hasSize(n);
            assertThat(consumption(r)).isEqualTo("0.0" + 1);
        }
        List<String> shares = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            shares.add(DebtQa.shareJson("0.01", joao));
        }
        Res r = DebtQa.sharedExpense(a, "5.00", "2026-10-10", shares);
        assertThat(r.status()).as(r.toString()).isIn(400, 422);
        assertThat(DebtQa.sharedExpense(a, "5.00", "2026-10-10", List.of(DebtQa.shareJson("0.00", joao), DebtQa.shareJson("1.00", maria))).code()).isEqualTo("SHARES_EXCEED_AMOUNT");
        assertThat(a.ledgerSum()).isZero();
        long debts = a.count("debts");
        assertThat(debts).isEqualTo(1 + 2 + 3 + 7 + 19 + 20);
    }

    @Test
    @DisplayName("reembolso da despesa dividida: o teto é a parte do usuário (200 com 100 do João: 100,00 passa, 100,01 não), consumo nunca negativo, estorno da despesa com reembolso é recusado")
    void reembolsoDaDividida() throws Exception {
        Res tx = CardQaWorld.ok(DebtQa.sharedExpense(a, "200.00", "2026-10-10", List.of(DebtQa.shareJson("100.00", joao))));
        String id = tx.node().get("id").asString();
        assertThat(DebtQa.accountRefund(a, "100.01", "2026-10-10", a.food, id).code()).isEqualTo("REFUND_EXCEEDS_ORIGINAL");
        Res refund = CardQaWorld.ok(DebtQa.accountRefund(a, "100.00", "2026-10-10", a.food, id));
        assertThat(cash(refund)).isEqualTo("100.00");
        assertThat(a.getOk("/v1/entries?competence=2026-10").get("totals").get("total").get("amount").asString()).isEqualTo("0.00");
        assertThat(a.getOk("/v1/dashboard/summary").get("month").get("consumption").get("amount").asString()).isEqualTo("0.00");
        Res voidTx = a.voidTx(id);
        assertThat(voidTx.status()).as(voidTx.toString()).isEqualTo(422);
        assertThat(a.ledgerSum()).isZero();
        String share = tx.node().get("shares").get(0).get("id").asString();
        CardQaWorld.ok(DebtQa.received(a, "100.00", "2026-10-10", share));
        assertThat(DebtQa.accountRefund(a, "0.01", "2026-10-10", a.food, id).code()).isEqualTo("REFUND_EXCEEDS_ORIGINAL");
        Res tx2 = CardQaWorld.ok(DebtQa.sharedExpense(a, "200.00", "2026-10-11", List.of(DebtQa.shareJson("100.00", joao))));
        CardQaWorld.ok(DebtQa.cancelDebt(a, tx2.node().get("shares").get(0).get("id").asString()));
        assertThat(DebtQa.accountRefund(a, "100.01", "2026-10-11", a.food, tx2.node().get("id").asString()).code()).isEqualTo("REFUND_EXCEEDS_ORIGINAL");
        assertThat(a.getOk("/v1/entries?competence=2026-10").get("totals").get("total").get("amount").asString()).isEqualTo("100.00");
        assertThat(a.ledgerSum()).isZero();
    }

    @Test
    @DisplayName("edição da despesa dividida: valor/divisão = 422 TRANSACTION_NOT_EDITABLE; descrição e categoria seguem a Etapa 2 sem quebrar o razão nem as dívidas")
    void edicaoDaDividida() throws Exception {
        Res tx = CardQaWorld.ok(DebtQa.sharedExpense(a, "200.00", "2026-10-10", List.of(DebtQa.shareJson("100.00", joao))));
        String id = tx.node().get("id").asString();
        int version = tx.node().get("version").asInt();
        String other = a.category("Lazer", com.moneylog.core.category.domain.CategoryKind.EXPENSE);
        String put = "{\"type\":\"EXPENSE\",\"amount\":\"%s\",\"date\":\"2026-10-10\",\"description\":\"%s\",\"accountId\":\"" + a.nubank + "\",\"categoryId\":\"%s\",\"method\":\"PIX\",\"version\":%d%s}";
        Res amount = a.call(HttpMethod.PUT, "/v1/transactions/" + id, String.format(put, "300.00", "Jantar", a.food, version, ""), null);
        assertThat(amount.status()).as(amount.toString()).isEqualTo(422);
        assertThat(amount.code()).isEqualTo("TRANSACTION_NOT_EDITABLE");
        Res withShares = a.call(HttpMethod.PUT, "/v1/transactions/" + id, String.format(put, "200.00", "Jantar", a.food, version, ",\"shares\":[" + DebtQa.shareJson("10.00", joao) + "]"), null);
        assertThat(withShares.status()).as(withShares.toString()).isIn(400, 422);
        Res text = a.call(HttpMethod.PUT, "/v1/transactions/" + id, String.format(put, "200.00", "Novo texto", other, version, ""), null);
        assertThat(text.status()).as(text.toString()).isEqualTo(200);
        assertThat(consumption(text)).isEqualTo("100.00");
        assertThat(text.node().get("shares")).hasSize(1);
        assertThat(a.ledgerSum()).isZero();
        assertThat(a.unbalancedGroups()).isZero();
        JsonNode e = a.getOk("/v1/entries?competence=2026-10");
        assertThat(e.get("totals").get("total").get("amount").asString()).isEqualTo("100.00");
        assertThat(e.get("totals").get("byCategory")).hasSize(1);
        assertThat(e.get("totals").get("byCategory").get(0).get("category").get("name").asString()).isEqualTo("Lazer");
        String debt = tx.node().get("shares").get(0).get("id").asString();
        assertThat(a.getOk("/v1/debts/" + debt).get("outstanding").get("amount").asString()).isEqualTo("100.00");
    }

    @Test
    @DisplayName("teto S-78: 999.999.999,99 passa (empréstimo, dívida, divisão, recorrência, realize) e 1.000.000.000,00 é 422; soma de partes no limite não estoura")
    void tetoS78() throws Exception {
        String max = "999999999.99";
        String over = "1000000000.00";
        CardQaWorld.ok(DebtQa.loan(a, max, "2026-10-10", joao, null));
        assertThat(DebtQa.loan(a, over, "2026-10-10", joao, null).status()).isEqualTo(422);
        CardQaWorld.ok(DebtQa.plainDebt(a, joao, "PAYABLE", max, "2026-10-10", null, a.food));
        assertThat(DebtQa.plainDebt(a, joao, "PAYABLE", over, "2026-10-10", null, null).status()).isEqualTo(422);
        List<String> shares = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            shares.add(DebtQa.shareJson(max, joao));
        }
        Res big = DebtQa.sharedExpense(a, max, "2026-10-10", shares);
        assertThat(big.status()).as(big.toString()).isEqualTo(422);
        CardQaWorld.ok(DebtQa.sharedExpense(a, max, "2026-10-10", List.of(DebtQa.shareJson("999999999.98", maria))));
        assertThat(DebtQa.sharedExpense(a, over, "2026-10-10", List.of(DebtQa.shareJson("1.00", maria))).status()).isEqualTo(422);
        String debt = jdbc.queryForObject("select id::text from debts where user_id = ? and direction = 'RECEIVABLE' and principal_cents = 99999999999 limit 1", String.class, a.id);
        CardQaWorld.ok(DebtQa.received(a, max, "2026-10-10", debt));
        assertThat(a.getOk("/v1/debts/" + debt).get("status").asString()).isEqualTo("SETTLED");
        String rec = recurrence("EXPENSE", max, "MONTHLY", "2026-10-10", null);
        assertThat(a.raw(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", over, "x", a.food, DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", "2026-10-10", null), null).status())
                .isEqualTo(422);
        assertThat(DebtQa.realize(a, rec, "2026-10-10", over).status()).isEqualTo(422);
        assertThat(DebtQa.realize(a, rec, "2026-10-10", "0.00").status()).isIn(400, 422);
        assertThat(DebtQa.realize(a, rec, "2026-10-10", max).status()).isEqualTo(201);
        JsonNode s = a.getOk("/v1/dashboard/summary");
        assertThat(s.get("netWorth").get("total").get("amount").asString()).isNotEmpty();
        assertThat(a.ledgerSum()).isZero();
        assertThat(a.unbalancedGroups()).isZero();
        long sumConsumption = CardQaWorld.cents(a.getOk("/v1/entries?competence=2026-10").get("totals").get("total"));
        assertThat(CardQaWorld.cents(s.get("month").get("consumption"))).isEqualTo(sumConsumption);
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("dia 31, fevereiro e anual 29/02: 31/01, 28/02, 31/03, 30/04, 31/05; bissexto 29/02/2028; anual 29/02 vira 28/02 e volta a 29/02 em 2032")
    void datasDeRecorrencia() throws Exception {
        String monthly31 = recurrence("EXPENSE", "10.00", "MONTHLY", "2026-01-31", "\"maxOccurrences\":5");
        assertThat(dates(monthly31, "2026-01-01", "2026-12-31", "PENDING")).containsExactly("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31");
        String leap = recurrence("EXPENSE", "10.00", "MONTHLY", "2028-01-31", "\"maxOccurrences\":3");
        assertThat(dates(leap, "2028-01-01", "2028-12-31", "PENDING")).containsExactly("2028-01-31", "2028-02-29", "2028-03-31");
        String yearly = recurrence("EXPENSE", "10.00", "YEARLY", "2028-02-29", "\"maxOccurrences\":5");
        List<String> yearlyDates = new ArrayList<>(dates(yearly, "2028-01-01", "2030-12-31", "PENDING"));
        yearlyDates.addAll(dates(yearly, "2031-01-01", "2033-12-31", "PENDING"));
        assertThat(yearlyDates).containsExactly("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29");
        String daily = recurrence("EXPENSE", "10.00", "DAILY", "2026-10-10", "\"maxOccurrences\":3");
        assertThat(dates(daily, "2026-10-01", "2026-12-31", "PENDING")).containsExactly("2026-10-10", "2026-10-11", "2026-10-12");
        String weekly = recurrence("EXPENSE", "10.00", "WEEKLY", "2026-10-10", "\"maxOccurrences\":3");
        assertThat(dates(weekly, "2026-10-01", "2026-12-31", "PENDING")).containsExactly("2026-10-10", "2026-10-17", "2026-10-24");
        String custom2w = recurrence("EXPENSE", "10.00", "CUSTOM", "2026-10-10", "\"interval\":2,\"unit\":\"WEEKS\",\"maxOccurrences\":3");
        assertThat(dates(custom2w, "2026-10-01", "2026-12-31", "PENDING")).containsExactly("2026-10-10", "2026-10-24", "2026-11-07");
        String custom10d = recurrence("EXPENSE", "10.00", "CUSTOM", "2026-10-10", "\"interval\":10,\"unit\":\"DAYS\",\"endDate\":\"2026-11-09\"");
        assertThat(dates(custom10d, "2026-10-01", "2026-12-31", "PENDING")).containsExactly("2026-10-10", "2026-10-20", "2026-10-30", "2026-11-09");
        String custom3m = recurrence("EXPENSE", "10.00", "CUSTOM", "2026-11-30", "\"interval\":3,\"unit\":\"MONTHS\",\"maxOccurrences\":4");
        assertThat(dates(custom3m, "2026-11-01", "2027-12-31", "PENDING")).containsExactly("2026-11-30", "2027-02-28", "2027-05-30", "2027-08-30");
        String both = recurrence("EXPENSE", "10.00", "MONTHLY", "2026-10-10", "\"maxOccurrences\":10,\"endDate\":\"2026-12-10\"");
        assertThat(dates(both, "2026-10-01", "2027-12-31", "PENDING")).containsExactly("2026-10-10", "2026-11-10", "2026-12-10");
        assertThat(DebtQa.realize(a, monthly31, "2026-02-27", null).code()).isEqualTo("RECURRENCE_NOT_AN_OCCURRENCE");
        assertThat(DebtQa.skip(a, monthly31, "2026-02-27").code()).isEqualTo("RECURRENCE_NOT_AN_OCCURRENCE");
        assertThat(DebtQa.realize(a, monthly31, "2026-06-30", null).code()).isEqualTo("RECURRENCE_NOT_AN_OCCURRENCE");
        Res feb = CardQaWorld.ok(DebtQa.realize(a, monthly31, "2026-02-28", null));
        assertThat(feb.node().get("date").asString()).isEqualTo("2026-02-28");
        assertThat(a.ledgerSum()).isZero();
    }

    @Test
    @DisplayName("janela 2000-2100: início 2000-01-01 e 2100-12-31 aceitos, 1999-12-31 e 2101-01-01 recusados; realizar ocorrência depois de 2100 não dá 5xx")
    void janelaDaRecorrencia() throws Exception {
        assertThat(a.raw(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "10.00", "x", a.food, DebtQa.accountTarget(a, "EXPENSE"), "YEARLY", "1999-12-31", null), null).status()).isEqualTo(400);
        assertThat(a.raw(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "10.00", "x", a.food, DebtQa.accountTarget(a, "EXPENSE"), "YEARLY", "2101-01-01", null), null).status()).isEqualTo(400);
        String yearly2000 = recurrence("EXPENSE", "10.00", "YEARLY", "2000-01-01", null);
        String last = recurrence("EXPENSE", "10.00", "DAILY", "2100-12-30", null);
        List<String> failures = new ArrayList<>();
        for (Object[] c : new Object[][] {{yearly2000, "2101-01-01"}, {last, "2101-01-01"}, {last, "2100-12-31"}}) {
            Res r = DebtQa.realize(a, (String) c[0], (String) c[1], null);
            if (r.status() >= 500) {
                failures.add("realize " + c[1] + " -> " + r);
            }
            Res s = DebtQa.skip(a, (String) c[0], (String) c[1]);
            if (s.status() >= 500) {
                failures.add("skip " + c[1] + " -> " + s);
            }
        }
        for (String q : List.of("/v1/recurrences/occurrences?to=2300-01-01&pageSize=50", "/v1/recurrences/occurrences?from=2100-12-01&to=2101-12-31", "/v1/dashboard/upcoming?to=2300-01-01",
                "/v1/dashboard/summary?asOf=2100-12-31", "/v1/dashboard/summary?asOf=2101-01-01")) {
            Res r = a.raw(HttpMethod.GET, q, null, null);
            if (r.status() >= 500) {
                failures.add(q + " -> " + r);
            }
        }
        assertThat(failures).isEmpty();
        Long outside = jdbc.queryForObject("select count(*) from transactions where user_id = ? and transaction_date > date '2100-12-31'", Long.class, a.id);
        assertThat(outside).as("movimentação criada fora da janela financeira 2000-2100").isZero();
    }

    @Test
    @DisplayName("fuso America/Sao_Paulo: às 23:30 de 10/10 em SP (02:30Z de 11/10) a ocorrência de 10/10 ainda é de hoje; às 00:30 de 11/10 em SP ela está vencida (OVERDUE)")
    void fusoHorario() throws Exception {
        String id = recurrence("EXPENSE", "119.00", "MONTHLY", "2026-10-10", null);
        a.at(Instant.parse("2026-10-11T02:30:00Z"));
        JsonNode occ = firstOf(DebtQa.pending(a, LocalDate.of(2026, 10, 31)), id);
        assertThat(occ.get("date").asString()).isEqualTo("2026-10-10");
        assertThat(occ.get("overdue").asBoolean()).as("23:30 em SP ainda é 10/10").isFalse();
        JsonNode up = a.getOk("/v1/dashboard/upcoming");
        assertThat(up.get("items").get(0).get("bucket").asString()).isEqualTo("TODAY");
        a.at(Instant.parse("2026-10-11T03:30:00Z"));
        occ = firstOf(DebtQa.pending(a, LocalDate.of(2026, 10, 31)), id);
        assertThat(occ.get("overdue").asBoolean()).as("00:30 em SP já é 11/10").isTrue();
        assertThat(a.getOk("/v1/dashboard/upcoming").get("items").get(0).get("bucket").asString()).isEqualTo("OVERDUE");
        assertThat(a.getOk("/v1/dashboard/summary").get("asOf").asString()).isEqualTo("2026-10-11");
    }

    private static JsonNode firstOf(List<JsonNode> list, String recurrenceId) {
        return list.stream().filter(n -> n.get("recurrenceId").asString().equals(recurrenceId)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("S-20 ocorrência vencida: continua pendente e comprometida até realizar ou pular; realizar uma antiga cria a movimentação na data dela; pular a tira sem criar movimentação")
    void ocorrenciaVencida() throws Exception {
        String id = recurrence("EXPENSE", "119.00", "MONTHLY", "2026-08-10", null);
        List<JsonNode> pending = DebtQa.pending(a, LocalDate.of(2026, 10, 31));
        assertThat(pending.stream().filter(o -> o.get("recurrenceId").asString().equals(id)).map(o -> o.get("date").asString() + ":" + o.get("overdue").asBoolean()).toList())
                .containsExactly("2026-08-10:true", "2026-09-10:true", "2026-10-10:false");
        JsonNode up = a.getOk("/v1/dashboard/upcoming");
        assertThat(up.get("items").size()).isEqualTo(3 + 0 + (up.get("items").size() - 3));
        long overdueItems = 0;
        for (JsonNode i : up.get("items")) {
            if ("OVERDUE".equals(i.get("bucket").asString())) {
                overdueItems++;
            }
        }
        assertThat(overdueItems).isEqualTo(2);
        assertThat(a.getOk("/v1/dashboard/summary").get("committed").get("d30").get("amount").asString()).isEqualTo("357.00");
        Res realized = CardQaWorld.ok(DebtQa.realize(a, id, "2026-08-10", null));
        assertThat(realized.node().get("date").asString()).isEqualTo("2026-08-10");
        CardQaWorld.ok(DebtQa.skip(a, id, "2026-09-10"));
        assertThat(a.count("transactions")).isEqualTo(1L);
        assertThat(a.getOk("/v1/dashboard/summary").get("committed").get("d30").get("amount").asString()).isEqualTo("119.00");
        assertThat(DebtQa.realize(a, id, "2026-09-10", null).code()).isEqualTo("RECURRENCE_OCCURRENCE_SKIPPED");
        assertThat(DebtQa.skip(a, id, "2026-08-10").code()).isIn("RECURRENCE_OCCURRENCE_ALREADY_REALIZED", "RECURRENCE_OCCURRENCE_SKIPPED");
        assertThat(DebtQa.skip(a, id, "2026-09-10").status()).as("pular de novo").isIn(200, 422);
        CardQaWorld.ok(a.voidTx(realized.node().get("id").asString()));
        assertThat(a.getOk("/v1/dashboard/summary").get("committed").get("d30").get("amount").asString()).isEqualTo("238.00");
        assertThat(a.ledgerSum()).isZero();
    }

    @Test
    @DisplayName("recorrência desativada ou encerrada: sem ocorrência pendente nem item em /upcoming; as realizadas e puladas continuam listadas; realizar a desativada é recusado com RECURRENCE_INACTIVE (S-234); encerrar corta só o futuro")
    void desativadaEEncerrada() throws Exception {
        String off = recurrence("EXPENSE", "119.00", "MONTHLY", "2026-10-10", null);
        String ended = recurrence("EXPENSE", "10.00", "MONTHLY", "2026-10-10", null);
        CardQaWorld.ok(DebtQa.realize(a, off, "2026-10-10", null));
        CardQaWorld.ok(DebtQa.skip(a, off, "2026-11-10"));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences/" + off + "/deactivate", "", null));
        assertThat(a.call(HttpMethod.POST, "/v1/recurrences/" + off + "/deactivate", "", null).status()).as("desativar de novo").isEqualTo(200);
        assertThat(dates(off, "2026-10-01", "2027-03-31", "PENDING")).isEmpty();
        assertThat(dates(off, "2026-10-01", "2027-03-31", "REALIZED")).containsExactly("2026-10-10");
        assertThat(dates(off, "2026-10-01", "2027-03-31", "SKIPPED")).containsExactly("2026-11-10");
        Res end = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences/" + ended + "/end", "{\"endDate\":\"2026-12-15\"}", null));
        assertThat(end.node().get("endDate").asString()).isEqualTo("2026-12-15");
        assertThat(dates(ended, "2026-10-01", "2027-03-31", "PENDING")).containsExactly("2026-10-10", "2026-11-10", "2026-12-10");
        JsonNode up = a.getOk("/v1/dashboard/upcoming?from=2026-10-10&to=2027-03-31");
        List<String> refs = new ArrayList<>();
        up.get("items").forEach(i -> refs.add(i.get("refId").asString()));
        assertThat(refs).doesNotContain(off);
        assertThat(refs.stream().filter(ended::equals).count()).isEqualTo(3);
        Res before = a.raw(HttpMethod.POST, "/v1/recurrences/" + ended + "/end", "{\"endDate\":\"2026-09-01\"}", null);
        assertThat(before.status()).as(before.toString()).isIn(400, 422);
        Res yesterday = a.call(HttpMethod.POST, "/v1/recurrences/" + ended + "/end", "", null);
        assertThat(yesterday.status()).as(yesterday.toString()).isEqualTo(200);
        assertThat(dates(ended, "2026-10-01", "2027-03-31", "PENDING")).containsExactly("2026-10-10").as("encerrada hoje (10/10): a de hoje continua").isNotNull();
        Res late = DebtQa.realize(a, off, "2026-12-10", null);
        assertThat(late.status()).as(late.toString()).isEqualTo(422);
        assertThat(late.code()).isEqualTo("RECURRENCE_INACTIVE");
        Res overdue = DebtQa.realize(a, off, "2026-10-10", null);
        assertThat(overdue.code()).as(overdue.toString()).isEqualTo("RECURRENCE_INACTIVE");
        assertThat(a.ledgerSum()).isZero();
    }

    @Test
    @DisplayName("recorrência no cartão realizada: sai das previsões e passa a vir só da fatura; Σ upcoming OUT = faturas + ocorrências + dívidas datadas sem duplicar")
    void cartaoSemDuplicar() throws Exception {
        String rec = recurrence("CARD_PURCHASE", "49.90", "MONTHLY", "2026-09-15", null);
        CardQaWorld.ok(a.purchase("1233.10", 1, "2026-10-01", "Base"));
        CardQaWorld.ok(DebtQa.plainDebt(a, maria, "PAYABLE", "200.00", "2026-10-10", "2026-11-05", null));
        JsonNode before = a.getOk("/v1/dashboard/upcoming?from=2026-10-10&to=2026-11-30");
        long out = CardQaWorld.cents(before.get("totals").get("out"));
        long invoices = 0;
        for (JsonNode i : before.get("items")) {
            if (i.get("kind").asString().startsWith("INVOICE")) {
                invoices += CardQaWorld.cents(i.get("amount"));
            }
        }
        assertThat(out).isEqualTo(invoices + 3 * 4990L + 20_000L);
        CardQaWorld.ok(DebtQa.realize(a, rec, "2026-10-15", null));
        JsonNode after = a.getOk("/v1/dashboard/upcoming?from=2026-10-10&to=2026-11-30");
        assertThat(CardQaWorld.cents(after.get("totals").get("out"))).as("a de 15/10 saiu das previsões e entrou na fatura (mesmo total)").isEqualTo(out);
        long invoicesAfter = 0;
        for (JsonNode i : after.get("items")) {
            if (i.get("kind").asString().startsWith("INVOICE")) {
                invoicesAfter += CardQaWorld.cents(i.get("amount"));
            }
        }
        assertThat(invoicesAfter).isEqualTo(invoices + 4990L);
        assertThat(CardInvariants.check(a, a.card)).isEmpty();
    }

    @Test
    @DisplayName("desempenho (B4, S-240): 10 recorrências diárias desde 2000-01-01: /upcoming devolve só as 500 vencidas mais recentes (overdueTruncated), /occurrences idem, totais completos; tempo e tamanho medidos")
    void desempenhoDeRecorrenciaDiaria() throws Exception {
        for (int i = 0; i < 10; i++) {
            recurrence("EXPENSE", "1.00", "DAILY", "2000-01-01", null);
        }
        long t0 = System.nanoTime();
        Res up = a.get("/v1/dashboard/upcoming");
        long t1 = System.nanoTime();
        Res summary = a.get("/v1/dashboard/summary");
        long t2 = System.nanoTime();
        Res occ = a.get("/v1/recurrences/occurrences?to=2029-10-10&pageSize=50");
        long t3 = System.nanoTime();
        Res pending = a.get("/v1/recurrences/occurrences?status=PENDING&to=2026-10-09&pageSize=50&page=10");
        long t4 = System.nanoTime();
        long overdueItems = 0;
        for (JsonNode item : up.node().get("items")) {
            if ("OVERDUE".equals(item.get("bucket").asString())) {
                overdueItems++;
            }
        }
        System.out.println("QA-E4-PERF upcoming=" + (t1 - t0) / 1_000_000 + "ms (" + up.text().length() + " bytes, " + up.node().get("items").size() + " itens, " + overdueItems
                + " vencidos, overdueTruncated=" + up.node().get("overdueTruncated") + ") summary=" + (t2 - t1) / 1_000_000 + "ms occurrences(to=+3a)=" + (t3 - t2) / 1_000_000
                + "ms (" + occ.text().length() + " bytes, overdueTruncated=" + occ.node().get("overdueTruncated") + ") occurrences(pending, page 10)=" + (t4 - t3) / 1_000_000
                + "ms committed.d30=" + summary.node().get("committed").get("d30").get("amount").asString() + " totals.out=" + up.node().get("totals").get("out").get("amount").asString());
        assertThat(up.status()).isEqualTo(200);
        assertThat(summary.status()).isEqualTo(200);
        assertThat(occ.status()).isEqualTo(200);
        assertThat(overdueItems).as("só as 500 vencidas mais recentes").isEqualTo(500);
        assertThat(up.node().get("overdueTruncated").asBoolean()).isTrue();
        assertThat(occ.node().get("overdueTruncated").asBoolean()).isTrue();
        assertThat(pending.node().get("items").size()).isEqualTo(50);
        assertThat(pending.node().get("hasNext").asBoolean()).isFalse();
        assertThat(up.node().get("totals").get("out").get("amount").asString()).isEqualTo(summary.node().get("committed").get("d30").get("amount").asString());
    }

    @Test
    @DisplayName("S-239/S-240: datas extremas ou invertidas são 400 (nunca 500) em /upcoming, /occurrences e summary?asOf; janela de no máximo 3 anos")
    void janelaDasConsultas() throws Exception {
        for (String url : List.of("/v1/dashboard/upcoming?to=1999-12-31", "/v1/dashboard/upcoming?to=0000-01-01", "/v1/dashboard/upcoming?to=2026-10-09",
                "/v1/dashboard/upcoming?from=+999999999-12-31", "/v1/dashboard/upcoming?from=2026-10-10&to=2026-10-09", "/v1/dashboard/upcoming?to=2029-10-11",
                "/v1/dashboard/upcoming?from=2026-01-01&to=2029-01-02", "/v1/recurrences/occurrences?from=+999999999-12-31", "/v1/recurrences/occurrences?from=1999-12-31",
                "/v1/recurrences/occurrences?to=2101-01-01", "/v1/recurrences/occurrences?to=9999-12-31", "/v1/recurrences/occurrences?to=2029-10-11",
                "/v1/recurrences/occurrences?from=2026-12-01&to=2026-10-01", "/v1/recurrences/occurrences?from=2000-01-01&to=2003-01-02",
                "/v1/dashboard/summary?asOf=+999999999-12-31", "/v1/dashboard/summary?asOf=1999-12-31", "/v1/dashboard/summary?asOf=2101-01-01")) {
            Res r = a.raw(HttpMethod.GET, url, null, null);
            assertThat(r.status()).as(url + " -> " + r).isEqualTo(400);
            assertThat(r.code()).as(url).isEqualTo("VALIDATION_FAILED");
        }
        for (String url : List.of("/v1/dashboard/upcoming?to=2029-10-10", "/v1/dashboard/upcoming?from=2000-01-01&to=2003-01-01", "/v1/dashboard/upcoming?from=2097-12-31&to=2100-12-31",
                "/v1/recurrences/occurrences?to=2029-10-10", "/v1/recurrences/occurrences?to=2020-01-01", "/v1/recurrences/occurrences?from=2000-01-01&to=2003-01-01",
                "/v1/dashboard/summary?asOf=2000-01-01", "/v1/dashboard/summary?asOf=2100-12-31")) {
            Res r = a.raw(HttpMethod.GET, url, null, null);
            assertThat(r.status()).as(url + " -> " + r).isEqualTo(200);
        }
    }


    @Test
    @DisplayName("S-228 (alteração não aditiva): POST só com type LOAN_GIVEN/MONEY_RECEIVED/DEBT_PAYMENT agora é 400 (antes 422 NOT_AVAILABLE); INVOICE_PAYMENT segue 422 NOT_AVAILABLE; PUT de dívida é 422 NOT_EDITABLE (S-227), também com o corpo mínimo")
    void alteracaoNaoAditivaDoContrato() throws Exception {
        for (String type : List.of("LOAN_GIVEN", "MONEY_RECEIVED", "DEBT_PAYMENT")) {
            Res r = a.raw(HttpMethod.POST, "/v1/transactions", "{\"type\":\"" + type + "\"}", UUID.randomUUID().toString());
            assertThat(r.status()).as(type + " -> " + r).isEqualTo(400);
        }
        Res invoice = a.raw(HttpMethod.POST, "/v1/transactions", "{\"type\":\"INVOICE_PAYMENT\"}", UUID.randomUUID().toString());
        assertThat(invoice.status()).as(invoice.toString()).isEqualTo(422);
        assertThat(invoice.code()).isEqualTo("TRANSACTION_TYPE_NOT_AVAILABLE");
        Res loan = CardQaWorld.ok(DebtQa.loan(a, "100.00", "2026-10-10", joao, null));
        String id = loan.node().get("id").asString();
        Res minimal = a.raw(HttpMethod.PUT, "/v1/transactions/" + id, "{\"type\":\"LOAN_GIVEN\"}", null);
        assertThat(minimal.status()).as(minimal.toString()).isEqualTo(422);
        assertThat(minimal.code()).isEqualTo("TRANSACTION_NOT_EDITABLE");
        Res full = a.raw(HttpMethod.PUT, "/v1/transactions/" + id, "{\"type\":\"LOAN_GIVEN\",\"amount\":\"90.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + a.nubank
                + "\",\"newDebt\":{\"personId\":\"" + joao + "\"},\"version\":0}", null);
        assertThat(full.status()).as(full.toString()).isIn(400, 422);
        Res swapped = a.raw(HttpMethod.PUT, "/v1/transactions/" + id, "{\"type\":\"EXPENSE\",\"amount\":\"90.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + a.nubank
                + "\",\"categoryId\":\"" + a.food + "\",\"method\":\"PIX\",\"version\":0}", null);
        assertThat(swapped.status()).as(swapped.toString()).isEqualTo(422);
        assertThat(a.ledgerSum()).isZero();
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("operações da dívida: MONEY_RECEIVED sem debtId e sem newDebt = 400; com os dois = 400; DEBT_PAYMENT sem debtId = 400; direção errada = 422 DEBT_DIRECTION_MISMATCH; conta não CASH = 422")
    void tiposDeDividaValidacoes() throws Exception {
        String bare = "{\"type\":\"MONEY_RECEIVED\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + a.nubank + "\"}";
        assertThat(a.raw(HttpMethod.POST, "/v1/transactions", bare, UUID.randomUUID().toString()).status()).isEqualTo(400);
        Res loan = CardQaWorld.ok(DebtQa.loan(a, "100.00", "2026-10-10", joao, null));
        String debt = loan.node().get("debt").get("id").asString();
        String both = bare.substring(0, bare.length() - 1) + ",\"debtId\":\"" + debt + "\",\"newDebt\":{\"personId\":\"" + joao + "\"}}";
        assertThat(a.raw(HttpMethod.POST, "/v1/transactions", both, UUID.randomUUID().toString()).status()).isEqualTo(400);
        assertThat(a.raw(HttpMethod.POST, "/v1/transactions", "{\"type\":\"DEBT_PAYMENT\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + a.nubank + "\"}",
                UUID.randomUUID().toString()).status()).isEqualTo(400);
        assertThat(DebtQa.payment(a, "10.00", "2026-10-10", debt).code()).isEqualTo("DEBT_DIRECTION_MISMATCH");
        String payable = CardQaWorld.ok(DebtQa.plainDebt(a, maria, "PAYABLE", "50.00", "2026-10-10", null, null)).node().get("id").asString();
        assertThat(DebtQa.received(a, "10.00", "2026-10-10", payable).code()).isEqualTo("DEBT_DIRECTION_MISMATCH");
        String invest = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/accounts", "{\"name\":\"CDB\",\"type\":\"INVESTMENT\",\"institution\":\"X\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},"
                + "\"openingDate\":\"2026-10-01\"}", null)).node().get("id").asString();
        Res onInvest = DebtQa.createTx(a, "{\"type\":\"LOAN_GIVEN\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + invest + "\",\"newDebt\":{\"personId\":\"" + joao + "\"}}");
        assertThat(onInvest.code()).isEqualTo("TRANSACTION_ACCOUNT_NOT_CASH");
        assertThat(a.ledgerSum()).isZero();
    }

    @Test
    @DisplayName("paginação e filtros das listas novas: pageSize 51 e page 0 são 400; status/direction/overdue inválidos 400; q longo e filtros cruzados sem 5xx")
    void paginacaoEFiltros() throws Exception {
        for (String base : List.of("/v1/people", "/v1/debts", "/v1/recurrences", "/v1/recurrences/occurrences")) {
            assertThat(a.raw(HttpMethod.GET, base + "?pageSize=51", null, null).status()).as(base).isEqualTo(400);
            assertThat(a.raw(HttpMethod.GET, base + "?page=0", null, null).status()).as(base).isEqualTo(400);
            assertThat(a.raw(HttpMethod.GET, base + "?pageSize=abc", null, null).status()).as(base).isEqualTo(400);
            assertThat(a.raw(HttpMethod.GET, base + "?pageSize=1.5", null, null).status()).as(base).isEqualTo(400);
        }
        for (String q : List.of("/v1/people?status=XYZ", "/v1/debts?direction=XYZ", "/v1/debts?status=XYZ", "/v1/debts?overdue=maybe", "/v1/debts?personId=abc",
                "/v1/recurrences/occurrences?status=XYZ", "/v1/recurrences/occurrences?from=2026-12-01&to=2026-01-01", "/v1/debts?personId=" + UUID.randomUUID())) {
            Res r = a.raw(HttpMethod.GET, q, null, null);
            assertThat(r.status()).as(q + " -> " + r).isIn(200, 400);
        }
        assertThat(a.raw(HttpMethod.GET, "/v1/recurrences/occurrences?from=2026-12-01&to=2026-01-01", null, null).status()).isEqualTo(400);
        assertThat(a.raw(HttpMethod.GET, "/v1/people?status=XYZ", null, null).status()).isEqualTo(400);
    }
}
