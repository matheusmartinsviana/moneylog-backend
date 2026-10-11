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
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.util.ContractValidation;

@DisplayName("Estorno, cancelamento de parcelas e edição da compra no cartão (T57)")
class CardPurchaseLifecycleIT extends CardItBase {

    private String notebook() throws Exception {
        return created(purchase("1200.00", 6, "2026-10-10", "Notebook")).get("id").asString();
    }

    private ResultActions voidIt(String id, String reason) throws Exception {
        return mockMvc.perform(post("/v1/transactions/" + id + "/void", "{\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions cancelIt(String id, String reason) throws Exception {
        return mockMvc.perform(post("/v1/transactions/" + id + "/cancel-installments", "{\"reason\":\"" + reason + "\"}"));
    }

    private String editBody(String amount, String date, int installments, String categoryId, String description, int version) {
        return "{\"type\":\"CARD_PURCHASE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description
                + "\",\"notes\":\"obs\",\"cardId\":\"" + card + "\",\"categoryId\":\"" + categoryId + "\",\"installments\":" + installments
                + ",\"version\":" + version + "}";
    }

    private long activePostings(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE transaction_id = ? AND active", Long.class, UUID.fromString(id));
    }

    @Test
    @DisplayName("CA1: compra com todas as parcelas em faturas FUTURE/OPEN: estorno com motivo vira VOIDED, parcelas e lançamentos inativos, o limite volta a 10.000,00 e a fatura corrente perde 200,00")
    void deveEstornarACompraEmFaturaAberta() throws Exception {
        String id = notebook();
        created(purchase("1083.00", 1, "2026-10-12", "Mercado"));
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("7717.00");
        assertThat(okBody(get("/v1/transactions/" + id)).get("voidable").asBoolean()).isTrue();

        voidIt(id, "errei o valor").andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("VOIDED")).andExpect(jsonPath("$.voidable").value(false))
                .andExpect(jsonPath("$.editable").value(false)).andExpect(jsonPath("$.voidReason").value("errei o valor"))
                .andExpect(jsonPath("$.installments[0].status").value("CANCELED")).andExpect(jsonPath("$.installments[5].status").value("CANCELED"))
                .andExpect(jsonPath("$.impact.consumption.length()").value(0)).andExpect(jsonPath("$.impact.commitment.amount").value("0.00"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(activePostings(id)).isZero();
        JsonNode now = cardNow();
        assertThat(money(now, "availableLimit")).isEqualTo("8917.00");
        assertThat(money(now, "committedFuture")).isEqualTo("0.00");
        assertThat(money(now.get("currentInvoice"), "total")).isEqualTo("1083.00");
        assertThat(invoice(NOV_CLOSING).get("items")).hasSize(1);
        voidIt(id, "de novo").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_VOIDED"))
                .andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA2: com a parcela 1 em fatura CLOSED (30/10): voidable false com voidBlockedBy TRANSACTION_IN_CLOSED_INVOICE e o POST /void é 422 sem mudar nada")
    void deveRecusarEstornoComParcelaEmFaturaFechada() throws Exception {
        String id = notebook();
        advanceTo(OCT_30);

        JsonNode tx = okBody(get("/v1/transactions/" + id));
        assertThat(tx.get("voidable").asBoolean()).isFalse();
        assertThat(tx.get("voidBlockedBy").asString()).isEqualTo("TRANSACTION_IN_CLOSED_INVOICE");
        assertThat(tx.get("editable").asBoolean()).isTrue();
        voidIt(id, "x").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_IN_CLOSED_INVOICE"))
                .andExpect(ContractValidation.conforms());

        assertThat(okBody(get("/v1/transactions/" + id)).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(activePostings(id)).isEqualTo(12);
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("8800.00");
        assertThat(okBody(get("/v1/transactions?type=CARD_PURCHASE")).get("items").get(0).get("voidBlockedBy").asString())
                .isEqualTo("TRANSACTION_IN_CLOSED_INVOICE");
    }

    @Test
    @DisplayName("compra com reembolso ativo não é estornada (422 TRANSACTION_HAS_ACTIVE_REFUNDS) nem troca de categoria (422 REFUND_CATEGORY_LOCKED); estornando o reembolso, volta a poder")
    void deveProtegerACompraComReembolsoAtivo() throws Exception {
        String id = created(purchase("200.00", 1, "2026-10-10", "Fone")).get("id").asString();
        String refund = created(cardRefund("50.00", "2026-10-10", "Estorno")).get("id").asString();
        String linked = created("{\"type\":\"REFUND\",\"amount\":\"20.00\",\"date\":\"2026-10-10\",\"description\":\"Parcial\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + tech + "\",\"refundOfTransactionId\":\"" + id + "\"}").get("id").asString();

        JsonNode tx = okBody(get("/v1/transactions/" + id));
        assertThat(tx.get("voidBlockedBy").asString()).isEqualTo("HAS_ACTIVE_REFUNDS");
        voidIt(id, "x").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_HAS_ACTIVE_REFUNDS"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(put("/v1/transactions/" + id, editBody("200.00", "2026-10-10", 1, food, "Fone", 0))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_CATEGORY_LOCKED")).andExpect(ContractValidation.conforms());

        voidIt(linked, "desfeito").andExpect(status().isOk()).andExpect(ContractValidation.conforms());
        assertThat(okBody(get("/v1/transactions/" + id)).get("voidable").asBoolean()).isTrue();
        voidIt(id, "agora sim").andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("VOIDED"));
        assertThat(okBody(get("/v1/transactions/" + refund)).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("CA5: estornar um crédito em fatura OPEN funciona e a fatura volta a somar; em fatura CLOSED/PAID é 422 TRANSACTION_IN_CLOSED_INVOICE")
    void deveEstornarCreditoSoEmFaturaAberta() throws Exception {
        created(purchase("200.00", 1, "2026-10-10", "Fone"));
        String open = created(cardRefund("50.00", "2026-10-10", "Aberto")).get("id").asString();
        assertThat(money(invoice(NOV_CLOSING), "total")).isEqualTo("150.00");
        assertThat(okBody(get("/v1/transactions/" + open)).get("voidable").asBoolean()).isTrue();

        voidIt(open, "banco desfez").andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("VOIDED"));

        assertThat(money(invoice(NOV_CLOSING), "total")).isEqualTo("200.00");
        assertThat(activePostings(open)).isZero();
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("9800.00");
        String closed = created(cardRefund("30.00", "2026-10-10", "Vai fechar")).get("id").asString();
        advanceTo(OCT_30);
        assertThat(okBody(get("/v1/transactions/" + closed)).get("voidBlockedBy").asString()).isEqualTo("TRANSACTION_IN_CLOSED_INVOICE");
        voidIt(closed, "x").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_IN_CLOSED_INVOICE"))
                .andExpect(ContractValidation.conforms());
        seedPaymentAndCheckPaidInvoiceRefusal(closed);
    }

    private void seedPaymentAndCheckPaidInvoiceRefusal(String refundId) throws Exception {
        paid(NOV_CLOSING, "170.00", "2026-10-30");
        voidIt(refundId, "x").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_IN_CLOSED_INVOICE"));
    }

    @Test
    @DisplayName("CA3: com a parcela 1 em fatura CLOSED (30/10), cancelar as parcelas restantes deixa 2..6 CANCELED e a 1 como está; devolve 1.000,00 de limite e zera o comprometido; o motivo vai para a revisão")
    void deveCancelarAsParcelasRestantes() throws Exception {
        String id = notebook();
        advanceTo(OCT_30);
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("8800.00");

        JsonNode canceled = body(cancelIt(id, "desisti do plano").andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.installments[0].status").value("PENDING"))
                .andExpect(jsonPath("$.installments[1].status").value("CANCELED")).andExpect(jsonPath("$.installments[5].status").value("CANCELED"))
                .andExpect(jsonPath("$.impact.commitment.amount").value("0.00")).andExpect(jsonPath("$.impact.consumption.length()").value(1))
                .andExpect(jsonPath("$.version").value(1)).andReturn());

        assertThat(canceled.get("installments").get(0).get("amount").get("amount").asString()).isEqualTo("200.00");
        assertThat(activePostings(id)).isEqualTo(2);
        JsonNode now = cardNow();
        assertThat(money(now, "availableLimit")).isEqualTo("9800.00");
        assertThat(money(now, "committedFuture")).isEqualTo("0.00");
        assertThat(okBody(get("/v1/cards/" + card + "/future-installments")).get("byInvoice")).isEmpty();
        assertThat(money(invoice(NOV_CLOSING), "total")).isEqualTo("200.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_installments WHERE transaction_id = ? AND canceled_at IS NOT NULL", Long.class,
                UUID.fromString(id))).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE transaction_id = ? AND NOT active AND deactivation_reason = 'CANCEL'",
                Long.class, UUID.fromString(id))).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT snapshot ->> 'changeReason' FROM transaction_revisions WHERE transaction_id = ?", String.class,
                UUID.fromString(id))).isEqualTo("desisti do plano");
        assertThat(jdbc.queryForObject("SELECT snapshot ->> 'change' FROM transaction_revisions WHERE transaction_id = ?", String.class,
                UUID.fromString(id))).isEqualTo("CANCEL_INSTALLMENTS");

        cancelIt(id, "de novo").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CARD_NO_INSTALLMENTS_TO_CANCEL"))
                .andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA3: compra estornada é 422 TRANSACTION_ALREADY_VOIDED; movimentação que não é compra no cartão é 422 TRANSACTION_NOT_CANCELABLE; inexistente é 404; sem motivo é 400")
    void deveRecusarCancelamentoInvalido() throws Exception {
        String id = notebook();
        String expense = created("{\"type\":\"EXPENSE\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"Café\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}").get("id").asString();

        cancelIt(expense, "x").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_NOT_CANCELABLE"))
                .andExpect(ContractValidation.conforms());
        cancelIt(UUID.randomUUID().toString(), "x").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/transactions/" + id + "/cancel-installments", "{\"reason\":\" \"}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/transactions/" + id + "/cancel-installments", "{}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        voidIt(id, "errei").andExpect(status().isOk());
        cancelIt(id, "x").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_VOIDED"))
                .andExpect(ContractValidation.conforms());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_revisions WHERE transaction_id = ?", Long.class, UUID.fromString(id))).isZero();
    }

    @Test
    @DisplayName("dois cancelamentos simultâneos da mesma compra: um vence (200) e o outro é 422 CARD_NO_INSTALLMENTS_TO_CANCEL; nunca 500 nem revisão duplicada")
    void deveSerializarCancelamentosConcorrentes() throws Exception {
        String id = notebook();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<Integer>> results = List.of(pool.submit(() -> {
                barrier.await();
                return cancelIt(id, "um").andReturn().getResponse().getStatus();
            }), pool.submit(() -> {
                barrier.await();
                return cancelIt(id, "dois").andReturn().getResponse().getStatus();
            }));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 422);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_installments WHERE transaction_id = ? AND canceled_at IS NOT NULL", Long.class,
                UUID.fromString(id))).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_revisions WHERE transaction_id = ?", Long.class, UUID.fromString(id))).isEqualTo(1);
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("10000.00");
    }

    @Test
    @DisplayName("CA4: PUT muda descrição, categoria e observação: 200, versão + 1, a anterior em transaction_revisions, parcelas e faturas iguais e os lançamentos de despesa só trocam de categoria")
    void deveEditarAsCamposLivres() throws Exception {
        String id = notebook();
        JsonNode before = okBody(get("/v1/transactions/" + id));

        JsonNode edited = body(mockMvc.perform(put("/v1/transactions/" + id, editBody("1200.00", "2026-10-10", 6, food, "Notebook Dell", 0)))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.description").value("Notebook Dell")).andExpect(jsonPath("$.notes").value("obs"))
                .andExpect(jsonPath("$.category.name").value("Alimentação")).andReturn());

        assertThat(edited.get("installments")).isEqualTo(before.get("installments"));
        assertThat(edited.get("impact").get("consumption")).isEqualTo(before.get("impact").get("consumption"));
        assertThat(money(edited.get("impact"), "commitment")).isEqualTo("1000.00");
        assertThat(jdbc.queryForObject("SELECT snapshot ->> 'description' FROM transaction_revisions WHERE transaction_id = ? AND revision = 1",
                String.class, UUID.fromString(id))).isEqualTo("Notebook");
        assertThat(jdbc.queryForObject("SELECT snapshot ->> 'categoryId' FROM transaction_revisions WHERE transaction_id = ?", String.class,
                UUID.fromString(id))).isEqualTo(tech);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE transaction_id = ? AND active AND book_type = 'EXPENSE' AND book_id = ?",
                Long.class, UUID.fromString(id), UUID.fromString(food))).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE transaction_id = ? AND NOT active AND deactivation_reason = 'EDIT'",
                Long.class, UUID.fromString(id))).isEqualTo(12);
        assertThat(activePostings(id)).isEqualTo(12);
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("8800.00");
        JsonNode consumption = okBody(get("/v1/entries?competence=2026-10&categoryId=" + food));
        assertThat(money(consumption.get("totals"), "total")).isEqualTo("200.00");
        assertThat(okBody(get("/v1/entries?competence=2026-10&categoryId=" + tech)).get("items")).isEmpty();
        mockMvc.perform(put("/v1/transactions/" + id, editBody("1200.00", "2026-10-10", 6, food, "Outra", 0))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA4: PUT mudando amount, date, installments ou cardId é 422 TRANSACTION_NOT_EDITABLE e nada muda; categoria de receita é 422; sem version ou campo obrigatório é 400")
    void deveRecusarMudarOQueNaoMuda() throws Exception {
        String id = notebook();
        String otherCard = createCard("Outro", 20, 1);

        for (String body : List.of(editBody("1300.00", "2026-10-10", 6, tech, "x", 0), editBody("1200.00", "2026-10-11", 6, tech, "x", 0),
                editBody("1200.00", "2026-10-10", 5, tech, "x", 0), editBody("1200.00", "2026-10-10", 6, tech, "x", 0).replace(card, otherCard))) {
            mockMvc.perform(put("/v1/transactions/" + id, body)).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE")).andExpect(ContractValidation.conforms());
        }
        mockMvc.perform(put("/v1/transactions/" + id, editBody("1200.00", "2026-10-10", 6, income, "x", 0))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_KIND_MISMATCH")).andExpect(ContractValidation.conforms());
        mockMvc.perform(put("/v1/transactions/" + id, editBody("1200.00", "2026-10-10", 6, tech, "x", 0).replace(",\"version\":0", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("version")).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(put("/v1/transactions/" + id, "{\"type\":\"CARD_PURCHASE\",\"version\":0}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(put("/v1/transactions/" + id, editBody("1200.00", "2026-10-10", 6, tech, "x", 0).replace("\"type\":\"CARD_PURCHASE\"",
                "\"type\":\"EXPENSE\""))).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());

        JsonNode unchanged = okBody(get("/v1/transactions/" + id));
        assertThat(unchanged.get("version").asInt()).isZero();
        assertThat(unchanged.get("description").asString()).isEqualTo("Notebook");
        assertThat(count("transaction_revisions")).isZero();
        assertThat(activePostings(id)).isEqualTo(12);
    }

    @Test
    @DisplayName("CA4: crédito no cartão e pagamento de fatura não são editáveis (422); compra estornada também não")
    void deveRecusarEditarOutrasMovimentacoesDeCartao() throws Exception {
        String id = notebook();
        String refund = created(cardRefund("50.00", "2026-10-10", "Estorno")).get("id").asString();
        advanceTo(NOV_05);
        String payment = paid(NOV_CLOSING, "100.00", "2026-11-05").get("payments").get(0).get("transactionId").asString();

        mockMvc.perform(put("/v1/transactions/" + refund, "{\"type\":\"REFUND\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + tech + "\",\"version\":0}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE")).andExpect(ContractValidation.conforms());
        mockMvc.perform(put("/v1/transactions/" + payment, "{\"type\":\"INVOICE_PAYMENT\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE")).andExpect(ContractValidation.conforms());
        assertThat(okBody(get("/v1/transactions/" + payment)).get("editable").asBoolean()).isFalse();
        assertThat(okBody(get("/v1/transactions/" + refund)).get("editable").asBoolean()).isFalse();
        assertThat(id).isNotBlank();
    }

    @Test
    @DisplayName("CA7: com o cartão arquivado, editar continua permitido (e o estorno segue só a guarda de fatura)")
    void deveEditarComCartaoArquivado() throws Exception {
        String id = created(purchase("100.00", 1, "2026-10-10", "Livro")).get("id").asString();
        advanceTo(OCT_30);
        paid(NOV_CLOSING, "100.00", "2026-10-30");
        mockMvc.perform(post("/v1/cards/" + card + "/archive", "")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(put("/v1/transactions/" + id, editBody("100.00", "2026-10-10", 1, food, "Livro novo", 0))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.description").value("Livro novo"));
        voidIt(id, "x").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_IN_CLOSED_INVOICE"))
                .andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("isolamento: outro usuário não estorna, não cancela nem edita a compra (404)")
    void deveIsolarUsuarios() throws Exception {
        String id = notebook();
        UUID other = UUID.randomUUID();
        committed(() -> com.moneylog.util.SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        String otherBearer = auth.bearer(other);

        mockMvc.perform(postAs(otherBearer, "/v1/transactions/" + id + "/void", "{\"reason\":\"x\"}")).andExpect(status().isNotFound());
        mockMvc.perform(postAs(otherBearer, "/v1/transactions/" + id + "/cancel-installments", "{\"reason\":\"x\"}")).andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/v1/transactions/" + id)
                .header("Authorization", otherBearer).contentType("application/json").content(editBody("1200.00", "2026-10-10", 6, tech, "x", 0)))
                .andExpect(status().isNotFound());
        assertThat(okBody(get("/v1/transactions/" + id)).get("status").asString()).isEqualTo("ACTIVE");
    }
}
