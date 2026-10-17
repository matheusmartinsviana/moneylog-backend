package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

@DisplayName("H2: imóvel com cota por parcela, totais por pessoa, cláusulas e CSV (ADR-029)")
class RealEstateIT extends ResumaoItBase {

    private JsonNode person(JsonNode totals, String personId) {
        for (JsonNode line : totals) {
            if ((line.get("personId").isNull() && personId == null) || (!line.get("personId").isNull() && line.get("personId").asString().equals(personId))) {
                return line;
            }
        }
        throw new AssertionError("pessoa " + personId + " fora de totalsByPerson");
    }

    private String sharesBody(long version, String... shares) {
        return "{\"shares\":[" + String.join(",", shares) + "],\"version\":" + version + "}";
    }

    @Test
    @DisplayName("parcelas de 2.400, 6.500 e 3.500 com cotas: totalsByPerson mostra pago e pendente por pessoa; Σ por pessoa = valor dos itens; item sem cota é estimado e 100% meu")
    void totaisPorPessoa() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        JsonNode commitment = okBody(get("/v1/commitments/" + ids.get("imovel")));
        JsonNode totals = commitment.get("totalsByPerson");

        assertThat(money(person(totals, null), "pending")).isEqualTo("7300.00");
        assertThat(money(person(totals, ids.get("beto")), "pending")).isEqualTo("900.00");
        assertThat(money(person(totals, ids.get("ana")), "pending")).isEqualTo("4200.00");
        long sum = 0;
        for (JsonNode line : totals) {
            sum += cents(line.get("total"));
            assertThat(money(line, "paid")).isEqualTo("0.00");
        }
        assertThat(sum).isEqualTo(cents(commitment.get("total"))).isEqualTo(1240000L);
        assertThat(commitment.get("schedule").get(0).get("sharesDefined").asBoolean()).isTrue();
        assertThat(commitment.get("schedule").get(2).get("sharesDefined").asBoolean()).isFalse();
        JsonNode byPerson = okBody(get("/v1/commitments/" + ids.get("imovel") + "/by-person"));
        assertThat(byPerson.get("undefinedItems").get(0).asInt()).isEqualTo(3);
        assertThat(person(byPerson.get("people"), null).get("items").get(2).get("estimated").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("soma das cotas diferente do valor: 422 COMMITMENT_SHARES_INVALID na criação e no PUT das cotas, nada gravado; cota de pessoa de outro usuário é recusada")
    void cotasInvalidas() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode before = okBody(get("/v1/commitments/" + ids.get("imovel")));
        long version = before.get("version").asLong();

        mockMvc.perform(put("/v1/commitments/" + ids.get("imovel") + "/schedule/3/shares",
                sharesBody(version, amountShare(null, "1000.00"), amountShare(ids.get("ana"), "1000.00")))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMMITMENT_SHARES_INVALID"));
        mockMvc.perform(post("/v1/commitments", "{\"kind\":\"LONG_TERM_PROJECT\",\"name\":\"Invalido\",\"total\":\"1000.00\",\"schedule\":[{\"label\":\"P1\","
                + "\"type\":\"INSTALLMENT\",\"dueDate\":\"2026-12-10\",\"amount\":\"1000.00\",\"paid\":false,\"shares\":[" + amountShare(null, "400.00") + ","
                + amountShare(ids.get("ana"), "400.00") + "]}]}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMMITMENT_SHARES_INVALID"));
        String strangerPerson = createdBody(postAs(otherUserBearer(), "/v1/people", "{\"name\":\"Alheia\"}")).get("id").asString();
        mockMvc.perform(put("/v1/commitments/" + ids.get("imovel") + "/schedule/3/shares",
                sharesBody(version, amountShare(null, "1500.00"), amountShare(strangerPerson, "2000.00")))).andExpect(status().is4xxClientError());
        assertThat(okBody(get("/v1/commitments/" + ids.get("imovel")))).isEqualTo(before);
    }

    @Test
    @DisplayName("definir a cota do item 3 (eu 1.500,00 e Ana 2.000,00): sai do estimado, a soma por pessoa continua igual ao total e o resumo de janeiro passa a usar a minha cota")
    void definirCotaDoTerceiroItem() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        long version = okBody(get("/v1/commitments/" + ids.get("imovel"))).get("version").asLong();

        JsonNode updated = okBody(put("/v1/commitments/" + ids.get("imovel") + "/schedule/3/shares",
                sharesBody(version, amountShare(null, "1500.00"), amountShare(ids.get("ana"), "2000.00"))));

        assertThat(updated.get("schedule").get(2).get("sharesDefined").asBoolean()).isTrue();
        assertThat(money(person(updated.get("totalsByPerson"), ids.get("ana")), "pending")).isEqualTo("6200.00");
        assertThat(money(person(updated.get("totalsByPerson"), null), "pending")).isEqualTo("5300.00");
        JsonNode january = okBody(get("/v1/owner-statement?from=2027-01&months=1"));
        assertThat(money(january.get("months").get(0).get("outflow"), "realEstateMine")).isEqualTo("1500.00");
        assertThat(january.get("warnings").toString()).doesNotContain("REAL_ESTATE_SHARE_UNDEFINED");
    }

