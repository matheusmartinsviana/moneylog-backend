package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("Reembolso e crédito no cartão (T56)")
class CardRefundIT extends CardItBase {

    private String refundBody(String amount, String date, String originalId) {
        String origin = originalId == null ? "" : ",\"refundOfTransactionId\":\"" + originalId + "\"";
        return "{\"type\":\"REFUND\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Estorno do banco\","
                + "\"cardId\":\"" + card + "\",\"categoryId\":\"" + tech + "\"" + origin + "}";
    }

    @Test
    @DisplayName("CA1: compra de 200,00 e reembolso de 50,00 na mesma categoria: consumo −50 na competência, CARD +50 (limite sobe 50) e a fatura alvo cai 50")
    void deveReembolsarUmaCompra() throws Exception {
        String purchaseId = created(purchase("200.00", 1, "2026-10-10", "Fone")).get("id").asString();
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("9800.00");

        JsonNode refund = body(createTx(refundBody("50.00", "2026-10-10", purchaseId)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.type").value("REFUND"))
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.card.id").value(card))
                .andExpect(jsonPath("$.card.name").value("Roxinho")).andExpect(jsonPath("$.account").doesNotExist())
                .andExpect(jsonPath("$.refundOf.id").value(purchaseId)).andExpect(jsonPath("$.refundOf.description").value("Fone"))
                .andExpect(jsonPath("$.category.name").value("Tecnologia")).andExpect(jsonPath("$.installments.length()").value(0))
                .andExpect(jsonPath("$.invoice.closingDate").value("2026-10-25")).andExpect(jsonPath("$.invoice.referenceMonth").value("2026-11"))
                .andExpect(jsonPath("$.invoice.status").value("OPEN")).andExpect(jsonPath("$.impact.cash.amount").value("0.00"))
                .andExpect(jsonPath("$.impact.consumption.length()").value(1))
                .andExpect(jsonPath("$.impact.consumption[0].competence").value("2026-10"))
                .andExpect(jsonPath("$.impact.consumption[0].amount.amount").value("-50.00"))
                .andExpect(jsonPath("$.impact.netWorth.amount").value("50.00")).andExpect(jsonPath("$.impact.commitment.amount").value("0.00"))
                .andExpect(jsonPath("$.editable").value(false)).andReturn());

        UUID refundId = UUID.fromString(refund.get("id").asString());
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE transaction_id = ? AND book_type = 'CARD' AND active",
                Long.class, refundId)).isEqualTo(5_000L);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM ledger_postings WHERE transaction_id = ? AND book_type = 'EXPENSE' AND active",
                Long.class, refundId)).isEqualTo(-5_000L);
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("9850.00");
        JsonNode invoice = invoice(NOV_CLOSING);
        assertThat(money(invoice, "total")).isEqualTo("150.00");
        assertThat(invoice.get("items")).hasSize(2);
        JsonNode credit = invoice.get("items").get(1);
        assertThat(credit.get("kind").asString()).isEqualTo("CREDIT");
        assertThat(credit.get("number").isNull()).isTrue();
        assertThat(credit.get("totalInstallments").isNull()).isTrue();
        assertThat(money(credit, "amount")).isEqualTo("50.00");
        okBody(get("/v1/transactions/" + refundId));
        assertThat(okBody(get("/v1/transactions?type=REFUND")).get("items")).hasSize(1);
        JsonNode consumption = okBody(get("/v1/entries?competence=2026-10"));
        assertThat(money(consumption.get("totals"), "total")).isEqualTo("150.00");
        assertThat(count("credit_card_invoices")).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2: reembolso de 1.083,00 numa fatura OPEN de 200,00 é aceito: total −883, outstanding 0, creditCarry 883 que abate a fatura seguinte")
    void deveAceitarCreditoMaiorQueAFatura() throws Exception {
        created(purchase("200.00", 1, "2026-10-10", "Fone"));
        created(purchase("100.00", 1, "2026-11-11", "Futura"));

        created(cardRefund("1083.00", "2026-10-10", "Estorno grande"));

        JsonNode first = invoice(NOV_CLOSING);
        assertThat(money(first, "total")).isEqualTo("-883.00");
        assertThat(money(first, "outstanding")).isEqualTo("0.00");
        assertThat(money(first, "creditCarry")).isEqualTo("883.00");
        JsonNode next = invoice(LocalDate.of(2026, 11, 25));
        assertThat(money(next, "carryIn")).isEqualTo("883.00");
        assertThat(money(next, "outstanding")).isEqualTo("0.00");
        assertThat(money(next, "creditCarry")).isEqualTo("783.00");
        assertThat(money(cardNow(), "creditBalance")).isEqualTo("783.00");
    }

    @Test
    @DisplayName("CA3: acima do que resta é 422 REFUND_EXCEEDS_ORIGINAL, outra categoria é 422 REFUND_INVALID_ORIGINAL, original de outro usuário é 404; nada gravado")
    void deveRespeitarOTetoEACategoria() throws Exception {
        String purchaseId = created(purchase("200.00", 1, "2026-10-10", "Fone")).get("id").asString();
        created(refundBody("150.00", "2026-10-10", purchaseId));
        long before = count("transactions");

        createTx(refundBody("60.00", "2026-10-10", purchaseId)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_ORIGINAL")).andExpect(ContractValidation.conforms());
        createTx(refundBody("10.00", "2026-10-10", purchaseId).replace(tech, food)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_INVALID_ORIGINAL")).andExpect(ContractValidation.conforms());
        createTx(refundBody("10.00", "2026-10-10", UUID.randomUUID().toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND")).andExpect(ContractValidation.conforms());
        createTx(refundBody("10.00", "2026-10-10", null).replace(tech, income)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_KIND_MISMATCH")).andExpect(ContractValidation.conforms());
        assertThat(count("transactions")).isEqualTo(before);
        created(refundBody("50.00", "2026-10-10", purchaseId));
    }

    @Test
    @DisplayName("CA4: fatura de 25/10 CLOSED e não paga recebe o crédito como lançamento tardio; PAID manda o crédito para a corrente (25/11)")
    void deveAlocarOCreditoPeloStatusDaFatura() throws Exception {
        created(purchase("200.00", 1, "2026-10-10", "Fone"));
        advanceTo(OCT_27);

        created(refundBody("30.00", "2026-10-20", null));

        JsonNode closed = invoice(NOV_CLOSING);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(money(closed, "total")).isEqualTo("170.00");
        assertThat(closed.get("hasLateEntries").asBoolean()).isTrue();
        assertThat(closed.get("items").get(1).get("lateEntry").asBoolean()).isTrue();

        paid(NOV_CLOSING, "170.00", "2026-10-27");
        JsonNode toCurrent = created(refundBody("20.00", "2026-10-21", null));

        assertThat(toCurrent.get("invoice").get("closingDate").asString()).isEqualTo("2026-11-25");
        JsonNode current = invoice(LocalDate.of(2026, 11, 25));
        assertThat(money(current, "total")).isEqualTo("-20.00");
        assertThat(current.get("items").get(0).get("lateEntry").asBoolean()).isTrue();
        assertThat(invoice(NOV_CLOSING).get("status").asString()).isEqualTo("PAID");
        assertThat(invoice(NOV_CLOSING).get("items")).hasSize(2);
    }

    @Test
    @DisplayName("CA5: com accountId e cardId, ou sem nenhum dos dois, é 400; cartão arquivado é 422 CARD_ARCHIVED; cartão de outro usuário é 404; nada gravado")
    void deveRecusarEntradaInvalida() throws Exception {
        long before = count("transactions");

        createTx(refundBody("10.00", "2026-10-10", null).replace("\"cardId\"", "\"accountId\":\"" + nubank + "\",\"cardId\""))
                .andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        createTx(refundBody("10.00", "2026-10-10", null).replace(",\"cardId\":\"" + card + "\"", "")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        UUID other = UUID.randomUUID();
        UUID foreignCard = UUID.randomUUID();
        committed(() -> {
            SqlFixtures.insertUser(jdbc, other, "alheio-" + other + "@example.com");
            jdbc.update("INSERT INTO credit_cards (id, user_id, name, limit_cents, currency, status, created_at, updated_at) "
                    + "VALUES (?, ?, 'Alheio', 100000, 'BRL', 'ACTIVE', now(), now())", foreignCard, other);
            jdbc.update("INSERT INTO credit_card_billing_rules (id, user_id, card_id, effective_from, closing_day, due_day, created_at, updated_at) "
                    + "VALUES (gen_random_uuid(), ?, ?, '2026-10', 25, 5, now(), now())", other, foreignCard);
        });
        createTx(refundBody("10.00", "2026-10-10", null).replace(card, foreignCard.toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/cards/" + card + "/archive", "")).andExpect(status().isOk());
        createTx(refundBody("10.00", "2026-10-10", null)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_ARCHIVED")).andExpect(ContractValidation.conforms());
        assertThat(count("transactions")).isEqualTo(before);
    }

    @Test
    @DisplayName("o reembolso em conta (Etapa 2) continua igual, e o mesmo Idempotency-Key devolve o mesmo crédito no cartão")
    void deveManterOReembolsoEmContaEAIdempotencia() throws Exception {
        String key = UUID.randomUUID().toString();
        String json = refundBody("10.00", "2026-10-10", null);

        String first = body(mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn()).get("id").asString();
        String second = body(mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn()).get("id").asString();
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ? AND type = 'REFUND'", Long.class, userId)).isEqualTo(1);

        String expense = created("{\"type\":\"EXPENSE\",\"amount\":\"100.00\",\"date\":\"2026-10-10\",\"description\":\"Mercado\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}").get("id").asString();
        createTx("{\"type\":\"REFUND\",\"amount\":\"30.00\",\"date\":\"2026-10-10\",\"description\":\"Devolução\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"refundOfTransactionId\":\"" + expense + "\"}").andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.card").doesNotExist())
                .andExpect(jsonPath("$.invoice").doesNotExist());
    }
}
