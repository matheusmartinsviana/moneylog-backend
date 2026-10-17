package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.util.ContractValidation;

@DisplayName("A2a: criar compra rateada no cartão (ADR-022): razão, divisão, prévia e fatura")
class SharedPurchaseCreationIT extends ResumaoItBase {

    @BeforeEach
    void mundo() throws Exception {
        basicWorld();
    }

    @Test
    @DisplayName("300,00 em 3x, eu 5000 / Ana 5000: cada parcela EXPENSE +50,00, RECEIVABLE(Ana) +50,00, CARD -100,00, soma zero e fatura cheia de 100,00")
    void trezentosEmTresVezesMetadeMetade() throws Exception {
        JsonNode tx = created(sharedPurchase(cardA, "300.00", 3, "2026-10-05", "BPS", "[" + share(null, 5000) + "," + share(ana, 5000) + "]"));
        String id = tx.get("id").asString();

        var rows = byInstallment(id);
        assertThat(rows).hasSize(3);
        rows.forEach((number, books) -> {
            assertThat(books).as("parcela " + number).containsEntry("EXPENSE", 5000L).containsEntry("RECEIVABLE", 5000L)
                    .containsEntry("CARD", -10000L);
            assertThat(books.values().stream().mapToLong(Long::longValue).sum()).isZero();
        });
        assertThat(receivableOf(id, ana)).isEqualTo(15000L);
        assertThat(totalOfTransaction(id)).isZero();
        assertThat(tx.get("splitMode").asString()).isEqualTo("BPS");
        assertThat(money(tx, "myAmount")).isEqualTo("150.00");
        assertThat(money(tx, "thirdPartyAmount")).isEqualTo("150.00");

        JsonNode invoice = okBody(get("/v1/invoices/" + InvoiceIds.of(UUID.fromString(cardA), LocalDate.of(2026, 10, 25))));
        assertThat(money(invoice, "total")).isEqualTo("100.00");
        assertThat(money(invoice, "myShare")).isEqualTo("50.00");
        assertThat(money(invoice, "thirdPartyShare")).isEqualTo("50.00");
        assertThat(invoice.get("shareByPerson")).hasSize(1);
        assertThat(invoice.get("shareByPerson").get(0).get("personId").asString()).isEqualTo(ana);
        assertThat(money(invoice.get("shareByPerson").get(0), "amount")).isEqualTo("50.00");
    }

    @Test
    @DisplayName("EQUAL entre eu, Ana e Beto numa parcela de 100,00: Ana 33,33, Beto 33,33 e o dono 33,34, em todas as parcelas")
    void divisaoIgualComRestoDoDono() throws Exception {
        String id = created(sharedPurchase(cardA, "300.00", 3, "2026-10-05", "EQUAL",
                "[" + share(null, null) + "," + share(ana, null) + "," + share(beto, null) + "]")).get("id").asString();

        byInstallment(id).forEach((number, books) -> assertThat(books).as("parcela " + number).containsEntry("EXPENSE", 3334L)
                .containsEntry("CARD", -10000L).containsEntry("RECEIVABLE", 6666L));
        assertThat(receivableOf(id, ana)).isEqualTo(3L * 3333);
        assertThat(receivableOf(id, beto)).isEqualTo(3L * 3333);
    }

    @Test
    @DisplayName("BPS 3333/3333 para terceiros numa parcela de 100,00: 33,33 cada e o dono 33,34; Σ das partes = 100,00")
    void bpsComRestoDoDono() throws Exception {
        String id = created(sharedPurchase(cardA, "100.00", 1, "2026-10-05", "BPS",
                "[" + share(null, 3334) + "," + share(ana, 3333) + "," + share(beto, 3333) + "]")).get("id").asString();

        var books = byInstallment(id).get(1);
        assertThat(books).containsEntry("EXPENSE", 3334L).containsEntry("RECEIVABLE", 6666L).containsEntry("CARD", -10000L);
        assertThat(receivableOf(id, ana)).isEqualTo(3333L);
        assertThat(receivableOf(id, beto)).isEqualTo(3333L);
    }

