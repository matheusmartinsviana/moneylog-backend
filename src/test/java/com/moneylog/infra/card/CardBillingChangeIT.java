package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("Mudança de fechamento e vencimento do cartão (T58)")
class CardBillingChangeIT extends CardItBase {

    private String notebookId;

    private JsonNode preview(int closing, int due) throws Exception {
        return okBody(get("/v1/cards/" + card + "/billing-change-preview?closingDay=" + closing + "&dueDay=" + due));
    }

    private String putBody(int closing, int due, String confirmed, int version) {
        return "{\"name\":\"Roxinho\",\"institution\":\"Nubank\",\"limit\":{\"amount\":\"10000.00\",\"currency\":\"BRL\"},\"closingDay\":" + closing
                + ",\"dueDay\":" + due + ",\"defaultPaymentAccountId\":\"" + nubank + "\""
                + (confirmed == null ? "" : ",\"confirmedEffectiveFrom\":\"" + confirmed + "\"") + ",\"version\":" + version + "}";
    }

    private ResultActions change(int closing, int due, String confirmed, int version) throws Exception {
        return mockMvc.perform(put("/v1/cards/" + card, putBody(closing, due, confirmed, version)));
    }

    private JsonNode confirmedChange(int closing, int due, int version) throws Exception {
        String confirmed = preview(closing, due).get("effectiveFromClosingDate").asString();
        return body(change(closing, due, confirmed, version).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private void notebook() throws Exception {
        notebookId = created(purchase("1200.00", 6, "2026-10-10", "Notebook")).get("id").asString();
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT id, closing_date, period_start, due_date, reference_month FROM credit_card_invoices WHERE card_id = ? "
                + "ORDER BY closing_date", UUID.fromString(card));
    }

    private List<String> closings() {
        return rows().stream().map(r -> r.get("closing_date").toString()).toList();
    }

    private JsonNode consumptionByCompetence() throws Exception {
        var node = objectMapper.createObjectNode();
        for (String competence : List.of("2026-10", "2026-11", "2026-12", "2027-01", "2027-02", "2027-03", "2027-04")) {
            node.put(competence, money(okBody(get("/v1/entries?competence=" + competence)).get("totals"), "total"));
        }
        return node;
    }

    @Test
    @DisplayName("CA1: fechamento 25 e vencimento 5 → 28: a corrente vence 05/11 como antes, a seguinte fecha 25/11 e vence 28/11 (ref. 2026-11), as parcelas ficam nos mesmos ciclos e o consumo por competência não muda; as colunas das linhas acompanham o calendário")
    void deveMudarSoOVencimento() throws Exception {
        notebook();
        JsonNode consumptionBefore = consumptionByCompetence();
        JsonNode installmentsBefore = okBody(get("/v1/transactions/" + notebookId)).get("installments");
        JsonNode preview = preview(25, 28);
        assertThat(preview.get("effectiveFromClosingDate").asString()).isEqualTo("2026-11-25");
        assertThat(preview.get("firstNewInvoice").get("closingDate").asString()).isEqualTo("2026-11-25");
        assertThat(preview.get("firstNewInvoice").get("dueDate").asString()).isEqualTo("2026-11-28");
        assertThat(preview.get("movedInstallments").asInt()).isZero();

        JsonNode changed = confirmedChange(25, 28, 0);

        assertThat(changed.get("closingDay").asInt()).isEqualTo(25);
        assertThat(changed.get("dueDay").asInt()).isEqualTo(28);
        assertThat(changed.get("version").asInt()).isEqualTo(1);
        JsonNode current = invoice(NOV_CLOSING);
        assertThat(current.get("dueDate").asString()).isEqualTo("2026-11-05");
        assertThat(current.get("referenceMonth").asString()).isEqualTo("2026-11");
        assertThat(current.get("id").asString()).isEqualTo(invoiceId(NOV_CLOSING).toString());
        JsonNode next = invoice(LocalDate.of(2026, 11, 25));
        assertThat(next.get("dueDate").asString()).isEqualTo("2026-11-28");
        assertThat(next.get("referenceMonth").asString()).isEqualTo("2026-11");
        assertThat(okBody(get("/v1/transactions/" + notebookId)).get("installments").size()).isEqualTo(6);
        for (int k = 0; k < 6; k++) {
            assertThat(okBody(get("/v1/transactions/" + notebookId)).get("installments").get(k).get("invoiceId"))
                    .isEqualTo(installmentsBefore.get(k).get("invoiceId"));
        }
        assertThat(consumptionByCompetence()).isEqualTo(consumptionBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_billing_rules WHERE card_id = ?", Long.class, UUID.fromString(card))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT effective_from FROM credit_card_billing_rules WHERE card_id = ? AND due_day = 28", String.class,
                UUID.fromString(card)).strip()).isEqualTo("2026-11");
        assertThat(closings()).containsExactly("2026-10-25", "2026-11-25", "2026-12-25", "2027-01-25", "2027-02-25", "2027-03-25");
        assertRowsMatchTheCalendar();
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("8800.00");
        assertThat(jdbc.queryForObject("SELECT version FROM transactions WHERE id = ?", Long.class, UUID.fromString(notebookId))).isZero();
    }

