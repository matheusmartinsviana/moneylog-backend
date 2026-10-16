package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

@DisplayName("B2b/B2c: resumo do mês (owner-statement) de novembro/2026 com a fixture sintética")
class OwnerStatementNovemberIT extends ResumaoItBase {

    private JsonNode november() throws Exception {
        return okBody(get("/v1/owner-statement?from=2026-11&months=1")).get("months").get(0);
    }

    private JsonNode outflow(JsonNode month) {
        return month.get("outflow");
    }

    private void realize(String recurrenceId, String date, String body) throws Exception {
        mockMvc.perform(idem(post("/v1/recurrences/" + recurrenceId + "/occurrences/" + date + "/realize", body))).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("composição de novembro bate com a conta independente: cartões 569,52 (bruto 716,18), assinaturas 51,18, fora do cartão 800,00 fixo + 120,00 variável, imóvel 1.500,00, saídas 2.989,52")
    void composicaoDeNovembro() throws Exception {
        loadFixture("novembro-2026");

        JsonNode month = november();
        JsonNode out = outflow(month);

        assertThat(money(out, "cardsMine")).isEqualTo("569.52");
        assertThat(money(out, "cardsGross")).isEqualTo("716.18");
        assertThat(money(out, "subscriptionsMine")).isEqualTo("51.18");
        assertThat(money(out.get("offCard"), "fixed")).isEqualTo("800.00");
        assertThat(money(out.get("offCard"), "variable")).isEqualTo("120.00");
        assertThat(money(out, "realEstateMine")).isEqualTo("1500.00");
        assertThat(money(out, "total")).isEqualTo("2989.52");
        assertThat(money(month, "companyReimbursable")).isEqualTo("30.00");
        assertThat(cents(out.get("total"))).isEqualTo(cents(out.get("cardsMine")) + cents(out.get("offCard").get("fixed"))
                + cents(out.get("offCard").get("variable")) + cents(out.get("realEstateMine")));
        long byMethod = 0;
        for (JsonNode method : out.get("offCard").get("byMethod")) {
            byMethod += cents(method.get("amount"));
        }
        assertThat(byMethod).isEqualTo(cents(out.get("offCard").get("fixed")) + cents(out.get("offCard").get("variable")));
    }

    @Test
    @DisplayName("renda de novembro = competência de outubro (leitura A): 5.742,77 líquidos em 2.297,10 (16/10) + 3.445,67 (01/11), consignado 300,00 aplicado à dívida fora da sobra")
    void rendaDaCompetenciaAnterior() throws Exception {
        loadFixture("novembro-2026");

        JsonNode month = november();
        JsonNode income = month.get("income");

        assertThat(money(income, "total")).isEqualTo("5742.77");
        assertThat(money(income, "receivedInAccount")).isEqualTo("5742.77");
        assertThat(money(income, "appliedToDebt")).isEqualTo("300.00");
        long items = 0;
        for (JsonNode item : income.get("items")) {
            items += cents(item.get("amount"));
            assertThat(item.get("confidence").asString()).isEqualTo("CONFIRMED");
        }
        assertThat(items).isEqualTo(cents(income.get("total")));
        assertThat(money(month, "surplus")).isEqualTo("2753.25");
        assertThat(cents(month.get("surplus"))).isEqualTo(cents(income.get("total")) - cents(outflow(month).get("total")));
    }

    @Test
    @DisplayName("saldo inicial = contas CASH (9.000,00), aviso OPENING_BALANCE_NOT_PROJECTED, endBalance = abertura + sobra; cardsGross não altera a sobra")
    void saldoAcumulado() throws Exception {
        loadFixture("novembro-2026");

        JsonNode statement = okBody(get("/v1/owner-statement?from=2026-11&months=1"));
        JsonNode month = statement.get("months").get(0);

        assertThat(money(statement, "openingBalance")).isEqualTo("9000.00");
        assertThat(statement.get("warnings").toString()).contains("OPENING_BALANCE_NOT_PROJECTED");
        assertThat(cents(month.get("endBalance"))).isEqualTo(cents(statement.get("openingBalance")) + cents(month.get("surplus")));
        assertThat(month.get("negative").asBoolean()).isFalse();
        assertThat(statement.get("negativeMonths")).isEmpty();
        assertThat(statement.get("view").asString()).isEqualTo("MINE");
        assertThat(statement.get("incomeLagMonths").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a receber pendente do mês (Ana 50,00 + 33,33 e Beto 33,33 em novembro) aparece em receivablesPending e nunca em renda nem no saldo")
    void receivablesPendingDeNovembro() throws Exception {
        loadFixture("novembro-2026");

        JsonNode month = november();

        assertThat(money(month, "receivablesPending")).isEqualTo("116.66");
        assertThat(cents(month.get("endBalance"))).isEqualTo(900000L + cents(month.get("surplus")));
        assertThat(money(month.get("income"), "total")).isEqualTo("5742.77");
    }

    @Test
    @DisplayName("editar o mês previsto da parcela 1 da Ana para dezembro tira os 50,00 de novembro (66,66) e põe em dezembro; o saldo e a renda não mudam")
    void receivablesPendingAposEditarOMesPrevisto() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String tx = ids.get("compraMetade");
        JsonNode installment = null;
        for (JsonNode person : okBody(get("/v1/debts/third-party-receivables")).get("people")) {
            if (person.get("personId").asString().equals(ids.get("ana"))) {
                for (JsonNode item : person.get("items")) {
                    if (item.get("refId").asString().equals(tx)) {
                        installment = item.get("installments").get(0);
                    }
                }
            }
        }
        long endBefore = cents(november().get("endBalance"));

        mockMvc.perform(put("/v1/third-party-receivables/" + tx + "/installments/" + installment.get("installmentId").asString() + "/people/"
                + ids.get("ana") + "/expected-month", "{\"expectedMonth\":\"2026-12\"}")).andExpect(status().isOk());

        JsonNode month = november();
        assertThat(money(month, "receivablesPending")).isEqualTo("66.66");
        assertThat(cents(month.get("endBalance"))).isEqualTo(endBefore);
        assertThat(money(month.get("income"), "total")).isEqualTo("5742.77");
        JsonNode december = okBody(get("/v1/owner-statement?from=2026-12&months=1")).get("months").get(0);
        assertThat(money(december, "receivablesPending")).isEqualTo("100.00");
    }

    @Test
    @DisplayName("dedup das assinaturas: lançar cada ocorrência prevista não muda cardsMine/cardsGross/subscriptionsMine (cada uma conta uma vez, antes e depois de lançada)")
    void dedupDeAssinaturasAntesEDepoisDeLancada() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode before = outflow(november());
        advanceTo(Instant.parse("2026-10-23T15:00:00Z"));

        realize(ids.get("assinaturaUsd"), "2026-10-20", "{\"originalAmount\":{\"amount\":\"5.00\",\"currency\":\"USD\"},\"fxRate\":\"5.000000\",\"iofBps\":350}");
        realize(ids.get("assinaturaSemanal"), "2026-10-19", "{}");
        realize(ids.get("assinaturaEmpresa"), "2026-10-22", "{}");
        realize(ids.get("assinaturaStreaming"), "2026-10-21", "{}");

        JsonNode after = outflow(november());
        assertThat(money(after, "cardsMine")).isEqualTo(money(before, "cardsMine"));
        assertThat(money(after, "cardsGross")).isEqualTo(money(before, "cardsGross"));
        assertThat(money(after, "subscriptionsMine")).isEqualTo(money(before, "subscriptionsMine"));
        assertThat(money(after, "total")).isEqualTo(money(before, "total"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ? AND source = 'RECURRENCE'", Long.class, userId)).isEqualTo(4L);
    }

    @Test
    @DisplayName("lançar a assinatura em USD com câmbio diferente (5,2): substitui a previsão, sem somar as duas (cardsMine sobe só 1,03: 26,91 - 25,88)")
    void cambioDiferenteSubstituiAPrevisao() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        long before = cents(outflow(november()).get("cardsMine"));
        advanceTo(Instant.parse("2026-10-23T15:00:00Z"));

        realize(ids.get("assinaturaUsd"), "2026-10-20", "{\"originalAmount\":{\"amount\":\"5.00\",\"currency\":\"USD\"},\"fxRate\":\"5.200000\",\"iofBps\":350}");

        assertThat(cents(outflow(november()).get("cardsMine"))).isEqualTo(before + 103L);
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM transactions WHERE user_id = ? AND source = 'RECURRENCE'", Long.class, userId)).isEqualTo(2691L);
    }

    @Test
    @DisplayName("fatura fechada e lançada conta no mês do vencimento (novembro) e não em outubro; transferência entre contas não é saída")
    void faturaNoVencimentoETransferencia() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode base = outflow(november());
        advanceTo(Instant.parse("2026-10-26T15:00:00Z"));

        String savings = createdBody(post("/v1/accounts", "{\"name\":\"Reserva\",\"type\":\"SAVINGS\",\"openingBalance\":{\"amount\":\"0.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        created("{\"type\":\"TRANSFER\",\"amount\":\"500.00\",\"date\":\"2026-10-26\",\"description\":\"Guardar\",\"fromAccountId\":\"" + ids.get("contaA")
                + "\",\"toAccountId\":\"" + savings + "\"}");

        JsonNode nov = outflow(november());
        JsonNode oct = okBody(get("/v1/owner-statement?from=2026-10&months=1")).get("months").get(0).get("outflow");
        assertThat(money(nov, "cardsMine")).isNotEqualTo("0.00");
        assertThat(money(nov, "total")).isEqualTo(money(base, "total"));
        assertThat(cents(oct.get("cardsMine"))).as("a fatura fechada em outubro vence em novembro (P-3)").isLessThan(cents(nov.get("cardsMine")));
    }

    @Test
    @DisplayName("imóvel: sem cota o valor cheio e o aviso REAL_ESTATE_SHARE_UNDEFINED (janeiro: 3.500,00); com cota a minha parte (dezembro: 2.300,00)")
    void imovelComESemCota() throws Exception {
        loadFixture("novembro-2026");

        JsonNode december = okBody(get("/v1/owner-statement?from=2026-12&months=1"));
        JsonNode january = okBody(get("/v1/owner-statement?from=2027-01&months=1"));

        assertThat(money(december.get("months").get(0).get("outflow"), "realEstateMine")).isEqualTo("2300.00");
        assertThat(december.get("warnings").toString()).doesNotContain("REAL_ESTATE_SHARE_UNDEFINED");
        assertThat(money(january.get("months").get(0).get("outflow"), "realEstateMine")).isEqualTo("3500.00");
        assertThat(january.get("warnings").toString()).contains("REAL_ESTATE_SHARE_UNDEFINED");
    }

    @Test
    @DisplayName("mês negativo e sem renda: negative=true, negativeMonths com o mês, INCOME_MISSING; com 3 meses o saldo encadeia endBalance(m) = endBalance(m-1) + sobra(m)")
    void mesNegativoEEncadeamento() throws Exception {
        basicWorld();
        created(sharedPurchase(cardA, "600.00", 3, "2026-10-05", null, null));

        JsonNode statement = okBody(get("/v1/owner-statement?from=2026-11&months=3"));

        JsonNode first = statement.get("months").get(0);
        assertThat(first.get("negative").asBoolean()).isTrue();
        assertThat(money(first.get("income"), "total")).isEqualTo("0.00");
        assertThat(statement.get("negativeMonths").get(0).asString()).isEqualTo("2026-11");
        assertThat(statement.get("warnings").toString()).contains("INCOME_MISSING");
        long running = cents(statement.get("openingBalance"));
        for (JsonNode month : statement.get("months")) {
            running += cents(month.get("surplus"));
            assertThat(cents(month.get("endBalance"))).isEqualTo(running);
            assertThat(month.get("negative").asBoolean()).isEqualTo(cents(month.get("surplus")) < 0);
        }
        assertThat(statement.get("months")).hasSize(3);
    }

    @Test
    @DisplayName("parâmetros inválidos 400 (months 0 e 13, from fora da janela, asOf inválido); sem token 401; outro usuário enxerga só os próprios dados (vazio)")
    void parametrosEIsolamento() throws Exception {
        loadFixture("novembro-2026");
        mockMvc.perform(get("/v1/owner-statement?from=2026-11&months=0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/owner-statement?from=2026-11&months=13")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OWNER_STATEMENT_PERIOD_INVALID"));
        mockMvc.perform(get("/v1/owner-statement?from=1999-12&months=1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/owner-statement?from=2026-13&months=1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/owner-statement?from=2026-11&months=1&asOf=ontem")).andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/owner-statement?from=2026-11&months=1")).andExpect(status().isUnauthorized());

        JsonNode theirs = body(mockMvc.perform(getAs(otherUserBearer(), "/v1/owner-statement?from=2026-11&months=1")).andExpect(status().isOk()).andReturn());
        assertThat(money(theirs, "openingBalance")).isEqualTo("0.00");
        assertThat(money(theirs.get("months").get(0).get("outflow"), "total")).isEqualTo("0.00");
        assertThat(money(theirs.get("months").get(0).get("income"), "total")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("from no passado (setembro): abre no saldo do fim de agosto (1.000,00) e fecha em 900,00, o saldo do fim de setembro, sem somar de novo a sobra já realizada no caixa de hoje")
    void fromNoPassadoNaoDuplicaASobra() throws Exception {
        accountA = createdBody(post("/v1/accounts", "{\"name\":\"Conta A\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1000.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-08-01\"}")).get("id").asString();
        catFood = createdBody(post("/v1/categories", "{\"name\":\"Mercado\",\"kind\":\"EXPENSE\"}")).get("id").asString();
        created("{\"type\":\"EXPENSE\",\"amount\":\"100.00\",\"date\":\"2026-09-10\",\"description\":\"Gasto de setembro\",\"categoryId\":\"" + catFood
                + "\",\"accountId\":\"" + accountA + "\",\"method\":\"PIX\"}");

        JsonNode statement = okBody(get("/v1/owner-statement?from=2026-09&months=1"));
        JsonNode month = statement.get("months").get(0);

        assertThat(money(statement, "openingBalance")).as("saldo no fim de agosto").isEqualTo("1000.00");
        assertThat(money(month.get("outflow"), "total")).isEqualTo("100.00");
        assertThat(money(month, "endBalance")).as("fim de setembro = 900,00 (o caixa de hoje já tem a saída)").isEqualTo("900.00");
    }

    @Test
    @DisplayName("from no passado atravessando o mês corrente: setembro fecha em 900,00 e outubro (corrente) em 700,00, o caixa de hoje, sem ignorar o que já foi pago no mês")
    void fromNoPassadoAtravessandoOMesCorrente() throws Exception {
        advanceTo(OCT_15);
        accountA = createdBody(post("/v1/accounts", "{\"name\":\"Conta A\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1000.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-08-01\"}")).get("id").asString();
        catFood = createdBody(post("/v1/categories", "{\"name\":\"Mercado\",\"kind\":\"EXPENSE\"}")).get("id").asString();
        created("{\"type\":\"EXPENSE\",\"amount\":\"100.00\",\"date\":\"2026-09-10\",\"description\":\"Gasto de setembro\",\"categoryId\":\"" + catFood
                + "\",\"accountId\":\"" + accountA + "\",\"method\":\"PIX\"}");
        created("{\"type\":\"EXPENSE\",\"amount\":\"200.00\",\"date\":\"2026-10-05\",\"description\":\"Gasto de outubro\",\"categoryId\":\"" + catFood
                + "\",\"accountId\":\"" + accountA + "\",\"method\":\"PIX\"}");

        JsonNode statement = okBody(get("/v1/owner-statement?from=2026-09&months=3"));

        assertThat(money(statement, "openingBalance")).as("saldo no fim de agosto").isEqualTo("1000.00");
        assertThat(money(statement.get("months").get(0), "endBalance")).as("fim de setembro").isEqualTo("900.00");
        assertThat(money(statement.get("months").get(1), "endBalance")).as("fim de outubro = caixa de hoje").isEqualTo("700.00");
        assertThat(money(statement.get("months").get(2), "endBalance")).as("novembro sem movimento previsto").isEqualTo("700.00");
    }
}