    @Test
    @DisplayName("soma de bps inválida, pessoa repetida e AMOUNT que não soma a parcela: 422 SHARES_INVALID e nada é gravado")
    void rateioInvalidoNaoGrava() throws Exception {
        long before = count("transactions");
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);
        String[] bodies = {
                sharedPurchase(cardA, "100.00", 1, "2026-10-05", "BPS", "[" + share(null, 5000) + "," + share(ana, 4000) + "]"),
                sharedPurchase(cardA, "100.00", 1, "2026-10-05", "BPS", "[" + share(null, 4000) + "," + share(ana, 3000) + "," + share(ana, 3000) + "]"),
                sharedPurchase(cardA, "100.00", 1, "2026-10-05", "AMOUNT", "[" + amountShare(null, "40.00") + "," + amountShare(ana, "50.00") + "]"),
                sharedPurchase(cardA, "100.00", 1, "2026-10-05", "AMOUNT", "[" + amountShare(null, "60.00") + "," + amountShare(ana, "50.00") + "]")};
        for (String body : bodies) {
            createTx(body).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SHARES_INVALID"));
        }
        assertThat(count("transactions")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(postings);
        assertThat(count("card_purchase_shares")).isZero();
    }

    @Test
    @DisplayName("a prévia devolve myAmount, thirdPartyAmount e byPerson por parcela idênticos ao que a criação grava, e não grava nada")
    void previaIgualACriacao() throws Exception {
        String shares = "[" + share(null, 3334) + "," + share(ana, 3333) + "," + share(beto, 3333) + "]";
        String previewBody = "{\"amount\":\"100.04\",\"installments\":3,\"date\":\"2026-10-05\",\"splitMode\":\"BPS\",\"shares\":" + shares + "}";
        long before = count("transactions");
        JsonNode preview = okBody(post("/v1/cards/" + cardA + "/purchase-preview", previewBody));
        assertThat(count("transactions")).isEqualTo(before);

        String id = created(sharedPurchase(cardA, "100.04", 3, "2026-10-05", "BPS", shares)).get("id").asString();
        var rows = byInstallment(id);
        assertThat(preview.get("installments")).hasSize(3);
        for (JsonNode installment : preview.get("installments")) {
            int number = installment.get("number").asInt();
            long mine = cents(installment.get("myAmount"));
            long third = cents(installment.get("thirdPartyAmount"));
            assertThat(rows.get(number)).containsEntry("EXPENSE", mine).containsEntry("RECEIVABLE", third);
            assertThat(mine + third).isEqualTo(cents(installment.get("amount")));
            long byPerson = 0;
            for (JsonNode person : installment.get("byPerson")) {
                byPerson += cents(person.get("amount"));
            }
            assertThat(byPerson).isEqualTo(third);
        }
    }

