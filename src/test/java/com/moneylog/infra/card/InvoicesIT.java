package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

/**
 * T54: cartão enriquecido, lista de faturas, fatura com itens e parcelas futuras. Cenário: as duas compras de 10/10 e 12/10
 * no Roxinho (1.200,00 em 6x e 1.083,00 à vista); relógio 10/10 e depois 30/10. O estado com pagamentos é semeado pelo
 * gateway (E3-13). Toda requisição e resposta valida contra o contrato.
 */
@DisplayName("Faturas, cartão enriquecido e parcelas futuras (T54)")
class InvoicesIT extends CardItBase {

    @Test
    @DisplayName("CA1: em 10/10 o cartão mostra a fatura corrente 2026-11 OPEN com 1.283,00, futuras 1.000,00, disponível 7.717,00 e uso 0,2283")
    void deveEnriquecerOCartao() throws Exception {
        twoPurchases();

        JsonNode now = cardNow();

        JsonNode current = now.get("currentInvoice");
        assertThat(current.get("referenceMonth").asString()).isEqualTo("2026-11");
        assertThat(current.get("status").asString()).isEqualTo("OPEN");
        assertThat(money(current, "total")).isEqualTo("1283.00");
        assertThat(money(current, "outstanding")).isEqualTo("1283.00");
        assertThat(current.get("closingDate").asString()).isEqualTo("2026-10-25");
        assertThat(current.get("dueDate").asString()).isEqualTo("2026-11-05");
        assertThat(money(now, "committedFuture")).isEqualTo("1000.00");
        assertThat(money(now, "availableLimit")).isEqualTo("7717.00");
        assertThat(money(now, "totalCommitted")).isEqualTo("1283.00");
        assertThat(now.get("usedRatio").asDouble()).isEqualTo(0.2283);
        assertThat(now.get("overLimit").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("CA2: em 30/10 a fatura de 25/10 é CLOSED com a parcela 1/6 'Notebook' e a compra à vista, sem pagamentos; a seguinte (2026-12) é OPEN com 200,00")
    void deveDetalharAFaturaFechada() throws Exception {
        twoPurchases();
        advanceTo(OCT_30);

        JsonNode closed = invoice(NOV_CLOSING);

        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("id").asString()).isEqualTo(invoiceId(NOV_CLOSING).toString());
        assertThat(money(closed, "total")).isEqualTo("1283.00");
        assertThat(closed.get("payments")).isEmpty();
        assertThat(closed.get("items")).hasSize(2);
        // mesma data: ordem por número da parcela e, empatando, por descrição (Mercado 1/1 antes de Notebook 1/6)
        JsonNode installment = closed.get("items").get(1);
        assertThat(installment.get("kind").asString()).isEqualTo("INSTALLMENT");
        assertThat(installment.get("description").asString()).isEqualTo("Notebook");
        assertThat(installment.get("number").asInt()).isEqualTo(1);
        assertThat(installment.get("totalInstallments").asInt()).isEqualTo(6);
        assertThat(money(installment, "amount")).isEqualTo("200.00");
        assertThat(installment.get("lateEntry").asBoolean()).isFalse();
        JsonNode cashPurchase = closed.get("items").get(0);
        assertThat(cashPurchase.get("description").asString()).isEqualTo("Mercado");
        assertThat(money(cashPurchase, "amount")).isEqualTo("1083.00");
        JsonNode next = cardNow().get("currentInvoice");
        assertThat(next.get("status").asString()).isEqualTo("OPEN");
        assertThat(next.get("referenceMonth").asString()).isEqualTo("2026-12");
        assertThat(money(next, "total")).isEqualTo("200.00");
    }

    @Test
    @DisplayName("CA3: as parcelas futuras somam 1.000,00 em 5 faturas (2026-12 a 2027-04), e o total é o Card.committedFuture")
    void deveListarAsParcelasFuturas() throws Exception {
        twoPurchases();

        JsonNode future = okBody(get("/v1/cards/" + card + "/future-installments"));

        assertThat(money(future, "total")).isEqualTo("1000.00");
        assertThat(future.get("byInvoice")).hasSize(5);
        assertThat(future.get("byInvoice").get(0).get("referenceMonth").asString()).isEqualTo("2026-12");
        assertThat(future.get("byInvoice").get(4).get("referenceMonth").asString()).isEqualTo("2027-04");
        assertThat(future.get("byInvoice").get(0).get("dueDate").asString()).isEqualTo("2026-12-05");
        assertThat(money(future.get("byInvoice").get(0), "amount")).isEqualTo("200.00");
        assertThat(future.get("byInvoice").get(0).get("items").get(0).get("number").asInt()).isEqualTo(2);
        assertThat(future.get("byInvoice").get(0).get("invoiceId").asString()).isEqualTo(invoiceId(LocalDate.of(2026, 11, 25)).toString());
        assertThat(money(future, "total")).isEqualTo(money(cardNow(), "committedFuture"));
        mockMvc.perform(get("/v1/cards/" + UUID.randomUUID() + "/future-installments")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND")).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA4: pagamento parcial de 500,00 e relógio depois do vencimento: OVERDUE, paid 500,00, outstanding 783,00 e o disponível sobe 500,00")
    void deveMostrarFaturaAtrasadaComPagamentoParcial() throws Exception {
        twoPurchases();
        String availableBefore = money(cardNow(), "availableLimit");
        advanceTo(NOV_05.plusSeconds(86_400 * 2L));
        seedPayment("500.00", NOV_CLOSING, LocalDate.of(2026, 11, 6));

        JsonNode overdue = invoice(NOV_CLOSING);

        assertThat(overdue.get("status").asString()).isEqualTo("OVERDUE");
        assertThat(money(overdue, "paid")).isEqualTo("500.00");
        assertThat(money(overdue, "outstanding")).isEqualTo("783.00");
        assertThat(overdue.get("payments")).hasSize(1);
        assertThat(money(overdue.get("payments").get(0), "amount")).isEqualTo("500.00");
        assertThat(overdue.get("payments").get(0).get("accountId").asString()).isEqualTo(nubank);
        assertThat(money(cardNow(), "availableLimit")).isEqualTo("8217.00");
        assertThat(availableBefore).isEqualTo("7717.00");
    }

    @Test
    @DisplayName("CA5: 1.300,00 numa fatura CLOSED de 1.283,00: PAID, creditCarry 17,00, a seguinte com carryIn 17,00 e outstanding 183,00; creditBalance 0,00; sem parcelas futuras o creditBalance é 17,00")
    void deveTransportarOCredito() throws Exception {
        twoPurchases();
        advanceTo(OCT_30);
        seedPayment("1300.00", NOV_CLOSING, LocalDate.of(2026, 10, 30));

        JsonNode paid = invoice(NOV_CLOSING);
        JsonNode next = invoice(LocalDate.of(2026, 11, 25));

        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(money(paid, "creditCarry")).isEqualTo("17.00");
        assertThat(money(next, "carryIn")).isEqualTo("17.00");
        assertThat(money(next, "outstanding")).isEqualTo("183.00");
        assertThat(money(cardNow(), "creditBalance")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("CA5: cartão sem parcelas futuras, pagar 1.300,00 de 1.283,00: creditBalance 17,00 e disponível acima do limite")
    void deveMostrarOCreditoNoCartaoSemParcelas() throws Exception {
        created(purchase("1283.00", 1, "2026-10-10", "Mercado"));
        advanceTo(OCT_30);
        seedPayment("1300.00", NOV_CLOSING, LocalDate.of(2026, 10, 30));

        JsonNode now = cardNow();

        assertThat(money(now, "creditBalance")).isEqualTo("17.00");
        assertThat(money(now, "availableLimit")).isEqualTo("10017.00");
        assertThat(now.get("usedRatio").asDouble()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("CA6: a fatura corrente sem linha responde 200 com o id determinístico e total 0,00; id de outro usuário, desconhecido ou fora da janela é 404 INVOICE_NOT_FOUND")
    void deveResolverOIdDaFaturaSemLinha() throws Exception {
        assertThat(count("credit_card_invoices")).isZero();

        JsonNode empty = invoice(NOV_CLOSING);

        assertThat(empty.get("id").asString()).isEqualTo(InvoiceIds.of(UUID.fromString(card), NOV_CLOSING).toString());
        assertThat(money(empty, "total")).isEqualTo("0.00");
        assertThat(empty.get("status").asString()).isEqualTo("OPEN");
        assertThat(empty.get("items")).isEmpty();
        assertThat(count("credit_card_invoices")).as("a leitura não cria linha").isZero();

        UUID other = UUID.randomUUID();
        UUID foreignCard = UUID.randomUUID();
        committed(() -> {
            SqlFixtures.insertUser(jdbc, other, "alheio-" + other + "@moneylog.com");
            jdbc.update("INSERT INTO credit_cards (id, user_id, name, limit_cents, currency, status, created_at, updated_at) "
                    + "VALUES (?, ?, 'Alheio', 100000, 'BRL', 'ACTIVE', now(), now())", foreignCard, other);
            jdbc.update("INSERT INTO credit_card_billing_rules (id, user_id, card_id, effective_from, closing_day, due_day, created_at, updated_at) "
                    + "VALUES (gen_random_uuid(), ?, ?, '2026-10', 25, 5, now(), now())", other, foreignCard);
        });
        for (UUID id : new UUID[] {UUID.randomUUID(), InvoiceIds.of(foreignCard, NOV_CLOSING),
                InvoiceIds.of(UUID.fromString(card), LocalDate.of(2024, 9, 25)), InvoiceIds.of(UUID.fromString(card), LocalDate.of(2029, 11, 25))}) {
            mockMvc.perform(get("/v1/invoices/" + id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"))
                    .andExpect(ContractValidation.conforms());
        }
        mockMvc.perform(get("/v1/invoices/não-é-uuid")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("CA6: a fatura que já tem linha (recebeu a compra) também resolve pelo id, e a lista só devolve ids que resolvem")
    void deveResolverTodoIdDaLista() throws Exception {
        twoPurchases();

        JsonNode list = okBody(get("/v1/cards/" + card + "/invoices?pageSize=50"));

        assertThat(list.get("items")).hasSize(6);
        for (JsonNode item : list.get("items")) {
            JsonNode detail = okBody(get("/v1/invoices/" + item.get("id").asString()));
            assertThat(money(detail, "total")).isEqualTo(money(item, "total"));
            assertThat(detail.get("closingDate").asString()).isEqualTo(item.get("closingDate").asString());
        }
    }

    @Test
    @DisplayName("CA7: a lista segue a janela padrão em ordem decrescente e from/to filtram por closingDate; from depois de to é 400")
    void deveListarPorJanela() throws Exception {
        twoPurchases();

        JsonNode all = okBody(get("/v1/cards/" + card + "/invoices?pageSize=50"));
        assertThat(all.get("hasNext").asBoolean()).isFalse();
        assertThat(all.get("items").get(0).get("closingDate").asString()).isEqualTo("2027-03-25");
        assertThat(all.get("items").get(5).get("closingDate").asString()).isEqualTo("2026-10-25");

        JsonNode page = okBody(get("/v1/cards/" + card + "/invoices?pageSize=2&page=2"));
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("closingDate").asString()).isEqualTo("2027-01-25");
        assertThat(page.get("hasNext").asBoolean()).isTrue();

        JsonNode filtered = okBody(get("/v1/cards/" + card + "/invoices?from=2026-11-01&to=2026-12-31&pageSize=50"));
        assertThat(filtered.get("items")).hasSize(2);
        assertThat(filtered.get("items").get(0).get("closingDate").asString()).isEqualTo("2026-12-25");
        assertThat(filtered.get("items").get(1).get("closingDate").asString()).isEqualTo("2026-11-25");

        mockMvc.perform(get("/v1/cards/" + card + "/invoices?from=2026-12-01&to=2026-11-01")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/cards/" + UUID.randomUUID() + "/invoices")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND")).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("isolamento: outro usuário não lê fatura, lista nem parcelas futuras do meu cartão")
    void deveIsolarUsuarios() throws Exception {
        twoPurchases();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        String otherBearer = auth.bearer(other);

        mockMvc.perform(getAs(otherBearer, "/v1/invoices/" + invoiceId(NOV_CLOSING))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"));
        mockMvc.perform(getAs(otherBearer, "/v1/cards/" + card + "/invoices")).andExpect(status().isNotFound());
        mockMvc.perform(getAs(otherBearer, "/v1/cards/" + card + "/future-installments")).andExpect(status().isNotFound());
    }
}
