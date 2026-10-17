package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

@DisplayName("G2: ativos não disponíveis (FGTS) e reserva de emergência (ADR-027)")
class AssetsReserveIT extends ResumaoItBase {

    @BeforeEach
    void mundo() throws Exception {
        basicWorld();
    }

    private String asset(String kind, String name, String balance, String availability, boolean countsAsReserve) {
        return "{\"kind\":\"" + kind + "\",\"name\":\"" + name + "\",\"balance\":\"" + balance + "\",\"balanceDate\":\"2026-10-10\",\"availability\":\""
                + availability + "\",\"countsAsReserve\":" + countsAsReserve + "}";
    }

    private JsonNode overview(String query) throws Exception {
        return okBody(get("/v1/net-worth/overview" + query));
    }

    @Test
    @DisplayName("FGTS de 10.000,00 aparece em reservedAssets e não em availableCash nem na reserva; o razão não muda e o FGTS não é renda nem entra no resumo do mês")
    void fgtsNuncaEDisponivelNemReserva() throws Exception {
        long ledger = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);
        JsonNode statementBefore = okBody(get("/v1/owner-statement?from=2026-11&months=1"));
        JsonNode before = overview("");

        createdBody(post("/v1/assets", asset("FGTS", "FGTS Empresa Exemplo", "10000.00", "RESERVED", false)));

