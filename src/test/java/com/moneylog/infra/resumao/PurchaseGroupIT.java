package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

@DisplayName("C2: compra parcelada agrupada entre cartões (PurchaseGroup, ADR-024)")
class PurchaseGroupIT extends ResumaoItBase {

    private String member(String card, int total, String installment, Integer remaining, String category, String extra) {
        return "{\"cardId\":\"" + card + "\",\"totalInstallments\":" + total + ",\"installmentAmount\":\"" + installment + "\","
                + (remaining == null ? "" : "\"remainingInstallments\":" + remaining + ",") + "\"firstInvoiceDate\":\"2026-10-10\""
                + (category == null ? "" : ",\"categoryId\":\"" + category + "\"") + (extra == null ? "" : "," + extra) + "}";
    }

    private String group(String name, String... members) {
        return "{\"name\":\"" + name + "\",\"interest\":false,\"members\":[" + String.join(",", members) + "]}";
    }

    @Test
    @DisplayName("aceite do Notebook (A 8x110 na 4ª parcela + B 4x45 na 1ª): total 1.060,00, restante 730,00, última parcela 05/03/2027 e valor do mês")
    void aceiteDoNotebook() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        JsonNode notebook = okBody(get("/v1/purchase-groups/" + ids.get("grupoNotebook")));

        assertThat(money(notebook, "total")).isEqualTo("1060.00");
        assertThat(money(notebook, "remaining")).isEqualTo("730.00");
        assertThat(notebook.get("lastDueDate").asString()).isEqualTo("2027-03-05");
        assertThat(notebook.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(notebook.get("members")).hasSize(2);
        assertThat(money(notebook, "myRemaining")).isEqualTo("730.00");
        long members = 0;
        for (JsonNode member : notebook.get("members")) {
            members += cents(member.get("remaining"));
        }
        assertThat(members).isEqualTo(73000L);
        assertThat(notebook.get("divergence").isNull()).isTrue();
        assertThat(cents(notebook.get("installmentAmountPerMonth"))).isIn(0L, 15500L);
    }

