package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

@DisplayName("D2: assinaturas com USD, câmbio por cobrança, IOF, custo mensal equivalente, empresa, ativo até e sobreposição")
class SubscriptionsIT extends ResumaoItBase {

    private JsonNode subscriptions(String query) throws Exception {
        return okBody(get("/v1/subscriptions" + query));
    }

    private JsonNode itemOf(JsonNode list, String description) {
        for (JsonNode item : list.get("items")) {
            if (item.get("recurrence").get("description").asString().equals(description)) {
                return item;
            }
        }
        throw new AssertionError("assinatura " + description + " ausente");
    }

    private String realize(String recurrenceId, String date, String body) throws Exception {
        var result = mockMvc.perform(idem(post("/v1/recurrences/" + recurrenceId + "/occurrences/" + date + "/realize", body))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asString();
    }

    @Test
    @DisplayName("USD 5,00 a 5,000000 com IOF 350 bps previstos = 25,88 (25,00 + 0,875 arredondado uma vez); semanal 5,30 = 22,97 por mês (5,30 x 52 / 12); sem acumular erro no total")
    void custoMensalEquivalente() throws Exception {
        loadFixture("novembro-2026");

        JsonNode list = subscriptions("?status=ACTIVE");

        assertThat(money(itemOf(list, "Servico USD"), "monthlyEquivalent")).isEqualTo("25.88");
        assertThat(money(itemOf(list, "Servico semanal"), "monthlyEquivalent")).isEqualTo("22.97");
        assertThat(money(itemOf(list, "Streaming"), "monthlyEquivalent")).isEqualTo("20.00");
        assertThat(money(list, "totalMonthly")).isEqualTo("98.85");
        assertThat(money(list, "totalMonthlyMine")).as("a assinatura da empresa fica fora de minha parte (P-10)").isEqualTo("68.85");
        for (JsonNode scope : list.get("byScope")) {
            assertThat(money(scope, "monthly")).isEqualTo(scope.get("scope").asString().equals("COMPANY") ? "30.00" : "68.85");
        }
        assertThat(itemOf(list, "Servico USD").get("recurrence").get("amount").get("amount").asString()).isEqualTo("25.88");
    }

    @Test
    @DisplayName("duas ativas no mesmo overlapGroup aparecem em overlaps (45,88/mês); uma só, não; encerrar uma desfaz a sobreposição")
    void sobreposicao() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        JsonNode list = subscriptions("?status=ACTIVE");
        assertThat(list.get("overlaps")).hasSize(1);
        assertThat(list.get("overlaps").get(0).get("group").asString()).isEqualTo("streaming");
        assertThat(list.get("overlaps").get(0).get("items")).hasSize(2);
        assertThat(money(list.get("overlaps").get(0), "monthly")).isEqualTo("45.88");

        mockMvc.perform(post("/v1/recurrences/" + ids.get("assinaturaStreaming") + "/deactivate", "{}")).andExpect(status().isOk());

        assertThat(subscriptions("?status=ACTIVE").get("overlaps")).isEmpty();
    }

    @Test
    @DisplayName("encerrar com activeUntil: as ocorrências até a data seguem previstas e depois não; devolve a recorrência atualizada; ENDED lista a encerrada e ACTIVE não")
    void ativoAte() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String weekly = ids.get("assinaturaSemanal");

        JsonNode ended = body(mockMvc.perform(post("/v1/recurrences/" + weekly + "/end", "{\"activeUntil\":\"2026-11-09\"}")).andExpect(status().isOk()).andReturn());

