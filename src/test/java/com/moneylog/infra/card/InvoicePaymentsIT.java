package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("Pagamento de fatura (T55)")
class InvoicePaymentsIT extends CardItBase {

    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    @Test
    @DisplayName("CA1: pagar 1.283,00 da Nubank: 200 PAID, postings CARD +1.283 / ACCOUNT −1.283 sem EXPENSE, a Nubank cai 1.283, consumo vazio, parcelas PAID")
    void devePagarAFatura() throws Exception {
        twoPurchases();
        advanceTo(NOV_05);

        JsonNode invoice = paid(NOV_CLOSING, "1283.00", "2026-11-05");

        assertThat(invoice.get("status").asString()).isEqualTo("PAID");
        assertThat(money(invoice, "paid")).isEqualTo("1283.00");
        assertThat(money(invoice, "outstanding")).isEqualTo("0.00");
        assertThat(invoice.get("payments")).hasSize(1);
        JsonNode payment = invoice.get("payments").get(0);
        assertThat(payment.get("accountId").asString()).isEqualTo(nubank);
        assertThat(payment.get("date").asString()).isEqualTo("2026-11-05");
        UUID paymentId = UUID.fromString(payment.get("transactionId").asString());
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE transaction_id = ? AND book_type = 'CARD' AND active",
                Long.class, paymentId)).isEqualTo(128_300L);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE transaction_id = ? AND book_type = 'ACCOUNT' AND active",
                Long.class, paymentId)).isEqualTo(-128_300L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE transaction_id = ? AND book_type = 'EXPENSE'", Long.class, paymentId))
                .isZero();
        assertThat(money(account(nubank), "currentBalance")).isEqualTo("18717.00");

        JsonNode tx = okBody(get("/v1/transactions/" + paymentId));
        assertThat(tx.get("type").asString()).isEqualTo("INVOICE_PAYMENT");
        assertThat(tx.get("card").get("id").asString()).isEqualTo(card);
        assertThat(tx.get("account").get("id").asString()).isEqualTo(nubank);
        assertThat(tx.get("category").isNull()).isTrue();
        assertThat(tx.get("impact").get("consumption")).isEmpty();
        assertThat(money(tx.get("impact"), "cash")).isEqualTo("-1283.00");
        assertThat(money(tx.get("impact"), "netWorth")).isEqualTo("0.00");
        assertThat(tx.get("invoice").get("id").asString()).isEqualTo(invoiceId(NOV_CLOSING).toString());
        assertThat(tx.get("voidable").asBoolean()).isTrue();
        assertThat(tx.get("editable").asBoolean()).isFalse();
        JsonNode list = okBody(get("/v1/transactions?type=INVOICE_PAYMENT"));
        assertThat(list.get("items")).hasSize(1);
        assertThat(money(list.get("totals").get("cashFlow"), "out")).isEqualTo("1283.00");
        JsonNode consumption = okBody(get("/v1/entries?competence=2026-10&lens=CONSUMPTION"));
        assertThat(consumption.get("items")).hasSize(2);
        assertThat(money(consumption.get("totals"), "total")).isEqualTo("1283.00");
        for (JsonNode purchase : okBody(get("/v1/transactions?type=CARD_PURCHASE")).get("items")) {
            assertThat(purchase.get("installments").get(0).get("status").asString()).isEqualTo("PAID");
        }
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("9000.00");
    }

    @Test
    @DisplayName("CA2: 500,00 e depois 783,00: o disponível sobe 500 e depois 783; a fatura atrasa e só então fica PAID")
    void devePagarEmDuasVezes() throws Exception {
        twoPurchases();
        advanceTo(NOV_05.plusSeconds(86_400 * 2L));
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("7717.00");

        JsonNode first = paid(NOV_CLOSING, "500.00", "2026-11-07");
        assertThat(first.get("status").asString()).isEqualTo("OVERDUE");
        assertThat(money(first, "outstanding")).isEqualTo("783.00");
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("8217.00");

        JsonNode second = paid(NOV_CLOSING, "783.00", "2026-11-07");
        assertThat(second.get("status").asString()).isEqualTo("PAID");
        assertThat(second.get("payments")).hasSize(2);
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("9000.00");
    }

    @Test
    @DisplayName("CA3: 1.300,00 a maior vira creditCarry 17,00 e abate a seguinte; 300,00 numa fatura OPEN de 200,00 (antecipação) é aceito")
    void deveAceitarAMaiorEAntecipacao() throws Exception {
        twoPurchases();
        advanceTo(NOV_05);
        JsonNode over = paid(NOV_CLOSING, "1300.00", "2026-11-05");
        assertThat(over.get("status").asString()).isEqualTo("PAID");
        assertThat(money(over, "creditCarry")).isEqualTo("17.00");
        assertThat(money(invoice(LocalDate.of(2026, 11, 25)), "carryIn")).isEqualTo("17.00");
        assertThat(money(invoice(LocalDate.of(2026, 11, 25)), "outstanding")).isEqualTo("183.00");
    }

    @Test
    @DisplayName("CA3: antecipação de 300,00 numa fatura OPEN de 200,00 em 20/10: aceita, creditCarry 100,00")
    void deveAntecipar() throws Exception {
        created(purchase("200.00", 1, "2026-10-10", "Mercado"));
        advanceTo(java.time.Instant.parse("2026-10-20T15:00:00Z"));

        JsonNode prepaid = paid(NOV_CLOSING, "300.00", "2026-10-20");

        assertThat(prepaid.get("status").asString()).isEqualTo("OPEN");
        assertThat(money(prepaid, "creditCarry")).isEqualTo("100.00");
        assertThat(money(prepaid, "outstanding")).isEqualTo("0.00");
        advanceTo(NOV_05);
        assertThat(invoice(NOV_CLOSING).get("status").asString()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("CA4: fatura FUTURE ou PAID, conta que não é CASH, arquivada, valor zero, acima do teto, ou corpo inválido: recusado e nada gravado")
    void deveRecusarPagamentosInvalidos() throws Exception {
        twoPurchases();
        advanceTo(NOV_05);
        String invest = body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"XP Invest\",\"type\":\"INVESTMENT\","
                + "\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        String archived = body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"Velha\",\"type\":\"CHECKING\","
                + "\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        mockMvc.perform(post("/v1/accounts/" + archived + "/archive", "")).andExpect(status().isOk());
        long before = count("transactions");
        UUID closed = invoiceId(NOV_CLOSING);

        payInvoice(invoiceId(LocalDate.of(2026, 12, 25)), nubank, "10.00", "2026-11-05").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_PAYABLE")).andExpect(ContractValidation.conforms());
        payInvoice(closed, invest, "10.00", "2026-11-05").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_ACCOUNT_NOT_CASH")).andExpect(ContractValidation.conforms());
        payInvoice(closed, archived, "10.00", "2026-11-05").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED")).andExpect(ContractValidation.conforms());
        payInvoice(closed, nubank, "0.00", "2026-11-05").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_AMOUNT_NOT_POSITIVE")).andExpect(ContractValidation.conforms());
        payInvoice(closed, nubank, "1000000000.00", "2026-11-05").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AMOUNT_ABOVE_LIMIT")).andExpect(ContractValidation.conforms());
        payInvoice(closed, nubank, "-5.00", "2026-11-05").andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        payInvoice(closed, nubank, "10.005", "2026-11-05").andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/invoices/" + closed + "/payments", "{\"accountId\":\"" + nubank + "\",\"date\":\"2026-11-05\"}"))
                .andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/invoices/" + closed + "/payments", paymentBody(nubank, "10.00", "2026-11-05")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/invoices/" + closed + "/payments", "{\"accountId\":\"" + nubank + "\",\"amount\":\"1.00\",\"date\":\"2026-11-05\",\"x\":1}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD")).andExpect(ContractValidation.responseOnly());

        assertThat(count("transactions")).isEqualTo(before);
        paid(NOV_CLOSING, "1283.00", "2026-11-05");
        payInvoice(closed, nubank, "10.00", "2026-11-05").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_PAYABLE")).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA5: o mesmo Idempotency-Key e o mesmo corpo não pagam duas vezes; outro corpo é 409 IDEMPOTENCY_KEY_REUSED; a chave de compra não vale para pagamento")
    void deveSerIdempotente() throws Exception {
        twoPurchases();
        advanceTo(NOV_05);
        String key = UUID.randomUUID().toString();
        String json = paymentBody(nubank, "500.00", "2026-11-05");
        UUID closed = invoiceId(NOV_CLOSING);

        JsonNode first = body(mockMvc.perform(post("/v1/invoices/" + closed + "/payments", json).header("Idempotency-Key", key))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        JsonNode second = body(mockMvc.perform(post("/v1/invoices/" + closed + "/payments", json).header("Idempotency-Key", key))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());

        assertThat(second.get("payments")).hasSize(1);
        assertThat(second.get("payments").get(0).get("transactionId").asString())
                .isEqualTo(first.get("payments").get(0).get("transactionId").asString());
        assertThat(money(second, "outstanding")).isEqualTo("783.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ? AND type = 'INVOICE_PAYMENT'", Long.class, userId)).isEqualTo(1);
        mockMvc.perform(post("/v1/invoices/" + closed + "/payments", paymentBody(nubank, "501.00", "2026-11-05")).header("Idempotency-Key", key))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/transactions", purchase("10.00", 1, "2026-11-05", "Outra")).header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ? AND type = 'INVOICE_PAYMENT'", Long.class, userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5: dois pagamentos simultâneos de chaves diferentes na mesma fatura são aceitos em série e o outstanding final é consistente")
    void deveSerializarPagamentosConcorrentes() throws Exception {
        twoPurchases();
        advanceTo(NOV_05);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<Integer>> results = List.of(pool.submit(() -> concurrentPayment(barrier, "500.00")),
                    pool.submit(() -> concurrentPayment(barrier, "400.00")));
            assertThat(results.get(0).get()).isEqualTo(200);
            assertThat(results.get(1).get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }

        JsonNode invoice = invoice(NOV_CLOSING);
        assertThat(invoice.get("payments")).hasSize(2);
        assertThat(money(invoice, "paid")).isEqualTo("900.00");
        assertThat(money(invoice, "outstanding")).isEqualTo("383.00");
        assertThat(money(account(nubank), "currentBalance")).isEqualTo("19100.00");
    }

    private int concurrentPayment(CyclicBarrier barrier, String amount) throws Exception {
        barrier.await();
        return payInvoice(invoiceId(NOV_CLOSING), nubank, amount, "2026-11-05").andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("CA5: pagamento × compra tardia simultâneos (trava do cartão): a compra nunca cai numa fatura que o pagamento acabou de quitar")
    void devePagarEComprarSemItemEmFaturaPaga() throws Exception {
        for (int round = 0; round < 8; round++) {
            advanceTo(java.time.Instant.parse("2026-10-10T15:00:00Z"));
            String roundCard = createCard("Concorrente " + round, 25, 5);
            created(purchaseOn(roundCard, tech, "1283.00", 1, "2026-10-10", "Mercado"));
            advanceTo(OCT_30);
            UUID closed = com.moneylog.core.card.domain.InvoiceIds.of(UUID.fromString(roundCard), NOV_CLOSING);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<String> payment = pool.submit(() -> {
                    barrier.await();
                    var response = payInvoice(closed, nubank, "1283.00", "2026-10-30").andReturn().getResponse();
                    return response.getStatus() + " " + response.getContentAsString();
                });
                Future<String> late = pool.submit(() -> {
                    barrier.await();
                    var response = createTx(purchaseOn(roundCard, tech, "90.00", 1, "2026-10-20", "Tardia")).andReturn().getResponse();
                    return response.getStatus() + " " + response.getContentAsString();
                });
                assertThat(payment.get()).startsWith("200 ");
                assertThat(late.get()).startsWith("201 ");
            } finally {
                pool.shutdownNow();
            }

            JsonNode invoice = okBody(get("/v1/invoices/" + closed));
            boolean purchaseFirst = invoice.get("items").size() == 2;
            if (purchaseFirst) {
                assertThat(invoice.get("items").get(1).get("lateEntry").asBoolean()).isTrue();
                assertThat(money(invoice, "outstanding")).isEqualTo("90.00");
                assertThat(invoice.get("status").asString()).isEqualTo("CLOSED");
            } else {
                assertThat(invoice.get("status").asString()).isEqualTo("PAID");
                assertThat(money(invoice, "total")).isEqualTo("1283.00");
                JsonNode next = okBody(get("/v1/invoices/" + com.moneylog.core.card.domain.InvoiceIds.of(UUID.fromString(roundCard), LocalDate.of(2026, 11, 25))));
                assertThat(money(next, "total")).isEqualTo("90.00");
                assertThat(next.get("items").get(0).get("lateEntry").asBoolean()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("CA6: estornar o INVOICE_PAYMENT recalcula o status (PAID → OVERDUE/CLOSED conforme a data) e o limite disponível volta")
    void deveEstornarOPagamento() throws Exception {
        twoPurchases();
        advanceTo(NOV_05);
        JsonNode invoice = paid(NOV_CLOSING, "1283.00", "2026-11-05");
        String paymentId = invoice.get("payments").get(0).get("transactionId").asString();
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("9000.00");

        mockMvc.perform(post("/v1/transactions/" + paymentId + "/void", "{\"reason\":\"conta errada\"}")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.voidable").value(false));

        JsonNode reopened = invoice(NOV_CLOSING);
        assertThat(reopened.get("status").asString()).isEqualTo("CLOSED");
        assertThat(money(reopened, "outstanding")).isEqualTo("1283.00");
        assertThat(reopened.get("payments")).isEmpty();
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("7717.00");
        assertThat(money(account(nubank), "currentBalance")).isEqualTo("20000.00");
        advanceTo(NOV_05.plusSeconds(86_400 * 3L));
        assertThat(invoice(NOV_CLOSING).get("status").asString()).isEqualTo("OVERDUE");
        mockMvc.perform(post("/v1/transactions/" + paymentId + "/void", "{\"reason\":\"de novo\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_VOIDED")).andExpect(ContractValidation.conforms());
        assertThat(paid(NOV_CLOSING, "1283.00", "2026-11-08").get("status").asString()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("CA7: fatura ou conta de outro usuário é 404 e nada é gravado")
    void deveIsolarUsuarios() throws Exception {
        twoPurchases();
        advanceTo(NOV_05);
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@example.com"));
        String otherBearer = auth.bearer(other);
        long before = count("transactions");

        mockMvc.perform(postAs(otherBearer, "/v1/invoices/" + invoiceId(NOV_CLOSING) + "/payments", paymentBody(nubank, "10.00", "2026-11-05"))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(getAs(otherBearer, "/v1/invoices/" + invoiceId(NOV_CLOSING) + "/payment-preview?amount=10.00"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"));
        String foreignAccount = body(mockMvc.perform(postAs(otherBearer, "/v1/accounts", "{\"name\":\"Dele\",\"type\":\"CHECKING\","
                + "\"openingBalance\":{\"amount\":\"100.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        payInvoice(invoiceId(NOV_CLOSING), foreignAccount, "10.00", "2026-11-05").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND")).andExpect(ContractValidation.conforms());
        assertThat(count("transactions")).isEqualTo(before);
    }

    @Test
    @DisplayName("CA8: a prévia de 500,00 é PARTIAL (783,00 / 0,00), 1.300,00 é OVERPAY (0,00 / 17,00), 1.283,00 é FULL; igual ao pagamento real e sem gravar")
    void devePrevisualizarOPagamento() throws Exception {
        twoPurchases();
        advanceTo(NOV_05);
        UUID closed = invoiceId(NOV_CLOSING);
        long transactionsBefore = count("transactions");
        long postingsBefore = count("ledger_postings");
        long keysBefore = count("idempotency_keys");

        JsonNode partial = okBody(get("/v1/invoices/" + closed + "/payment-preview?amount=500.00"));
        assertThat(partial.get("kind").asString()).isEqualTo("PARTIAL");
        assertThat(money(partial, "outstandingAfter")).isEqualTo("783.00");
        assertThat(money(partial, "creditCarryAfter")).isEqualTo("0.00");
        JsonNode over = okBody(get("/v1/invoices/" + closed + "/payment-preview?amount=1300.00"));
        assertThat(over.get("kind").asString()).isEqualTo("OVERPAY");
        assertThat(money(over, "outstandingAfter")).isEqualTo("0.00");
        assertThat(money(over, "creditCarryAfter")).isEqualTo("17.00");
        assertThat(okBody(get("/v1/invoices/" + closed + "/payment-preview?amount=1283.00")).get("kind").asString()).isEqualTo("FULL");
        assertThat(count("transactions")).isEqualTo(transactionsBefore);
        assertThat(count("ledger_postings")).isEqualTo(postingsBefore);
        assertThat(count("idempotency_keys")).isEqualTo(keysBefore);

        JsonNode real = paid(NOV_CLOSING, "1300.00", "2026-11-05");
        assertThat(money(real, "outstanding")).isEqualTo(money(over, "outstandingAfter"));
        assertThat(money(real, "creditCarry")).isEqualTo(money(over, "creditCarryAfter"));
    }

    @Test
    @DisplayName("CA8: prévia em fatura OPEN é PREPAY; FUTURE ou PAID é 422 INVOICE_NOT_PAYABLE; valor ausente, malformado ou zero é recusado")
    void devePrevisualizarAntecipacaoERecusar() throws Exception {
        twoPurchases();
        UUID open = invoiceId(NOV_CLOSING);
        JsonNode prepay = okBody(get("/v1/invoices/" + open + "/payment-preview?amount=2000.00"));
        assertThat(prepay.get("kind").asString()).isEqualTo("PREPAY");
        assertThat(money(prepay, "creditCarryAfter")).isEqualTo("717.00");
        mockMvc.perform(get("/v1/invoices/" + invoiceId(LocalDate.of(2026, 12, 25)) + "/payment-preview?amount=1.00"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVOICE_NOT_PAYABLE"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(get("/v1/invoices/" + open + "/payment-preview")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/invoices/" + open + "/payment-preview?amount=abc")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/invoices/" + open + "/payment-preview?amount=0.00")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_AMOUNT_NOT_POSITIVE")).andExpect(ContractValidation.conforms());
        advanceTo(NOV_05);
        paid(NOV_CLOSING, "1283.00", "2026-11-05");
        mockMvc.perform(get("/v1/invoices/" + open + "/payment-preview?amount=1.00")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_PAYABLE")).andExpect(ContractValidation.conforms());
    }
}