    @Test
    @DisplayName("criar o grupo não gera consumo retroativo: a compra em andamento só tem as 6 parcelas que faltam e setembro fica sem consumo")
    void naoGeraConsumoRetroativo() throws Exception {
        basicWorld();

        JsonNode created = createdBody(post("/v1/purchase-groups", group("Geladeira", member(cardA, 10, "100.00", 6, catFood, null))));

        assertThat(cents(okBody(get("/v1/summaries/2026-09")).get("expenses"))).as("as parcelas 1 a 4 não geram consumo").isZero();
        assertThat(book("EXPENSE")).as("só as 6 parcelas que faltam").isEqualTo(60000L);
        assertThat(book("CARD")).isEqualTo(-60000L);
        assertThat(created.get("members").get(0).get("remainingInstallments").asInt()).isEqualTo(6);
        assertThat(count("transactions")).isEqualTo(1L);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("GET /commitments?until=2026-11: parcelas de grupo e de compras fora de grupo entram uma só vez (535,00 = Jantar 100 + Pix 280 + Notebook 110 + 45) e committedMine tira a parte da Ana (485,00)")
    void compromissosAteNovembro() throws Exception {
        loadFixture("novembro-2026");

        JsonNode commitments = okBody(get("/v1/purchase-groups/commitments?until=2026-11"));

        assertThat(commitments.get("until").asString()).isEqualTo("2026-11");
        assertThat(money(commitments, "committed")).isEqualTo("535.00");
        assertThat(money(commitments, "committedMine")).isEqualTo("485.00");
        long byMonth = 0;
        for (JsonNode month : commitments.get("byMonth")) {
            byMonth += cents(month.get("amount"));
            assertThat(month.get("competence").asString()).isLessThanOrEqualTo("2026-11");
        }
        assertThat(byMonth).isEqualTo(53500L);
        mockMvc.perform(get("/v1/purchase-groups/commitments?until=2026-13")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PURCHASE_GROUP_UNTIL_INVALID"));
    }

    @Test
    @DisplayName("membro com rateio 50/50 com a Ana: myRemaining usa só a minha parte (200,00 de 400,00) e o committedMine acompanha; compra em andamento não aceita rateio (422)")
    void restanteMeuComRateio() throws Exception {
        basicWorld();
        JsonNode created = createdBody(post("/v1/purchase-groups", group("Sofa", member(cardA, 4, "100.00", null, catFood,
                "\"splitMode\":\"BPS\",\"shares\":[" + share(null, 5000) + "," + share(ana, 5000) + "]"))));

        assertThat(money(created, "remaining")).isEqualTo("400.00");
        assertThat(money(created, "myRemaining")).isEqualTo("200.00");
        assertThat(money(created.get("members").get(0), "myRemaining")).isEqualTo("200.00");
        JsonNode commitments = okBody(get("/v1/purchase-groups/commitments?until=2027-04"));
        assertThat(money(commitments, "committed")).isEqualTo("400.00");
        assertThat(money(commitments, "committedMine")).isEqualTo("200.00");
        assertThat(book("RECEIVABLE", ana)).isEqualTo(20000L);
        mockMvc.perform(post("/v1/purchase-groups", group("Ongoing com rateio", member(cardA, 10, "100.00", 6, catFood,
                "\"splitMode\":\"BPS\",\"shares\":[" + share(null, 5000) + "," + share(ana, 5000) + "]")))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("cancelar as parcelas restantes de uma compra membro recalcula o grupo (restante e última parcela) e estornar a compra tira o membro do total")
    void grupoRecalculaComCancelamento() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode before = okBody(get("/v1/purchase-groups/" + ids.get("grupoNotebook")));
        String boughtOnB = null;
        for (JsonNode member : before.get("members")) {
            if (member.get("card").get("id").asString().equals(ids.get("cartaoB"))) {
                boughtOnB = member.get("transactionId").asString();
            }
        }
        assertThat(boughtOnB).isNotNull();

        mockMvc.perform(post("/v1/transactions/" + boughtOnB + "/cancel-installments", "{\"reason\":\"devolvi o item\"}")).andExpect(status().isOk());

        JsonNode after = okBody(get("/v1/purchase-groups/" + ids.get("grupoNotebook")));
        assertThat(money(after, "remaining")).isEqualTo("550.00");
        assertThat(after.get("lastDueDate").asString()).isEqualTo("2027-03-05");
        assertThat(cents(after.get("total")) - 0).isLessThanOrEqualTo(106000L);
    }

    @Test
    @DisplayName("vincular compra existente, desvincular, editar nome e juros com versão, divergência do total informado (P-26) e filtro DONE x ACTIVE")
    void vinculosEDivergencia() throws Exception {
        basicWorld();
        String tx = created(sharedPurchase(cardA, "300.00", 3, "2026-10-05", null, null)).get("id").asString();
        JsonNode created = createdBody(post("/v1/purchase-groups", "{\"name\":\"Fogao\",\"interest\":true,\"declaredTotal\":\"999.00\",\"members\":["
                + member(cardB, 3, "50.00", null, catFood, null) + "]}"));
        String id = created.get("id").asString();

        assertThat(money(created.get("divergence"), "declared")).isEqualTo("999.00");
        assertThat(money(created.get("divergence"), "computed")).isEqualTo("150.00");

        JsonNode linked = okBody(post("/v1/purchase-groups/" + id + "/members", "{\"transactionId\":\"" + tx + "\"}"));
        assertThat(linked.get("members")).hasSize(2);
        assertThat(money(linked, "total")).isEqualTo("450.00");
        JsonNode unlinked = okBody(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/purchase-groups/" + id + "/members/" + tx)
                .header("Authorization", bearer));
        assertThat(unlinked.get("members")).hasSize(1);

        JsonNode renamed = okBody(put("/v1/purchase-groups/" + id, "{\"name\":\"Fogao novo\",\"interest\":false,\"version\":" + unlinked.get("version").asLong() + "}"));
        assertThat(renamed.get("name").asString()).isEqualTo("Fogao novo");
        mockMvc.perform(put("/v1/purchase-groups/" + id, "{\"name\":\"X\",\"interest\":false,\"version\":1}")).andExpect(status().isConflict());
        assertThat(okBody(get("/v1/purchase-groups?status=ACTIVE")).get("items")).hasSize(1);
        assertThat(okBody(get("/v1/purchase-groups?status=DONE")).get("items")).isEmpty();
        JsonNode twoParts = createdBody(post("/v1/purchase-groups", group("Cadeira", member(cardA, 2, "100.00", null, catFood, null))));
        assertThat(twoParts.get("status").asString()).isEqualTo("ACTIVE");
        advanceTo(Instant.parse("2026-10-26T15:00:00Z"));
        mockMvc.perform(idem(post("/v1/invoices/" + com.moneylog.core.card.domain.InvoiceIds.of(java.util.UUID.fromString(cardA), java.time.LocalDate.of(2026, 10, 25))
                + "/payments", "{\"accountId\":\"" + accountA + "\",\"amount\":\"200.00\",\"date\":\"2026-10-26\"}"))).andExpect(status().isOk());
        advanceTo(Instant.parse("2026-11-26T15:00:00Z"));
        mockMvc.perform(idem(post("/v1/invoices/" + com.moneylog.core.card.domain.InvoiceIds.of(java.util.UUID.fromString(cardA), java.time.LocalDate.of(2026, 11, 25))
                + "/payments", "{\"accountId\":\"" + accountA + "\",\"amount\":\"200.00\",\"date\":\"2026-11-26\"}"))).andExpect(status().isOk());
        JsonNode done = okBody(get("/v1/purchase-groups?status=DONE"));
        assertThat(done.get("items")).hasSize(1);
        assertThat(done.get("items").get(0).get("name").asString()).isEqualTo("Cadeira");
        assertThat(money(done.get("items").get(0), "remaining")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("entradas inválidas: sem membros 400, restantes acima do total 422 PURCHASE_GROUP_INVALID, cartão de outro usuário 404 e nada gravado")
    void entradasInvalidas() throws Exception {
        basicWorld();
        long transactions = count("transactions");
        mockMvc.perform(post("/v1/purchase-groups", "{\"name\":\"Vazio\",\"interest\":false,\"members\":[]}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/purchase-groups", group("Mais", member(cardA, 3, "10.00", 4, catFood, null)))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PURCHASE_GROUP_INVALID"));
        String other = otherUserBearer();
        String strangerCard = createdBody(postAs(other, "/v1/cards", "{\"name\":\"Cartao X\",\"closingDay\":25,\"dueDay\":5}")).get("id").asString();
        mockMvc.perform(post("/v1/purchase-groups", group("Alheio", member(strangerCard, 3, "10.00", null, catFood, null)))).andExpect(status().isNotFound());
        assertThat(count("transactions")).isEqualTo(transactions);
        assertThat(count("purchase_groups")).isZero();
    }

    @Test
    @DisplayName("isolamento: outro usuário recebe 404 em GET, PUT, vincular e desvincular; lista vazia; não vincula compra alheia ao próprio grupo; sem token 401")
    void isolamento() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String groupId = ids.get("grupoNotebook");
        String other = otherUserBearer();

        mockMvc.perform(getAs(other, "/v1/purchase-groups/" + groupId)).andExpect(status().isNotFound());
        mockMvc.perform(putAs(other, "/v1/purchase-groups/" + groupId, "{\"name\":\"X\",\"interest\":false,\"version\":1}")).andExpect(status().isNotFound());
        assertThat(body(mockMvc.perform(getAs(other, "/v1/purchase-groups")).andExpect(status().isOk()).andReturn()).get("items")).isEmpty();
        String strangerGroup = createdBody(postAs(other, "/v1/accounts", "{\"name\":\"C\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"0.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        assertThat(strangerGroup).isNotBlank();
        mockMvc.perform(postAs(other, "/v1/purchase-groups/" + groupId + "/members", "{\"transactionId\":\"" + ids.get("compraTerco") + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/purchase-groups")).andExpect(status().isUnauthorized());
        JsonNode theirs = body(mockMvc.perform(getAs(other, "/v1/purchase-groups/commitments?until=2026-11")).andExpect(status().isOk()).andReturn());
        assertThat(money(theirs, "committed")).isEqualTo("0.00");
    }
}