        assertThat(ended.get("id").asString()).isEqualTo(weekly);
        assertThat(ended.get("endDate").asString()).isEqualTo("2026-11-09");
        advanceTo(Instant.parse("2026-11-10T15:00:00Z"));
        JsonNode occurrences = okBody(get("/v1/recurrences/occurrences?from=2026-10-01&to=2026-12-31"));
        String last = "";
        for (JsonNode occurrence : occurrences.get("items")) {
            if (occurrence.get("recurrenceId").asString().equals(weekly)) {
                last = occurrence.get("date").asString();
            }
        }
        assertThat(last).isEqualTo("2026-11-09");
        assertThat(itemOf(subscriptions("?status=ENDED"), "Servico semanal").get("ended").asBoolean()).isTrue();
        assertThat(subscriptions("?status=ACTIVE").get("items").toString()).doesNotContain("Servico semanal");
        assertThat(subscriptions("?status=ALL").get("items")).hasSize(4);
        mockMvc.perform(post("/v1/recurrences/" + weekly + "/end", "{\"activeUntil\":\"2026-10-01\"}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/v1/recurrences/" + weekly + "/end", "{\"activeUntil\":\"2026-11-09\",\"endDate\":\"2026-11-10\"}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("câmbio por cobrança: a de outubro com 5,000000 vale 25,88 e a de novembro com 5,200000 vale 26,91, sem mexer na anterior; razão só em BRL; lastChargedAmount acompanha")
    void cambioPorCobranca() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        advanceTo(Instant.parse("2026-10-20T15:00:00Z"));
        String first = realize(ids.get("assinaturaUsd"), "2026-10-20", "{\"originalAmount\":{\"amount\":\"5.00\",\"currency\":\"USD\"},\"fxRate\":\"5.000000\",\"iofBps\":350}");
        advanceTo(Instant.parse("2026-11-20T15:00:00Z"));
        String second = realize(ids.get("assinaturaUsd"), "2026-11-20", "{\"originalAmount\":{\"amount\":\"5.00\",\"currency\":\"USD\"},\"fxRate\":\"5.200000\",\"iofBps\":350}");

        assertThat(jdbc.queryForObject("SELECT amount_cents FROM transactions WHERE id = ?::uuid", Long.class, first)).isEqualTo(2588L);
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM transactions WHERE id = ?::uuid", Long.class, second)).isEqualTo(2691L);
        assertThat(jdbc.queryForObject("SELECT currency FROM transactions WHERE id = ?::uuid", String.class, second)).isEqualTo("BRL");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT currency) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(1L);
        JsonNode firstTx = okBody(get("/v1/transactions/" + first));
        assertThat(money(firstTx, "amount")).isEqualTo("25.88");
        assertThat(firstTx.get("originalAmount").get("currency").asString()).isEqualTo("USD");
        assertThat(firstTx.get("fxRate").asString()).isEqualTo("5.000000");
        assertThat(money(itemOf(subscriptions("?status=ACTIVE"), "Servico USD"), "lastChargedAmount")).isEqualTo("26.91");
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("compra de cartão comum em USD usa os mesmos campos (USD 5,00 a 5,000000 + IOF 350 = 25,88) na prévia e na criação, em BRL no razão")
    void compraEmUsd() throws Exception {
        basicWorld();
        JsonNode preview = body(mockMvc.perform(post("/v1/cards/" + cardA + "/purchase-preview", "{\"amount\":\"1.00\",\"installments\":1,\"date\":\"2026-10-05\","
                + "\"originalAmount\":{\"amount\":\"5.00\",\"currency\":\"USD\"},\"fxRate\":\"5.000000\",\"iofBps\":350}")).andExpect(status().isOk()).andReturn());
        assertThat(money(preview, "total")).isEqualTo("25.88");

        JsonNode tx = created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1.00\",\"date\":\"2026-10-05\",\"description\":\"Servico em dolar\",\"cardId\":\"" + cardA
                + "\",\"categoryId\":\"" + catFood + "\",\"installments\":1,\"originalAmount\":{\"amount\":\"5.00\",\"currency\":\"USD\"},"
                + "\"fxRate\":\"5.000000\",\"iofBps\":350}");

        assertThat(money(tx, "amount")).isEqualTo("25.88");
        assertThat(book("CARD")).isEqualTo(-2588L);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("IOF padrão 350 bps com moeda estrangeira; câmbio sem moeda ou moeda sem câmbio: 4xx; fxRate com 7 casas: 400; EUR e BRL aceitos; recorrência sem campos novos continua igual")
    void validacoesDeMoedaEIof() throws Exception {
        basicWorld();
        String category = catFood;
        String base = "{\"type\":\"CARD_PURCHASE\",\"amount\":\"10.00\",\"description\":\"Servico\",\"categoryId\":\"" + category + "\",\"cardId\":\"" + cardA
                + "\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-11-05\"";
        JsonNode withDefaultIof = createdBody(post("/v1/recurrences", base + ",\"originalAmount\":{\"amount\":\"5.00\",\"currency\":\"USD\"},\"referenceFxRate\":\"5.000000\"}"));
        assertThat(money(withDefaultIof, "amount")).isEqualTo("25.88");
        assertThat(withDefaultIof.get("iofBps").asInt()).isEqualTo(350);
        mockMvc.perform(post("/v1/recurrences", base + ",\"originalAmount\":{\"amount\":\"5.00\",\"currency\":\"USD\"}}")).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/v1/recurrences", base + ",\"referenceFxRate\":\"5.000000\"}")).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/v1/recurrences", base + ",\"originalAmount\":{\"amount\":\"5.00\",\"currency\":\"USD\"},\"referenceFxRate\":\"5.1234567\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/recurrences", base + ",\"originalAmount\":{\"amount\":\"5.00\",\"currency\":\"XXX\"},\"referenceFxRate\":\"5.000000\"}"))
                .andExpect(status().is4xxClientError());
        JsonNode plain = createdBody(post("/v1/recurrences", base + "}"));
        assertThat(money(plain, "amount")).isEqualTo("10.00");
        assertThat(plain.get("scope").asString()).isEqualTo("PERSONAL");
        assertThat(subscriptions("?status=ALL").get("items")).as("só entra em assinaturas se marcada").isEmpty();
    }

    @Test
    @DisplayName("assinaturas: outro usuário vê lista vazia e recebe 404 ao encerrar a de outro; filtro inválido 400; sem token 401")
    void isolamento() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String other = otherUserBearer();

        assertThat(body(mockMvc.perform(getAs(other, "/v1/subscriptions")).andExpect(status().isOk()).andReturn()).get("items")).isEmpty();
        mockMvc.perform(postAs(other, "/v1/recurrences/" + ids.get("assinaturaUsd") + "/end", "{}")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/subscriptions?status=NADA")).andExpect(status().isBadRequest());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/subscriptions")).andExpect(status().isUnauthorized());
    }
}