    private void assertRowsMatchTheCalendar() throws Exception {
        for (Map<String, Object> row : rows()) {
            JsonNode api = okBody(get("/v1/invoices/" + row.get("id")));
            assertThat(row.get("period_start").toString()).as("period_start de " + row.get("closing_date")).isEqualTo(api.get("periodStart").asString());
            assertThat(row.get("due_date").toString()).as("due_date de " + row.get("closing_date")).isEqualTo(api.get("dueDate").asString());
            assertThat(row.get("reference_month").toString().strip()).as("reference_month de " + row.get("closing_date"))
                    .isEqualTo(api.get("referenceMonth").asString());
            assertThat(row.get("id").toString()).isEqualTo(InvoiceIds.of(UUID.fromString(card), LocalDate.parse(row.get("closing_date").toString())).toString());
        }
    }

    @Test
    @DisplayName("CA2: fechamento 25 → 10 com a corrente fechando 25/10: a primeira fatura nova fecha 10/11; a fatura de 25/10 não muda; as parcelas 2..6 vão para 10/11..10/03; as linhas FUTURE velhas sem referência são removidas; o consumo não muda")
    void deveMudarOFechamento() throws Exception {
        notebook();
        JsonNode consumptionBefore = consumptionByCompetence();
        JsonNode currentBefore = invoice(NOV_CLOSING);
        assertThat(closings()).hasSize(6);

        JsonNode changed = confirmedChange(10, 5, 0);

        assertThat(changed.get("closingDay").asInt()).isEqualTo(10);
        assertThat(changed.get("dueDay").asInt()).isEqualTo(5);
        assertThat(invoice(NOV_CLOSING)).isEqualTo(currentBefore);
        JsonNode tx = okBody(get("/v1/transactions/" + notebookId));
        assertThat(tx.get("version").asInt()).isEqualTo(1);
        List<String> refs = tx.get("installments").valueStream().map(i -> i.get("invoiceReferenceMonth").asString()).toList();
        assertThat(refs).containsExactly("2026-11", "2026-12", "2027-01", "2027-02", "2027-03", "2027-04");
        assertThat(tx.get("installments").get(1).get("invoiceId").asString()).isEqualTo(invoiceId(LocalDate.of(2026, 11, 10)).toString());
        assertThat(tx.get("installments").get(1).get("invoiceDueDate").asString()).isEqualTo("2026-12-05");
        assertThat(closings()).containsExactly("2026-10-25", "2026-11-10", "2026-12-10", "2027-01-10", "2027-02-10", "2027-03-10");
        assertRowsMatchTheCalendar();
        assertThat(consumptionByCompetence()).isEqualTo(consumptionBefore);
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("8800.00");
        assertThat(money(cardNow(), "committedFuture")).isEqualTo("1000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices i WHERE card_id = ? AND NOT EXISTS (SELECT 1 FROM transaction_installments t "
                + "WHERE t.invoice_id = i.id) AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.invoice_id = i.id)", Long.class, UUID.fromString(card)))
                .as("nenhuma linha sem referência sobrou").isZero();
        for (JsonNode listed : okBody(get("/v1/cards/" + card + "/invoices?pageSize=50")).get("items")) {
            okBody(get("/v1/invoices/" + listed.get("id").asString()));
        }
        created(purchase("100.00", 2, "2026-10-12", "Livro"));
        advanceTo(OCT_30);
        assertThat(paid(NOV_CLOSING, "300.00", "2026-10-30").get("status").asString()).isEqualTo("OPEN".equals("") ? "" : "PAID");
    }

    @Test
    @DisplayName("CA3: a prévia com fechamento 10 devolve firstNewInvoice (fecha 10/11, vence 05/12), 5 parcelas movidas e FROM_NEXT_INVOICE, sem gravar nada")
    void devePrevisualizarSemGravar() throws Exception {
        notebook();
        created(purchase("300.00", 3, "2026-10-10", "Cursos"));
        long installmentsBefore = count("transaction_installments");
        long rulesBefore = jdbc.queryForObject("SELECT count(*) FROM credit_card_billing_rules WHERE card_id = ?", Long.class, UUID.fromString(card));
        List<Map<String, Object>> rowsBefore = rows();
        List<Map<String, Object>> installmentRowsBefore = jdbc.queryForList("SELECT id, invoice_id, version, updated_at FROM transaction_installments WHERE user_id = ? ORDER BY id", userId);

        mockMvc.perform(get("/v1/cards/" + card + "/billing-change-preview?closingDay=10&dueDay=5")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.effectiveFromClosingDate").value("2026-11-25"))
                .andExpect(jsonPath("$.firstNewInvoice.closingDate").value("2026-11-10")).andExpect(jsonPath("$.firstNewInvoice.dueDate").value("2026-12-05"))
                .andExpect(jsonPath("$.movedInstallments").value(7)).andExpect(jsonPath("$.movedCredits").value(0))
                .andExpect(jsonPath("$.notice").value("FROM_NEXT_INVOICE"));

        assertThat(count("transaction_installments")).isEqualTo(installmentsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_billing_rules WHERE card_id = ?", Long.class, UUID.fromString(card))).isEqualTo(rulesBefore);
        assertThat(rows()).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT id, invoice_id, version, updated_at FROM transaction_installments WHERE user_id = ? ORDER BY id", userId))
                .isEqualTo(installmentRowsBefore);
        assertThat(cardNow().get("version").asInt()).isZero();
        assertThat(cardNow().get("closingDay").asInt()).isEqualTo(25);
        assertThat(preview(25, 5).get("movedInstallments").asInt()).isZero();
    }

    @Test
    @DisplayName("a prévia conta os créditos que mudam de fatura (um reembolso datado de 11/11 vai de 25/11 para 10/12) e a mudança os realoca")
    void deveRealocarCreditos() throws Exception {
        created(purchase("500.00", 1, "2026-10-10", "Fone"));
        String refund = created(cardRefund("30.00", "2026-11-11", "Crédito futuro")).get("id").asString();
        assertThat(okBody(get("/v1/transactions/" + refund)).get("invoice").get("closingDate").asString()).isEqualTo("2026-11-25");
        JsonNode preview = preview(10, 5);
        assertThat(preview.get("movedCredits").asInt()).isEqualTo(1);
        assertThat(preview.get("movedInstallments").asInt()).isZero();

        confirmedChange(10, 5, 0);

        JsonNode moved = okBody(get("/v1/transactions/" + refund));
        assertThat(moved.get("invoice").get("closingDate").asString()).isEqualTo("2026-12-10");
        assertThat(moved.get("version").asInt()).isEqualTo(1);
        assertThat(closings()).containsExactly("2026-10-25", "2026-12-10");
        assertRowsMatchTheCalendar();
    }

    @Test
    @DisplayName("CA4: a prévia de 10/10 (25/11) fica velha na virada do dia (em 30/10 a primeira fatura afetada é 25/12): 409 CARD_BILLING_CHANGE_STALE e nada muda; com a prévia nova, aplica; sem confirmedEffectiveFrom, 422")
    void deveDetectarPreviaVelha() throws Exception {
        notebook();
        String confirmed = preview(10, 5).get("effectiveFromClosingDate").asString();
        assertThat(confirmed).isEqualTo("2026-11-25");
        advanceTo(OCT_30);

        change(10, 5, confirmed, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CARD_BILLING_CHANGE_STALE"))
                .andExpect(ContractValidation.conforms());
        change(10, 5, null, 0).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CARD_BILLING_CHANGE_NOT_CONFIRMED"))
                .andExpect(ContractValidation.conforms());

        JsonNode unchanged = cardNow();
        assertThat(unchanged.get("version").asInt()).isZero();
        assertThat(unchanged.get("closingDay").asInt()).isEqualTo(25);
        assertThat(closings()).containsExactly("2026-10-25", "2026-11-25", "2026-12-25", "2027-01-25", "2027-02-25", "2027-03-25");
        String fresh = preview(10, 5).get("effectiveFromClosingDate").asString();
        assertThat(fresh).isEqualTo("2026-12-25");
        change(10, 5, fresh, 0).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.closingDay").value(10));
        assertThat(closings()).containsExactly("2026-10-25", "2026-11-25", "2026-12-10", "2027-01-10", "2027-02-10", "2027-03-10");
        assertRowsMatchTheCalendar();
    }

    @Test
    @DisplayName("CA5: falha no meio da realocação (gatilho que recusa o UPDATE das parcelas): 500, rollback total (cartão, regras, parcelas e linhas iguais ao antes, nenhuma linha órfã); sem o gatilho, a mesma chamada funciona")
    void deveFazerRollbackTotal() throws Exception {
        notebook();
        String confirmed = preview(10, 5).get("effectiveFromClosingDate").asString();
        List<Map<String, Object>> installmentsBefore = jdbc.queryForList("SELECT id, invoice_id, version FROM transaction_installments WHERE user_id = ? ORDER BY id", userId);
        List<Map<String, Object>> rowsBefore = rows();
        committed(() -> {
            jdbc.execute("CREATE OR REPLACE FUNCTION fail_installment_update() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'falha injetada'; END $$ LANGUAGE plpgsql");
            jdbc.execute("CREATE TRIGGER tg_fail_installment_update BEFORE UPDATE ON transaction_installments FOR EACH ROW EXECUTE FUNCTION fail_installment_update()");
        });
        try {
            change(10, 5, confirmed, 0).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(ContractValidation.conforms());
        } finally {
            committed(() -> {
                jdbc.execute("DROP TRIGGER tg_fail_installment_update ON transaction_installments");
                jdbc.execute("DROP FUNCTION fail_installment_update()");
            });
        }

        JsonNode unchanged = cardNow();
        assertThat(unchanged.get("version").asInt()).isZero();
        assertThat(unchanged.get("closingDay").asInt()).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_billing_rules WHERE card_id = ?", Long.class, UUID.fromString(card))).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id, invoice_id, version FROM transaction_installments WHERE user_id = ? ORDER BY id", userId)).isEqualTo(installmentsBefore);
        assertThat(rows()).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForObject("SELECT version FROM transactions WHERE id = ?", Long.class, UUID.fromString(notebookId))).isZero();
        change(10, 5, confirmed, 0).andExpect(status().isOk()).andExpect(jsonPath("$.closingDay").value(10));
    }

    @Test
    @DisplayName("CA6: dois PUT simultâneos com a mesma versão: um vence (200) e o outro é 409 VERSION_CONFLICT; o estado final é o de uma só mudança")
    void deveSerializarMudancasConcorrentes() throws Exception {
        notebook();
        String confirmed = preview(10, 5).get("effectiveFromClosingDate").asString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<Integer>> results = List.of(pool.submit(() -> {
                barrier.await();
                return change(10, 5, confirmed, 0).andReturn().getResponse().getStatus();
            }), pool.submit(() -> {
                barrier.await();
                return change(10, 5, confirmed, 0).andReturn().getResponse().getStatus();
            }));
            assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(cardNow().get("version").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_billing_rules WHERE card_id = ?", Long.class, UUID.fromString(card))).isEqualTo(2);
        assertThat(closings()).containsExactly("2026-10-25", "2026-11-10", "2026-12-10", "2027-01-10", "2027-02-10", "2027-03-10");
    }

    @Test
    @DisplayName("mudança de dias × estorno da compra simultâneos: sem 500 e sem linha órfã (ordem de travas: movimentações → cartão)")
    void deveSerializarMudancaEEstorno() throws Exception {
        for (int round = 0; round < 5; round++) {
            clock.set(com.moneylog.util.MutableClockConfig.INITIAL);
            String roundCard = createCard("Rodada " + round, 25, 5);
            String id = created(purchaseOn(roundCard, tech, "1200.00", 6, "2026-10-10", "Notebook " + round)).get("id").asString();
            String confirmed = okBody(get("/v1/cards/" + roundCard + "/billing-change-preview?closingDay=10&dueDay=5")).get("effectiveFromClosingDate").asString();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<String> changeResult = pool.submit(() -> {
                    barrier.await();
                    var response = mockMvc.perform(put("/v1/cards/" + roundCard, putBody(10, 5, confirmed, 0))).andReturn().getResponse();
                    return response.getStatus() + " " + response.getContentAsString();
                });
                Future<String> voidResult = pool.submit(() -> {
                    barrier.await();
                    var response = mockMvc.perform(post("/v1/transactions/" + id + "/void", "{\"reason\":\"errei\"}")).andReturn().getResponse();
                    return response.getStatus() + " " + response.getContentAsString();
                });
                assertThat(changeResult.get()).startsWith("200 ");
                assertThat(voidResult.get()).startsWith("200 ");
            } finally {
                pool.shutdownNow();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices i WHERE card_id = ? AND NOT EXISTS (SELECT 1 FROM transaction_installments t "
                    + "WHERE t.invoice_id = i.id) AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.invoice_id = i.id)", Long.class, UUID.fromString(roundCard)))
                    .isZero();
            assertThat(okBody(get("/v1/transactions/" + id)).get("status").asString()).isEqualTo("VOIDED");
        }
    }

    @Test
    @DisplayName("CA7: itens de compra estornada e de parcelas canceladas também são realocados e mantêm a linha; só a linha FUTURE sem nenhuma referência é removida; o invariante do cartão vale antes e depois")
    void deveRealocarTambemOsItensInativos() throws Exception {
        notebook();
        String voided = created(purchase("600.00", 3, "2026-10-12", "Estornada")).get("id").asString();
        mockMvc.perform(post("/v1/transactions/" + voided + "/void", "{\"reason\":\"errei\"}")).andExpect(status().isOk());
        String canceled = created(purchase("400.00", 4, "2026-10-13", "Cancelada")).get("id").asString();
        advanceTo(OCT_30);
        mockMvc.perform(post("/v1/transactions/" + canceled + "/cancel-installments", "{\"reason\":\"desisti\"}")).andExpect(status().isOk());
        paid(NOV_CLOSING, "100.00", "2026-10-30");
        JsonNode before = cardNow();
        JsonNode consumptionBefore = consumptionByCompetence();

        confirmedChange(10, 5, 0);

        JsonNode after = cardNow();
        assertThat(money(after, "availableLimit")).isEqualTo(money(before, "availableLimit"));
        assertThat(money(after, "totalCommitted")).isEqualTo(money(before, "totalCommitted"));
        assertThat(money(after, "committedFuture")).isEqualTo(money(before, "committedFuture"));
        assertThat(money(after.get("currentInvoice"), "total")).isEqualTo(money(before.get("currentInvoice"), "total"));
        assertThat(consumptionByCompetence()).isEqualTo(consumptionBefore);
        assertThat(invoice(NOV_CLOSING).get("payments")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_installments i JOIN credit_card_invoices v ON v.id = i.invoice_id WHERE i.user_id = ? "
                + "AND v.closing_date NOT IN (SELECT closing_date FROM credit_card_invoices WHERE card_id = ?)", Long.class, userId, UUID.fromString(card))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices i WHERE card_id = ? AND NOT EXISTS (SELECT 1 FROM transaction_installments t "
                + "WHERE t.invoice_id = i.id) AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.invoice_id = i.id)", Long.class, UUID.fromString(card))).isZero();
        assertRowsMatchTheCalendar();
        assertThat(okBody(get("/v1/transactions/" + voided)).get("status").asString()).isEqualTo("VOIDED");
    }

    @Test
    @DisplayName("CA8: vencimento igual ao fechamento (25/25) e fechamento 31 em meses curtos são resolvidos pelo domínio (200); dia 0 ou 32 é 422 CARD_INVALID_BILLING_DAY, também com a confirmação ausente e na prévia")
    void deveValidarOsDias() throws Exception {
        notebook();

        mockMvc.perform(get("/v1/cards/" + card + "/billing-change-preview?closingDay=32&dueDay=5")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_INVALID_BILLING_DAY")).andExpect(ContractValidation.conforms());
        mockMvc.perform(get("/v1/cards/" + card + "/billing-change-preview?closingDay=25&dueDay=0")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_INVALID_BILLING_DAY"));
        mockMvc.perform(get("/v1/cards/" + card + "/billing-change-preview?closingDay=abc&dueDay=5")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/cards/" + card + "/billing-change-preview?closingDay=10")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        change(32, 5, null, 0).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CARD_INVALID_BILLING_DAY"))
                .andExpect(ContractValidation.conforms());
        change(0, 5, "2026-11-25", 0).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CARD_INVALID_BILLING_DAY"));
        assertThat(cardNow().get("version").asInt()).isZero();

        JsonNode same = confirmedChange(25, 25, 0);
        assertThat(same.get("dueDay").asInt()).isEqualTo(25);
        assertThat(invoice(LocalDate.of(2026, 11, 25)).get("dueDate").asString()).isEqualTo("2026-12-25");
        confirmedChange(31, 31, 1);
        assertThat(invoice(LocalDate.of(2027, 2, 28)).get("closingDate").asString()).isEqualTo("2027-02-28");
        assertThat(invoice(LocalDate.of(2027, 2, 28)).get("dueDate").asString()).isEqualTo("2027-03-31");
        assertRowsMatchTheCalendar();
    }

    @Test
    @DisplayName("PUT sem mudar os dias ignora confirmedEffectiveFrom e não toca em parcelas nem faturas; nome e dias mudam juntos numa só gravação; outro usuário recebe 404")
    void deveAplicarDetalhesEDiasEIsolarUsuarios() throws Exception {
        notebook();
        change(25, 5, "2030-01-01", 0).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.version").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_billing_rules WHERE card_id = ?", Long.class, UUID.fromString(card))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM transactions WHERE id = ?", Long.class, UUID.fromString(notebookId))).isZero();

        String confirmed = preview(10, 5).get("effectiveFromClosingDate").asString();
        mockMvc.perform(put("/v1/cards/" + card, putBody(10, 5, confirmed, 1).replace("\"name\":\"Roxinho\"", "\"name\":\"Roxinho Dez\"")))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.name").value("Roxinho Dez"))
                .andExpect(jsonPath("$.closingDay").value(10)).andExpect(jsonPath("$.version").value(2));

        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@example.com"));
        String otherBearer = auth.bearer(other);
        mockMvc.perform(getAs(otherBearer, "/v1/cards/" + card + "/billing-change-preview?closingDay=10&dueDay=5")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/v1/cards/" + card)
                .header("Authorization", otherBearer).contentType("application/json").content(putBody(12, 5, confirmed, 2)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"));
        assertThat(cardNow().get("closingDay").asInt()).isEqualTo(10);
    }
}
