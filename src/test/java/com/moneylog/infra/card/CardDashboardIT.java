package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.util.ContractValidation;

@DisplayName("Dashboard e compromissos com cartões, sem dupla contagem (T59)")
class CardDashboardIT extends CardItBase {

    private JsonNode summary() throws Exception {
        return okBody(get("/v1/dashboard/summary"));
    }

    private JsonNode upcoming(String query) throws Exception {
        return okBody(get("/v1/dashboard/upcoming" + query));
    }

    private JsonNode entries(String competence) throws Exception {
        return okBody(get("/v1/entries?competence=" + competence + "&pageSize=50"));
    }

    private String sumOfUnpaidOutstanding() throws Exception {
        long cents = 0;
        for (JsonNode invoice : okBody(get("/v1/cards/" + card + "/invoices?pageSize=50")).get("items")) {
            if (!"PAID".equals(invoice.get("status").asString())) {
                cents += new java.math.BigDecimal(money(invoice, "outstanding")).movePointRight(2).longValueExact();
            }
        }
        return java.math.BigDecimal.valueOf(cents, 2).toPlainString();
    }

    @Test
    @DisplayName("CA1: /upcoming traz UM item INVOICE_FORECAST (Roxinho, 05/11, 1.283,00) com as parcelas aninhadas ('Notebook 1/6' 200,00 e a compra à vista) e nenhum item INSTALLMENT de primeiro nível; os totais somam só as faturas")
    void deveMostrarAFaturaComAsParcelasAninhadas() throws Exception {
        twoPurchases();

        JsonNode upcoming = upcoming("");

        assertThat(upcoming.get("items")).hasSize(1);
        JsonNode item = upcoming.get("items").get(0);
        assertThat(item.get("kind").asString()).isEqualTo("INVOICE_FORECAST");
        assertThat(item.get("dueDate").asString()).isEqualTo("2026-11-05");
        assertThat(item.get("label").asString()).isEqualTo("Roxinho");
        assertThat(item.get("direction").asString()).isEqualTo("OUT");
        assertThat(money(item, "amount")).isEqualTo("1283.00");
        assertThat(item.get("refType").asString()).isEqualTo("INVOICE");
        assertThat(item.get("bucket").asString()).isEqualTo("NEXT_30_DAYS");
        assertThat(item.get("items")).hasSize(2);
        JsonNode notebook = item.get("items").get(0);
        assertThat(notebook.get("label").asString()).isEqualTo("Notebook 1/6");
        assertThat(money(notebook, "amount")).isEqualTo("200.00");
        assertThat(notebook.get("informational").asBoolean()).isTrue();
        assertThat(notebook.get("installment").get("number").asInt()).isEqualTo(1);
        assertThat(notebook.get("installment").get("total").asInt()).isEqualTo(6);
        assertThat(item.get("items").get(1).get("label").asString()).isEqualTo("Mercado 1/1");
        assertThat(money(upcoming.get("totals"), "out")).isEqualTo("1283.00");
        assertThat(money(upcoming.get("totals"), "in")).isEqualTo("0.00");
        assertThat(upcoming.get("undated")).isEmpty();

        JsonNode wide = upcoming("?from=2026-10-10&to=2027-04-30");
        assertThat(wide.get("items")).hasSize(6);
        assertThat(wide.get("items").valueStream().map(i -> i.get("kind").asString()).distinct().toList()).containsExactly("INVOICE_FORECAST");
        assertThat(money(wide.get("totals"), "out")).isEqualTo("2283.00");
        assertThat(wide.get("items").get(1).get("dueDate").asString()).isEqualTo("2026-12-05");
        assertThat(wide.get("items").get(1).get("items").get(0).get("label").asString()).isEqualTo("Notebook 2/6");
    }