    @Test
    @DisplayName("marcar a parcela 1 como paga: a cota paga é a registrada (eu 1.500,00 e Beto 900,00), o pendente por pessoa cai e o razão não é tocado")
    void marcarPago() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode before = okBody(get("/v1/commitments/" + ids.get("imovel")));
        long ledger = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);

        JsonNode paid = okBody(post("/v1/commitments/" + ids.get("imovel") + "/schedule/1/pay", "{\"paidOn\":\"2026-10-15\",\"version\":" + before.get("version").asLong() + "}"));

        assertThat(money(person(paid.get("totalsByPerson"), null), "paid")).isEqualTo("1500.00");
        assertThat(money(person(paid.get("totalsByPerson"), ids.get("beto")), "paid")).isEqualTo("900.00");
        assertThat(money(person(paid.get("totalsByPerson"), ids.get("beto")), "pending")).isEqualTo("0.00");
        assertThat(money(paid, "paid")).isEqualTo("2400.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(ledger);
        assertThat(money(okBody(get("/v1/owner-statement?from=2026-11&months=1")).get("months").get(0).get("outflow"), "realEstateMine")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("item FINANCING fica fora do pendente direto (financingPending separado) e não entra na saída do resumo do mês")
    void financiamentoForaDoPendenteDireto() throws Exception {
        loadFixture("novembro-2026");
        JsonNode project = createdBody(post("/v1/commitments", "{\"kind\":\"LONG_TERM_PROJECT\",\"name\":\"Casa B\",\"total\":\"150000.00\",\"schedule\":["
                + "{\"label\":\"Entrada\",\"type\":\"DOWN_PAYMENT\",\"dueDate\":\"2026-11-20\",\"amount\":\"30000.00\",\"paid\":false},"
                + "{\"label\":\"Financiamento\",\"type\":\"FINANCING\",\"dueDate\":\"2027-06-10\",\"amount\":\"120000.00\",\"paid\":false}]}"));

        assertThat(money(project, "directPending")).isEqualTo("30000.00");
        assertThat(money(project, "financingPending")).isEqualTo("120000.00");
        JsonNode statement = okBody(get("/v1/owner-statement?from=2026-11&months=1"));
        assertThat(money(statement.get("months").get(0).get("outflow"), "realEstateMine")).isEqualTo("31500.00");
        JsonNode june = okBody(get("/v1/owner-statement?from=2027-06&months=1"));
        assertThat(money(june.get("months").get(0).get("outflow"), "realEstateMine")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("cláusulas (multa 200 bps, juros 100 bps a.m., retenção 700 bps) são guardadas e a retenção sobre o preço (868,00 de 12.400,00) vem calculada; valores fora de 0 a 10000: 400; PUT das cláusulas devolve o compromisso")
    void clausulas() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode commitment = okBody(get("/v1/commitments/" + ids.get("imovel")));

        assertThat(commitment.get("clauses").get("lateFeeBps").asInt()).isEqualTo(200);
        assertThat(commitment.get("clauses").get("monthlyInterestBps").asInt()).isEqualTo(100);
        assertThat(commitment.get("clauses").get("terminationRetentionBps").asInt()).isEqualTo(700);
        assertThat(money(commitment.get("clauses"), "terminationRetentionAmount")).isEqualTo("868.00");

        JsonNode changed = okBody(put("/v1/commitments/" + ids.get("imovel") + "/clauses", "{\"clauses\":{\"lateFeeBps\":300,\"monthlyInterestBps\":150,\"terminationRetentionBps\":1000,"
                + "\"notes\":\"Clausula sintetica\"},\"version\":" + commitment.get("version").asLong() + "}"));
        assertThat(money(changed.get("clauses"), "terminationRetentionAmount")).isEqualTo("1240.00");
        mockMvc.perform(put("/v1/commitments/" + ids.get("imovel") + "/clauses", "{\"clauses\":{\"lateFeeBps\":10001,\"monthlyInterestBps\":150,\"terminationRetentionBps\":1000},\"version\":"
                + changed.get("version").asLong() + "}")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("CSV por pessoa: cabeçalho, uma linha por parcela e pessoa, valores com vírgula, em text/csv; formato inválido 400")
    void csvPorPessoa() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        String csv = mockMvc.perform(get("/v1/commitments/" + ids.get("imovel") + "/by-person?format=csv")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(csv).contains("Posição;Descrição;Tipo;Vencimento;Pessoa;Valor;Pago");
        assertThat(csv.lines().count()).isEqualTo(1 + 3 + 2);
        assertThat(csv).contains("1500,00").contains("4200,00").contains("Eu").contains("Beto").contains("Ana");
        mockMvc.perform(get("/v1/commitments/" + ids.get("imovel") + "/by-person?format=pdf")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("compromisso antigo (TRACKED_THIRD_PARTY) segue como antes e não ganha cotas; imóvel: outro usuário recebe 404 em GET, cotas, cláusulas, by-person e CSV; sem token 401")
    void regressaoEIsolamento() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode tracked = createdBody(post("/v1/commitments", "{\"kind\":\"TRACKED_THIRD_PARTY\",\"name\":\"Consorcio do Beto\",\"counterparty\":\"Beto\","
                + "\"installmentsTotal\":10,\"installmentsPaid\":2,\"paidAmount\":\"400.00\",\"nextDueDate\":\"2026-11-10\"}"));
        assertThat(tracked.get("kind").asString()).isEqualTo("TRACKED_THIRD_PARTY");
        String other = otherUserBearer();
        String id = ids.get("imovel");

        mockMvc.perform(getAs(other, "/v1/commitments/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(putAs(other, "/v1/commitments/" + id + "/schedule/1/shares", sharesBody(1, amountShare(null, "2000.00")))).andExpect(status().isNotFound());
        mockMvc.perform(putAs(other, "/v1/commitments/" + id + "/clauses", "{\"clauses\":{\"lateFeeBps\":1,\"monthlyInterestBps\":1,\"terminationRetentionBps\":1},\"version\":0}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(getAs(other, "/v1/commitments/" + id + "/by-person")).andExpect(status().isNotFound());
        mockMvc.perform(getAs(other, "/v1/commitments/" + id + "/by-person?format=csv")).andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/commitments/" + id + "/by-person")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/commitments/" + UUID.randomUUID() + "/by-person")).andExpect(status().isNotFound());
    }
}
