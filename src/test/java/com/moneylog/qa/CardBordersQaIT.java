package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
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

import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * T61 (QA): ciclo completo (D1 §2.5.4), bordas de data (fechamento 29/30/31, bissexto, virada de ano, fuso), fatura vazia e
 * passada, e os achados do revisor sobre cancelamento depois de reembolso (A1) e ids da lista (B4).
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T61: ciclo, bordas de data e regressões do cartão")
class CardBordersQaIT {

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
    private CardQaWorld.Actor a;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("bd");
    }

    private long expenseBook() {
        Long v = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'EXPENSE'", Long.class, a.id);
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------ critério 1: ciclo completo

    @Test
    @DisplayName("T61 CA1: 1.200,00 em 6x e as 6 faturas pagas: Σ consumo = 1.200,00, Σ caixa = −1.200,00, livro CARD = 0, nenhum mês com mais de 200,00")
    void cicloCompleto() throws Exception {
        CardQaWorld.ok(a.purchase("1200.00", 6, "2026-10-10", "Notebook"));
        String[] closings = {"2026-10-25", "2026-11-25", "2026-12-25", "2027-01-25", "2027-02-25", "2027-03-25"};
        String[] payDays = {"2026-10-26", "2026-11-26", "2026-12-26", "2027-01-26", "2027-02-26", "2027-03-26"};
        for (int i = 0; i < 6; i++) {
            a.at(Instant.parse(payDays[i] + "T15:00:00Z"));
            JsonNode inv = CardQaWorld.ok(a.pay(a.invoiceId(LocalDate.parse(closings[i])), "200.00", payDays[i], null)).node();
            assertThat(inv.get("status").asString()).isEqualTo("PAID");
            assertThat(CardInvariants.check(a, a.card)).as("após pagar a fatura " + closings[i]).isEmpty();
        }
        long consumption = 0;
        for (String competence : List.of("2026-10", "2026-11", "2026-12", "2027-01", "2027-02", "2027-03", "2027-04")) {
            long month = CardQaWorld.cents(a.getOk("/v1/entries?competence=" + competence).get("totals").get("total"));
            assertThat(month).as("consumo de " + competence).isLessThanOrEqualTo(20_000L);
            consumption += month;
        }
        assertThat(consumption).isEqualTo(120_000L);
        assertThat(a.getOk("/v1/accounts/" + a.nubank).get("currentBalance").get("amount").asString()).isEqualTo("18800.00");
        assertThat(a.cardBook(a.card)).isZero();
        assertThat(a.cardNow().get("availableLimit").get("amount").asString()).isEqualTo("10000.00");
        assertThat(world.contractViolations).isEmpty();
    }

    // ------------------------------------------------------------------ revisor A1 (reembolso -> cancelamento)

    @Test
    @DisplayName("A1: compra 1.200 em 6x, parcela 1 em fatura fechada, reembolso de 1.000 aceito; cancelar as parcelas 2..6 NÃO pode deixar o consumo da compra negativo")
    void reembolsoDepoisCancelamentoNaoDeixaConsumoNegativo() throws Exception {
        String purchase = CardQaWorld.ok(a.purchase("1200.00", 6, "2026-10-10", "Notebook")).node().get("id").asString();
        a.at(Instant.parse("2026-10-30T15:00:00Z"));
        CardQaWorld.Res refund = a.cardRefund("1000.00", "2026-10-30", purchase);
        assertThat(refund.status()).as(refund.toString()).isEqualTo(201);
        CardQaWorld.Res cancel = a.cancel(purchase);
        assertThat(cancel.status()).as("cancelar com reembolso ativo de 1.000 e cobrado remanescente de 200: " + cancel).isNotEqualTo(200);
        assertThat(expenseBook()).as("consumo total (livro EXPENSE) nunca negativo: compra 1.200 − reembolso 1.000 − parcelas canceladas 1.000").isGreaterThanOrEqualTo(0);
        assertThat(CardInvariants.check(a, a.card)).isEmpty();
    }

    @Test
    @DisplayName("A1 (controle): reembolso de 200,00 e depois cancelar as parcelas restantes é aceito e o consumo fica em 0,00")
    void reembolsoPequenoDepoisCancelamento() throws Exception {
        String purchase = CardQaWorld.ok(a.purchase("1200.00", 6, "2026-10-10", "Notebook")).node().get("id").asString();
        a.at(Instant.parse("2026-10-30T15:00:00Z"));
        assertThat(a.cardRefund("200.00", "2026-10-30", purchase).status()).isEqualTo(201);
        assertThat(a.cancel(purchase).status()).isEqualTo(200);
        assertThat(expenseBook()).isZero();
        assertThat(CardInvariants.check(a, a.card)).isEmpty();
    }

    // ------------------------------------------------------------------ revisor B4

    @Test
    @DisplayName("B4: todo id devolvido pela lista de faturas resolve em GET /v1/invoices/{id} (cartão usado em 01/2024 e retomado em 10/2026)")
    void idsDaListaResolvem() throws Exception {
        a.at(Instant.parse("2024-01-10T15:00:00Z"));
        String oldCard = a.newCard("Parado", 25, 5, "10000.00");
        CardQaWorld.ok(a.purchaseOn(oldCard, a.tech, "50.00", 1, "2024-01-10", "Antiga"));
        a.at(MutableClockConfig.INITIAL);
        CardQaWorld.ok(a.purchaseOn(oldCard, a.tech, "60.00", 1, "2026-10-10", "Atual"));
        List<JsonNode> list = a.invoicesOf(oldCard);
        List<String> failures = new ArrayList<>();
        for (JsonNode i : list) {
            CardQaWorld.Res r = a.get("/v1/invoices/" + i.get("id").asString());
            if (r.status() != 200) {
                failures.add(i.get("closingDate").asString() + " (" + i.get("total").get("amount").asString() + ") -> " + r.status() + " " + r.code());
            }
        }
        assertThat(failures).as("faturas listadas (" + list.size() + ") cujo id dá 404 no detalhe").isEmpty();
    }

    // ------------------------------------------------------------------ bordas de data: oráculo independente

    private static int dayOf(int day, YearMonth m) {
        return Math.min(day, m.lengthOfMonth());
    }

    /** Fechamento, vencimento e referência da fatura que fecha em {@code m}, calculados fora do domínio (D1 §2.5.1). */
    private static LocalDate[] oracle(int closingDay, int dueDay, YearMonth m) {
        LocalDate closing = m.atDay(dayOf(closingDay, m));
        YearMonth dueMonth = dueDay > closingDay ? m : m.plusMonths(1);
        LocalDate due = dueMonth.atDay(dayOf(dueDay, dueMonth));
        if (!due.isAfter(closing)) {
            YearMonth next = m.plusMonths(1);
            due = next.atDay(dayOf(dueDay, next));
        }
        return new LocalDate[] {closing, due};
    }

    private static LocalDate oracleClosingFor(int closingDay, LocalDate purchase) {
        YearMonth m = YearMonth.from(purchase);
        LocalDate thisMonth = m.atDay(dayOf(closingDay, m));
        return purchase.isBefore(thisMonth) ? thisMonth : oracle(closingDay, 1, m.plusMonths(1))[0];
    }

    @Test
    @DisplayName("bordas: fechamento 1/15/28/29/30/31 x vencimento 1/5/15/28/30/31 x datas de fim de mês, bissexto e virada de ano: a prévia bate com o oráculo independente (fatura, vencimento, referência, competência)")
    void calendarioContraOraculo() throws Exception {
        int[] closings = {1, 28, 29, 30, 31};
        int[] dues = {1, 15, 30, 31};
        String[] dates = {"2027-12-30", "2027-12-31", "2028-01-01", "2028-01-28", "2028-01-29", "2028-01-30", "2028-01-31", "2028-02-01", "2028-02-28",
                "2028-02-29", "2028-03-01", "2028-03-30", "2028-03-31", "2026-10-31", "2027-02-27", "2027-02-28", "2027-03-01", "2026-12-31", "2027-01-01"};
        List<String> mismatches = new ArrayList<>();
        for (int closing : closings) {
            for (int due : dues) {
                String cardId = a.newCard("C" + closing + "D" + due, closing, due, "10000.00");
                for (String d : dates) {
                    LocalDate date = LocalDate.parse(d);
                    CardQaWorld.Res r = a.call(HttpMethod.POST, "/v1/cards/" + cardId + "/purchase-preview",
                            "{\"amount\":\"100.00\",\"installments\":4,\"date\":\"" + d + "\"}", null);
                    if (r.status() != 200) {
                        mismatches.add("c" + closing + " d" + due + " " + d + " -> " + r);
                        continue;
                    }
                    LocalDate firstClosing = oracleClosingFor(closing, date);
                    for (int k = 0; k < 4; k++) {
                        JsonNode inst = r.node().get("installments").get(k);
                        YearMonth m = YearMonth.from(firstClosing).plusMonths(k);
                        LocalDate[] exp = oracle(closing, due, m);
                        String got = inst.get("invoice").get("closingDate").asString() + "/" + inst.get("invoice").get("dueDate").asString()
                                + "/" + inst.get("invoice").get("referenceMonth").asString() + "/" + inst.get("competence").asString();
                        String want = exp[0] + "/" + exp[1] + "/" + YearMonth.from(exp[1]) + "/" + YearMonth.from(date.plusMonths(k));
                        if (!got.equals(want)) {
                            mismatches.add("fecha " + closing + " vence " + due + " compra " + d + " parcela " + (k + 1) + ": got " + got + " want " + want);
                        }
                        if (!LocalDate.parse(inst.get("invoice").get("dueDate").asString()).isAfter(LocalDate.parse(inst.get("invoice").get("closingDate").asString()))) {
                            mismatches.add("vencimento não posterior ao fechamento: " + got);
                        }
                    }
                }
            }
        }
        assertThat(mismatches).as("divergências do calendário contra o oráculo").isEmpty();
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("S-77: fechamento 30, vencimento 30: o vencimento vai para o mês seguinte; compra no dia do fechamento cai na fatura seguinte; fevereiro bissexto fecha em 29")
    void vencimentoNoDiaDoFechamento() throws Exception {
        String cardId = a.newCard("Trinta", 30, 30, "10000.00");
        JsonNode p = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/cards/" + cardId + "/purchase-preview",
                "{\"amount\":\"100.00\",\"installments\":1,\"date\":\"2026-10-30\"}", null)).node();
        JsonNode inv = p.get("installments").get(0).get("invoice");
        assertThat(inv.get("closingDate").asString()).isEqualTo("2026-11-30");
        assertThat(inv.get("dueDate").asString()).isEqualTo("2026-12-30");
        JsonNode leap = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/cards/" + cardId + "/purchase-preview",
                "{\"amount\":\"100.00\",\"installments\":1,\"date\":\"2028-02-10\"}", null)).node();
        assertThat(leap.get("installments").get(0).get("invoice").get("closingDate").asString()).isEqualTo("2028-02-29");
    }

    @Test
    @DisplayName("fuso America/Sao_Paulo: 24/10 22:30 em São Paulo (25/10 01:30Z) a fatura que fecha 25/10 ainda é a OPEN; 25/10 23:30 em SP já é CLOSED e a corrente fecha 25/11")
    void fusoHorario() throws Exception {
        a.at(Instant.parse("2026-10-25T01:30:00Z"));
        JsonNode before = a.cardNow().get("currentInvoice");
        assertThat(before.get("closingDate").asString()).isEqualTo("2026-10-25");
        assertThat(before.get("status").asString()).isEqualTo("OPEN");
        a.at(Instant.parse("2026-10-26T02:30:00Z"));
        JsonNode after = a.cardNow().get("currentInvoice");
        assertThat(after.get("closingDate").asString()).isEqualTo("2026-11-25");
        assertThat(after.get("status").asString()).isEqualTo("OPEN");
        assertThat(a.getOk("/v1/dashboard/summary").get("asOf").asString()).isEqualTo("2026-10-25");
    }

    @Test
    @DisplayName("fatura vazia e fatura passada: GET resolve (total 0,00); pagar a vazia/passada nunca dá 5xx e o invariante fecha")
    void faturaVaziaEPassada() throws Exception {
        JsonNode empty = a.getOk("/v1/invoices/" + a.invoiceId(LocalDate.of(2026, 10, 25)));
        assertThat(empty.get("total").get("amount").asString()).isEqualTo("0.00");
        assertThat(empty.get("items")).isEmpty();
        JsonNode past = a.getOk("/v1/invoices/" + a.invoiceId(LocalDate.of(2026, 8, 25)));
        assertThat(past.get("total").get("amount").asString()).isEqualTo("0.00");
        for (LocalDate closing : List.of(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 8, 25), LocalDate.of(2026, 12, 25))) {
            CardQaWorld.Res pay = a.pay(a.invoiceId(closing), "10.00", "2026-10-10", null);
            assertThat(pay.status()).as(closing + " " + pay).isLessThan(500);
            CardQaWorld.Res prev = a.call(HttpMethod.GET, "/v1/invoices/" + a.invoiceId(closing) + "/payment-preview?amount=10.00", null, null);
            assertThat(prev.status()).as(closing + " " + prev).isLessThan(500);
        }
        assertThat(CardInvariants.check(a, a.card)).isEmpty();
        assertThat(a.get("/v1/invoices/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(world.contractViolations).isEmpty();
    }
}