        JsonNode after = overview("");
        assertThat(money(after, "reservedAssets")).isEqualTo("10000.00");
        assertThat(money(after, "availableCash")).isEqualTo(money(before, "availableCash")).isEqualTo("10000.00");
        assertThat(money(after.get("emergencyReserve"), "amount")).isEqualTo("0.00");
        assertThat(money(after, "availableAssets")).isEqualTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(ledger);
        assertThat(okBody(get("/v1/owner-statement?from=2026-11&months=1"))).isEqualTo(statementBefore);
        assertThat(book("INCOME")).isZero();
    }

    @Test
    @DisplayName("FGTS nunca conta como reserva: AVAILABLE ou countsAsReserve em FGTS é 422; countsAsReserve em ativo RESERVED também; saldo negativo recusado")
    void fgtsNaoPodeSerReserva() throws Exception {
        mockMvc.perform(post("/v1/assets", asset("FGTS", "FGTS", "100.00", "AVAILABLE", false))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/v1/assets", asset("FGTS", "FGTS", "100.00", "RESERVED", true))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/v1/assets", asset("OTHER", "Bloqueado", "100.00", "RESERVED", true))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/v1/assets", asset("OTHER", "Negativo", "-1.00", "AVAILABLE", false))).andExpect(status().is4xxClientError());
        assertThat(count("assets")).isZero();
    }

    @Test
    @DisplayName("reserva de 3.000,00 sobre base fixa de 1.000,00 do mês: coverageMonths 3.0; com targetMonths 6 faltam 3.000,00; sem base fixa a cobertura é nula")
    void coberturaDaReserva() throws Exception {
        createdBody(post("/v1/recurrences", "{\"type\":\"EXPENSE\",\"amount\":\"1000.00\",\"description\":\"Aluguel\",\"categoryId\":\"" + catFood + "\",\"accountId\":\""
                + accountA + "\",\"method\":\"BOLETO\",\"nature\":\"FIXED\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-11-10\"}"));
        assertThat(overview("?month=2026-11").get("emergencyReserve").get("coverageMonths").asString()).isEqualTo("0.0");
        createdBody(post("/v1/assets", asset("OTHER", "Reserva em CDB", "3000.00", "AVAILABLE", true)));

        JsonNode reserve = overview("?month=2026-11&targetMonths=6").get("emergencyReserve");

        assertThat(money(reserve, "amount")).isEqualTo("3000.00");
        assertThat(money(reserve, "monthlyBase")).isEqualTo("1000.00");
        assertThat(reserve.get("coverageMonths").asString()).isEqualTo("3.0");
        assertThat(reserve.get("targetMonths").asInt()).isEqualTo(6);
        assertThat(money(reserve, "targetAmount")).isEqualTo("6000.00");
        assertThat(money(reserve, "missingToTarget")).isEqualTo("3000.00");
        assertThat(money(overview("?month=2026-11"), "availableAssets")).isEqualTo("3000.00");
        assertThat(overview("?month=2026-10").get("emergencyReserve").get("coverageMonths").isNull()).isTrue();
    }

    @Test
    @DisplayName("atualizar saldo preserva o histórico (duas linhas), data anterior só entra no histórico, não gera lançamento, e o ativo volta atualizado")
    void atualizarSaldoPreservaHistorico() throws Exception {
        JsonNode created = createdBody(post("/v1/assets", asset("FGTS", "FGTS", "10000.00", "RESERVED", false)));
        String id = created.get("id").asString();
        long ledger = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);

        JsonNode updated = okBody(post("/v1/assets/" + id + "/balance", "{\"balance\":\"10500.00\",\"date\":\"2026-10-12\"}"));
        assertThat(money(updated, "balance")).isEqualTo("10500.00");
        assertThat(updated.get("history")).hasSize(2);
        JsonNode older = okBody(post("/v1/assets/" + id + "/balance", "{\"balance\":\"9000.00\",\"date\":\"2026-09-01\"}"));
        assertThat(money(older, "balance")).as("data anterior à última só vai ao histórico").isEqualTo("10500.00");
        assertThat(older.get("history")).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(ledger);
        assertThat(money(overview(""), "reservedAssets")).isEqualTo("10500.00");
        mockMvc.perform(post("/v1/assets/" + id + "/balance", "{\"balance\":\"-1.00\",\"date\":\"2026-10-12\"}")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("arquivar o ativo tira o valor do patrimônio e bloqueia o saldo (ASSET_ARCHIVED); editar com versão atual funciona e a antiga dá 409")
    void arquivarEEditar() throws Exception {
        JsonNode created = createdBody(post("/v1/assets", asset("OTHER", "Poupanca extra", "500.00", "AVAILABLE", false)));
        String id = created.get("id").asString();

        JsonNode edited = okBody(put("/v1/assets/" + id, "{\"kind\":\"OTHER\",\"name\":\"Poupanca renomeada\",\"availability\":\"AVAILABLE\",\"version\":"
                + created.get("version").asLong() + "}"));
        assertThat(edited.get("name").asString()).isEqualTo("Poupanca renomeada");
        mockMvc.perform(put("/v1/assets/" + id, "{\"kind\":\"OTHER\",\"name\":\"Velha\",\"availability\":\"AVAILABLE\",\"version\":" + created.get("version").asLong() + "}"))
                .andExpect(status().isConflict());
        assertThat(money(overview(""), "availableAssets")).isEqualTo("500.00");

        mockMvc.perform(MockMvcRequestBuilders.delete("/v1/assets/" + id).header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().is2xxSuccessful());

        assertThat(money(overview(""), "availableAssets")).isEqualTo("0.00");
        mockMvc.perform(post("/v1/assets/" + id + "/balance", "{\"balance\":\"1.00\",\"date\":\"2026-10-12\"}")).andExpect(status().isUnprocessableEntity());
        assertThat(okBody(get("/v1/assets?status=ARCHIVED")).get("items")).hasSize(1);
        assertThat(okBody(get("/v1/assets")).get("items")).isEmpty();
    }

    @Test
    @DisplayName("sem ativos o resultado é o de sempre; limite de cartão não é disponível; availableCash é o saldo das contas CASH; mês inválido 400")
    void semAtivosEValidacoes() throws Exception {
        JsonNode overview = overview("");
        assertThat(money(overview, "reservedAssets")).isEqualTo("0.00");
        assertThat(money(overview, "availableAssets")).isEqualTo("0.00");
        assertThat(money(overview, "availableCash")).isEqualTo("10000.00");
        assertThat(money(overview, "availableCash")).isEqualTo(money(okBody(get("/v1/dashboard/summary")).get("balances"), "inAccounts"));
        mockMvc.perform(get("/v1/net-worth/overview?month=2026-13")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/net-worth/overview?targetMonths=61")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("isolamento: outro usuário recebe 404 em GET, PUT, balance e DELETE, lista vazia e visão zerada; sem token 401")
    void isolamento() throws Exception {
        String id = createdBody(post("/v1/assets", asset("FGTS", "FGTS", "10000.00", "RESERVED", false))).get("id").asString();
        String other = otherUserBearer();

        mockMvc.perform(getAs(other, "/v1/assets/" + id)).andExpect(status().isNotFound());
        mockMvc.perform(putAs(other, "/v1/assets/" + id, "{\"kind\":\"FGTS\",\"name\":\"x\",\"availability\":\"RESERVED\",\"version\":1}")).andExpect(status().isNotFound());
        mockMvc.perform(postAs(other, "/v1/assets/" + id + "/balance", "{\"balance\":\"1.00\",\"date\":\"2026-10-12\"}")).andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.delete("/v1/assets/" + id).header(HttpHeaders.AUTHORIZATION, other)).andExpect(status().isNotFound());
        assertThat(body(mockMvc.perform(getAs(other, "/v1/assets")).andExpect(status().isOk()).andReturn()).get("items")).isEmpty();
        assertThat(money(body(mockMvc.perform(getAs(other, "/v1/net-worth/overview")).andExpect(status().isOk()).andReturn()), "reservedAssets")).isEqualTo("0.00");
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/assets")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/net-worth/overview")).andExpect(status().isUnauthorized());
    }
}