    @Test
    @DisplayName("fatura paga integralmente: o caixa cai 100,00, o consumo não muda e não nasce despesa nova")
    void pagarAFaturaNaoCriaDespesa() throws Exception {
        created(sharedPurchase(cardA, "100.00", 1, "2026-10-05", "BPS", "[" + share(null, 5000) + "," + share(ana, 5000) + "]"));
        JsonNode summaryBefore = okBody(get("/v1/summaries/2026-10"));
        long cashBefore = book("ACCOUNT");
        long expenseBefore = book("EXPENSE");

        UUID invoice = InvoiceIds.of(UUID.fromString(cardA), LocalDate.of(2026, 10, 25));
        advanceTo(java.time.Instant.parse("2026-10-26T15:00:00Z"));
        mockMvc.perform(idem(post("/v1/invoices/" + invoice + "/payments",
                "{\"accountId\":\"" + accountA + "\",\"amount\":\"100.00\",\"date\":\"2026-10-26\"}"))).andExpect(status().isOk());

        assertThat(book("ACCOUNT")).isEqualTo(cashBefore - 10000L);
        assertThat(book("EXPENSE")).isEqualTo(expenseBefore);
        assertThat(okBody(get("/v1/summaries/2026-10")).get("expenses")).isEqualTo(summaryBefore.get("expenses"));
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("consumo MINE conta só a minha parte e GROSS a parcela cheia; sem rateio os números são os de sempre")
    void consumoMineEGross() throws Exception {
        created(sharedPurchase(cardA, "100.00", 1, "2026-10-05", "BPS", "[" + share(null, 5000) + "," + share(ana, 5000) + "]"));
        created(sharedPurchase(cardA, "40.00", 1, "2026-10-06", null, null));

        JsonNode mine = okBody(get("/v1/summaries/2026-10"));
        JsonNode gross = okBody(get("/v1/summaries/2026-10?view=GROSS"));
        assertThat(mine.get("view").asString()).isEqualTo("MINE");
        assertThat(money(mine, "expenses")).isEqualTo("90.00");
        assertThat(gross.get("view").asString()).isEqualTo("GROSS");
        assertThat(money(gross, "expenses")).isEqualTo("140.00");

        JsonNode dashboard = okBody(get("/v1/dashboard/summary"));
        assertThat(dashboard.get("view").asString()).isEqualTo("MINE");
        assertThat(money(dashboard.get("month"), "consumption")).isEqualTo(money(mine, "expenses"));
        mockMvc.perform(get("/v1/summaries/2026-10?view=NADA")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("compra sem rateio: EXPENSE e CARD cheios, sem RECEIVABLE e sem linhas de rateio (regressão)")
    void semRateioIgualAoDeSempre() throws Exception {
        String id = created(sharedPurchase(cardA, "300.00", 3, "2026-10-05", null, null)).get("id").asString();
        byInstallment(id).forEach((number, books) -> assertThat(books).containsOnlyKeys("EXPENSE", "CARD")
                .containsEntry("EXPENSE", 10000L).containsEntry("CARD", -10000L));
        assertThat(count("card_purchase_shares")).isZero();
        JsonNode body = okBody(get("/v1/transactions/" + id));
        assertThat(body.get("splitMode").isNull() || body.get("splitMode") == null).isTrue();
        assertThat(body.get("thirdPartyAmount") == null || body.get("thirdPartyAmount").isNull()).isTrue();
    }

    @Test
    @DisplayName("pessoa de outro usuário no rateio: 404 e nada é gravado; token ausente: 401")
    void isolamentoEAutenticacao() throws Exception {
        String other = otherUserBearer();
        String strangerPerson = createdBody(postAs(other, "/v1/people", "{\"name\":\"Estranha\"}")).get("id").asString();
        long before = count("transactions");
        createTx(sharedPurchase(cardA, "100.00", 1, "2026-10-05", "BPS", "[" + share(null, 5000) + "," + share(strangerPerson, 5000) + "]"))
                .andExpect(status().is4xxClientError()).andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 422));
        assertThat(count("transactions")).isEqualTo(before);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/transactions")
                .contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        assertThat(ContractValidation.conforms()).isNotNull();
    }

    @Test
    @DisplayName("PR 01 regra 10: resumo, dashboard, entries e relatório por categoria coincidem em MINE (90,00) e em GROSS (140,00); a soma por categoria fecha com o consumo")
    void telasCoincidemNasDuasVisoes() throws Exception {
        created(sharedPurchase(cardA, "100.00", 1, "2026-10-05", "BPS", "[" + share(null, 5000) + "," + share(ana, 5000) + "]"));
        created(sharedPurchase(cardA, "40.00", 1, "2026-10-06", null, null));

        for (String view : new String[] {"MINE", "GROSS"}) {
            long expected = view.equals("MINE") ? 9000L : 14000L;
            JsonNode summary = okBody(get("/v1/summaries/2026-10?view=" + view));
            JsonNode dashboard = okBody(get("/v1/dashboard/summary?view=" + view));
            JsonNode entries = okBody(get("/v1/entries?competence=2026-10&view=" + view));
            JsonNode byCategory = okBody(get("/v1/reports/spending-by-category?month=2026-10&view=" + view));

            assertThat(cents(summary.get("expenses"))).as(view + " summary").isEqualTo(expected);
            assertThat(cents(dashboard.get("month").get("consumption"))).as(view + " dashboard").isEqualTo(expected);
            assertThat(cents(entries.get("totals").get("total"))).as(view + " entries").isEqualTo(expected);
            assertThat(entries.get("view").asString()).isEqualTo(view);
            assertThat(cents(byCategory.get("total"))).as(view + " relatório por categoria").isEqualTo(expected);
            long byCategorySum = 0;
            for (JsonNode category : entries.get("totals").get("byCategory")) {
                byCategorySum += cents(category.get("amount"));
            }
            assertThat(byCategorySum).isEqualTo(expected);
        }
    }
}