    @Test
    @DisplayName("CA2: committed.d30 inclui os 1.283,00 e não as parcelas 2..6; d60 inclui 05/12 (+200,00) e d90 também 05/01 (+200,00); o disponível é 20.000 − d30 − reservado")
    void deveCalcularOsHorizontes() throws Exception {
        twoPurchases();

        JsonNode committed = summary().get("committed");

        assertThat(money(committed, "d7")).isEqualTo("0.00");
        assertThat(money(committed, "d30")).isEqualTo("1283.00");
        assertThat(money(committed, "d60")).isEqualTo("1483.00");
        assertThat(money(committed, "d90")).isEqualTo("1683.00");
        assertThat(money(committed, "m6")).isEqualTo("2283.00");
        assertThat(money(committed, "m12")).isEqualTo("2283.00");
        JsonNode balances = summary().get("balances");
        assertThat(money(balances, "available")).isEqualTo("18717.00");
        assertThat(money(balances, "committed30d")).isEqualTo("1283.00");
        assertThat(money(balances, "reserved")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("CA3: o disponível é 18.717,00 em 10/10 e em 30/10, sem salto na virada do mês (a fatura passa de INVOICE_FORECAST para INVOICE e continua contada uma vez)")
    void deveManterODisponivelNaVirada() throws Exception {
        twoPurchases();
        assertThat(money(summary().get("balances"), "available")).isEqualTo("18717.00");

        advanceTo(OCT_30);

        assertThat(money(summary().get("balances"), "available")).isEqualTo("18717.00");
        JsonNode item = upcoming("").get("items").get(0);
        assertThat(item.get("kind").asString()).isEqualTo("INVOICE");
        assertThat(money(item, "amount")).isEqualTo("1283.00");
        assertThat(upcoming("").get("items")).hasSize(1);
    }

    @Test
    @DisplayName("CA4: pagar a fatura: committed.d30 cai 1.283,00 e inAccounts cai 1.283,00 (o disponível não muda); month.consumption não muda; month.credit mostra só as parcelas da competência")
    void devePagarAFaturaSemRecontabilizar() throws Exception {
        twoPurchases();
        JsonNode october = summary().get("month");
        assertThat(october.get("competence").asString()).isEqualTo("2026-10");
        assertThat(money(october, "credit")).isEqualTo("1283.00");
        assertThat(money(october, "consumption")).isEqualTo("1283.00");
        assertThat(money(october.get("expensesPaid"), "total")).isEqualTo("0.00");
        assertThat(money(october.get("cashFlow"), "out")).isEqualTo("0.00");
        advanceTo(NOV_05);
        JsonNode before = summary();
        assertThat(money(before.get("committed"), "d30")).isEqualTo("1483.00");
        assertThat(money(before.get("balances"), "inAccounts")).isEqualTo("20000.00");
        assertThat(money(before.get("balances"), "available")).isEqualTo("18517.00");
        assertThat(money(before.get("month"), "credit")).isEqualTo("200.00");

        paid(NOV_CLOSING, "1283.00", "2026-11-05");

        JsonNode after = summary();
        assertThat(money(after.get("committed"), "d30")).isEqualTo("200.00");
        assertThat(money(after.get("balances"), "inAccounts")).isEqualTo("18717.00");
        assertThat(money(after.get("balances"), "available")).isEqualTo("18517.00");
        assertThat(money(after.get("month"), "consumption")).isEqualTo(money(before.get("month"), "consumption"));
        assertThat(money(after.get("month"), "credit")).isEqualTo("200.00");
        assertThat(money(after.get("month").get("cashFlow"), "out")).isEqualTo("1283.00");
        assertThat(upcoming("").get("items").valueStream().map(i -> i.get("dueDate").asString()).toList()).containsExactly("2026-12-05");
    }

    @Test
    @DisplayName("CA5: netWorth.cardFutureInstallments é 1.000,00 (informativo, fora do total) e o patrimônio só leva o passivo já vencido do cartão")
    void deveMostrarAsParcelasFuturasForaDoPatrimonio() throws Exception {
        twoPurchases();

        JsonNode netWorth = summary().get("netWorth");

        assertThat(money(netWorth, "cardFutureInstallments")).isEqualTo("1000.00");
        assertThat(money(netWorth, "cardLiabilities")).isEqualTo("-1283.00");
        assertThat(money(netWorth, "accounts")).isEqualTo("20000.00");
        assertThat(money(netWorth, "total")).isEqualTo("18717.00");
    }

    @Test
    @DisplayName("CA6: matriz sem dupla contagem (parcelada, à vista, reembolso, antecipado, parcial e fatura vencida): Σ upcoming OUT = Σ outstanding das faturas não pagas, o consumo por competência não depende do pagamento e cada parcela aparece uma vez")
    void deveNaoDuplicarNaMatriz() throws Exception {
        twoPurchases();
        String refund = created(cardRefund("50.00", "2026-10-10", "Estorno do banco")).get("id").asString();
        assertThat(refund).isNotBlank();
        advanceTo(Instant.parse("2026-10-20T15:00:00Z"));
        paid(NOV_CLOSING, "200.00", "2026-10-20");
        advanceTo(Instant.parse("2026-11-08T15:00:00Z"));
        List<String> competences = List.of("2026-10", "2026-11", "2026-12", "2027-01", "2027-02", "2027-03");
        List<String> consumptionBefore = competences.stream().map(c -> {
            try {
                return money(entries(c).get("totals"), "total");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        JsonNode overdue = invoice(NOV_CLOSING);
        assertThat(overdue.get("status").asString()).isEqualTo("OVERDUE");
        assertThat(money(overdue, "outstanding")).isEqualTo("1033.00");

        paid(NOV_CLOSING, "500.00", "2026-11-08");

        assertThat(money(invoice(NOV_CLOSING), "outstanding")).isEqualTo("533.00");
        JsonNode upcoming = upcoming("?from=2026-11-08&to=2027-12-31");
        assertThat(upcoming.get("items").valueStream().map(i -> i.get("refType").asString()).distinct().toList()).containsExactly("INVOICE");
        assertThat(money(upcoming.get("totals"), "out")).as("Σ upcoming OUT = Σ outstanding das faturas não pagas")
                .isEqualTo(sumOfUnpaidOutstanding());
        assertThat(upcoming.get("items").valueStream().map(i -> i.get("label").asString()).distinct().toList()).containsExactly("Roxinho");
        assertThat(upcoming.get("items").valueStream().filter(i -> "INSTALLMENT".equals(i.get("kind").asString())).count()).isZero();
        List<String> consumptionAfter = competences.stream().map(c -> {
            try {
                return money(entries(c).get("totals"), "total");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        assertThat(consumptionAfter).isEqualTo(consumptionBefore);
        assertThat(consumptionAfter).containsExactly("1233.00", "200.00", "200.00", "200.00", "200.00", "200.00");
        assertThat(entries("2026-11").get("items")).hasSize(1);
        assertThat(entries("2026-10").get("items")).hasSize(3);
        assertThat(money(summary().get("committed"), "m12")).isEqualTo(upcoming("?from=2026-11-08&to=2027-12-31").get("totals").get("out").get("amount").asString());
    }

    @Test
    @DisplayName("CA7: a lista de movimentações e o dashboard concordam no mês: caixa 0 na compra no cartão e a saída só no pagamento")
    void deveConcordarListaEDashboard() throws Exception {
        twoPurchases();
        JsonNode listOct = okBody(get("/v1/transactions?from=2026-10-01&to=2026-10-31&pageSize=50"));
        assertThat(money(listOct.get("totals").get("cashFlow"), "out")).isEqualTo("0.00");
        assertThat(money(listOct.get("totals").get("cashFlow"), "out")).isEqualTo(money(summary().get("month").get("cashFlow"), "out"));
        advanceTo(NOV_05);
        paid(NOV_CLOSING, "1283.00", "2026-11-05");

        JsonNode listNov = okBody(get("/v1/transactions?from=2026-11-01&to=2026-11-30&pageSize=50"));

        assertThat(money(listNov.get("totals").get("cashFlow"), "out")).isEqualTo("1283.00");
        assertThat(money(listNov.get("totals").get("cashFlow"), "out")).isEqualTo(money(summary().get("month").get("cashFlow"), "out"));
        assertThat(money(listNov.get("totals").get("cashFlow"), "net")).isEqualTo(money(summary().get("month").get("cashFlow"), "net"));
    }

    @Test
    @DisplayName("CA8: fatura vencida e não paga aparece em Bucket.OVERDUE e entra em todos os horizontes (d7 inclusive)")
    void deveContarAFaturaVencidaEmTodosOsHorizontes() throws Exception {
        twoPurchases();
        advanceTo(Instant.parse("2026-11-08T15:00:00Z"));

        JsonNode upcoming = upcoming("");
        JsonNode overdue = upcoming.get("items").get(0);
        assertThat(overdue.get("kind").asString()).isEqualTo("INVOICE");
        assertThat(overdue.get("bucket").asString()).isEqualTo("OVERDUE");
        assertThat(overdue.get("dueDate").asString()).isEqualTo("2026-11-05");
        assertThat(money(overdue, "amount")).isEqualTo("1283.00");
        JsonNode committed = summary().get("committed");
        assertThat(money(committed, "d7")).isEqualTo("1283.00");
        assertThat(money(committed, "d30")).isEqualTo("1483.00");
        assertThat(money(committed, "m12")).isEqualTo("2283.00");
        assertThat(money(summary().get("balances"), "available")).isEqualTo("18517.00");
        mockMvc.perform(get("/v1/dashboard/upcoming?from=2026-12-31&to=2026-12-31")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms());
        assertThat(UUID.fromString(card)).isNotNull();
    }
}
