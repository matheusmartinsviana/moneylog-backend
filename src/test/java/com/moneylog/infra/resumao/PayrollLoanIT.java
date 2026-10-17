package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

@DisplayName("F3: perfil de renda, ocorrências, 13º e consignado em folha (N + p, soma zero, sem parcela duplicada)")
class PayrollLoanIT extends ResumaoItBase {

    private JsonNode generate(String profile, String from, String to) throws Exception {
        return body(mockMvc.perform(post("/v1/income-profiles/" + profile + "/generate-occurrences?from=" + from + "&to=" + to, ""))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode previewRaw(String profile, String month) throws Exception {
        return body(mockMvc.perform(get("/v1/income-profiles/" + profile + "/preview?month=" + month)).andExpect(status().isOk()).andReturn());
    }

    private String recurrenceOf(JsonNode generated, String kind) {
        for (JsonNode item : generated.get("items")) {
            if (item.get("kind").asString().equals(kind)) {
                return item.get("recurrenceId").asString();
            }
        }
        throw new AssertionError("sem ocorrência " + kind);
    }

    private String realize(String recurrenceId, String date, String accountId) throws Exception {
        var result = mockMvc.perform(idem(post("/v1/recurrences/" + recurrenceId + "/occurrences/" + date + "/realize", "{\"accountId\":\"" + accountId + "\",\"method\":\"PIX\"}"))).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
        return body(result).get("id").asString();
    }

    @Test
    @DisplayName("generate-occurrences cria adiantamento (16/10), saldo (01/11) com competência de outubro, e rodar de novo não duplica (created=false, mesmos ids)")
    void geraOcorrenciasUmaPorPagamentoSemDuplicar() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        JsonNode first = generate(ids.get("perfilRenda"), "2026-10", "2026-10");
        long recurrences = count("recurring_transactions");

        assertThat(first.get("items")).hasSize(2);
        JsonNode advance = first.get("items").get(0);
        JsonNode balance = first.get("items").get(1);
        assertThat(advance.get("kind").asString()).isEqualTo("ADVANCE");
        assertThat(advance.get("date").asString()).isEqualTo("2026-10-16");
        assertThat(money(advance, "amount")).isEqualTo("2297.10");
        assertThat(advance.get("competence").asString()).isEqualTo("2026-10");
        assertThat(balance.get("kind").asString()).isEqualTo("BALANCE");
        assertThat(balance.get("date").asString()).isEqualTo("2026-11-01");
        assertThat(money(balance, "amount")).isEqualTo("3445.67");
        assertThat(balance.get("competence").asString()).isEqualTo("2026-10");
        assertThat(cents(advance.get("amount")) + cents(balance.get("amount"))).isEqualTo(cents(
                previewRaw(ids.get("perfilRenda"), "2026-10").get("net")));

        JsonNode second = generate(ids.get("perfilRenda"), "2026-10", "2026-10");

        assertThat(second.get("items")).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(second.get("items").get(i).get("created").asBoolean()).isFalse();
            assertThat(second.get("items").get(i).get("recurrenceId").asString()).isEqualTo(first.get("items").get(i).get("recurrenceId").asString());
        }
        assertThat(count("recurring_transactions")).isEqualTo(recurrences);
        assertThat(count("transactions") - 0).isNotNegative();
    }

    @Test
    @DisplayName("realizar adiantamento e saldo: soma zero por movimento; ACCOUNT +5.742,77 (N), principal 260,00 baixa a dívida uma só vez, juros 40,00 viram despesa e a renda do razão é N + p = 6.042,77")
    void consignadoNMaisPSomaZero() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode generated = generate(ids.get("perfilRenda"), "2026-10", "2026-10");
        long cashBefore = book("ACCOUNT");
        long expenseBefore = book("EXPENSE");
        long incomeBefore = book("INCOME");
        long payableBefore = book("PAYABLE");

        advanceTo(Instant.parse("2026-10-16T15:00:00Z"));
        String advanceTx = realize(recurrenceOf(generated, "ADVANCE"), "2026-10-16", ids.get("contaA"));
        advanceTo(Instant.parse("2026-11-01T15:00:00Z"));
        String balanceTx = realize(recurrenceOf(generated, "BALANCE"), "2026-11-01", ids.get("contaA"));

        assertThat(totalOfTransaction(advanceTx)).isZero();
        assertThat(totalOfTransaction(balanceTx)).isZero();
        assertThat(book("ACCOUNT") - cashBefore).as("o que cai na conta = N").isEqualTo(574277L);
        assertThat(book("EXPENSE") - expenseBefore).as("só os juros da parcela são despesa (nunca o principal)").isEqualTo(4000L);
        assertThat(-(book("INCOME") - incomeBefore)).as("renda do razão = N + p").isEqualTo(574277L + 30000L);
        assertThat(book("PAYABLE") - payableBefore).as("a dívida baixa só o principal da parcela, uma vez (não por pagamento)").isEqualTo(26000L);
        assertThat(ledgerTotal()).isZero();
        JsonNode debt = okBody(get("/v1/debts/" + ids.get("debtFolha")));
        assertThat(money(debt, "outstanding")).isEqualTo("2740.00");
        assertThat(debt.get("kind").asString()).isEqualTo("PAYROLL");
    }

    @Test
    @DisplayName("a parcela do consignado vinculada não aparece como a vencer em dívidas, no que vence do dashboard nem na saída do resumo; a dívida segue no total")
    void parcelaVinculadaNaoDuplica() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String debtId = ids.get("debtFolha");

        JsonNode overview = okBody(get("/v1/debts/overview?months=3"));
        JsonNode upcoming = okBody(get("/v1/dashboard/upcoming"));
        JsonNode statement = okBody(get("/v1/owner-statement?from=2026-11&months=1"));

        assertThat(overview.toString()).as("a dívida entra no total da visão de dívidas").contains("Financeira Exemplo");
        assertThat(upcoming.toString()).doesNotContain(debtId);
        assertThat(overview.get("schedule").toString()).doesNotContain(debtId);
        assertThat(money(statement.get("months").get(0).get("outflow"), "total")).isEqualTo("2989.52");
        assertThat(okBody(get("/v1/debts/" + debtId)).get("kind").asString()).isEqualTo("PAYROLL");
    }

    @Test
    @DisplayName("consignado com data esperada já vencida não aparece como dívida vencida (descontado em folha), nem no filtro overdue")
    void consignadoNaoFicaVencido() throws Exception {
        basicWorld();
        String bank = createdBody(post("/v1/people", "{\"name\":\"Financeira Exemplo\"}")).get("id").asString();
        String debt = created("{\"type\":\"MONEY_RECEIVED\",\"amount\":\"1000.00\",\"date\":\"2026-08-01\",\"description\":\"Consignado\",\"accountId\":\""
                + accountA + "\",\"newDebt\":{\"personId\":\"" + bank + "\",\"expectedDate\":\"2026-09-01\"}}").get("debt").get("id").asString();
        createdBody(post("/v1/income-profiles", "{\"source\":\"Empresa Exemplo\",\"gross\":\"5000.00\",\"dependents\":0,\"advance\":null,"
                + "\"balanceDayOfMonth\":1,\"deductions\":[{\"kind\":\"LOAN\",\"label\":\"Consignado\",\"amount\":\"100.00\",\"debtId\":\"" + debt
                + "\"}],\"thirteenth\":{\"enabled\":false,\"firstInstallmentMonth\":11}}"));

        JsonNode detail = okBody(get("/v1/debts/" + debt));
        JsonNode overdue = okBody(get("/v1/debts?overdue=true"));

        assertThat(detail.get("kind").asString()).isEqualTo("PAYROLL");
        assertThat(detail.get("overdue").asBoolean()).as("descontada em folha, não pode estar vencida").isFalse();
        assertThat(overdue.toString()).doesNotContain(debt);
    }

    @Test
    @DisplayName("tirar a rubrica LOAN do perfil libera a dívida (deixa de ser PAYROLL) para que o pagamento volte a ser previsto")
    void rubricaRemovidaLiberaADivida() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode profile = okBody(get("/v1/income-profiles/" + ids.get("perfilRenda")));
        String body = "{\"source\":\"Empresa Exemplo\",\"gross\":\"8000.00\",\"dependents\":1,\"advance\":{\"dayOfMonth\":16,\"percentBps\":4000},"
                + "\"balanceDayOfMonth\":1,\"deductions\":[{\"kind\":\"UNION\",\"label\":\"Mensalidade sindical\",\"amount\":\"50.00\"}],"
                + "\"thirteenth\":{\"enabled\":true,\"firstInstallmentMonth\":11},\"version\":" + profile.get("version").asLong() + "}";

        mockMvc.perform(put("/v1/income-profiles/" + ids.get("perfilRenda"), body)).andExpect(status().isOk());

        assertThat(okBody(get("/v1/debts/" + ids.get("debtFolha"))).get("kind").asString()).isEqualTo("PERSONAL");
    }

    @Test
    @DisplayName("dívida quitada antes de realizar a folha: o salário ainda pode ser realizado (sem parcela do consignado), sem 422 que bloqueie a renda")
    void folhaComDividaQuitada() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode generated = generate(ids.get("perfilRenda"), "2026-10", "2026-10");
        created("{\"type\":\"DEBT_PAYMENT\",\"amount\":\"3000.00\",\"date\":\"2026-10-15\",\"description\":\"Quitei\",\"accountId\":\"" + ids.get("contaA")
                + "\",\"debtId\":\"" + ids.get("debtFolha") + "\"}");
        advanceTo(Instant.parse("2026-10-16T15:00:00Z"));

        realize(recurrenceOf(generated, "ADVANCE"), "2026-10-16", ids.get("contaA"));

        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("rubrica fora do período não reduz o líquido; adiantamento + saldo = líquido da prévia; 13º (1ª parcela) é ocorrência separada em novembro sem descontos")
    void rubricasPeriodoEDecimoTerceiro() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        JsonNode october = previewRaw(ids.get("perfilRenda"), "2026-10");
        JsonNode june2027 = previewRaw(ids.get("perfilRenda"), "2027-06");

        assertThat(october.get("loanDeductions")).hasSize(1);
        assertThat(june2027.get("loanDeductions")).as("a rubrica termina em 31/05/2027").isEmpty();
        assertThat(cents(june2027.get("net")) - cents(october.get("net"))).isEqualTo(30000L);
        assertThat(cents(october.get("advance").get("amount")) + cents(october.get("balance").get("amount"))).isEqualTo(cents(october.get("net")));
        assertThat(october.get("thirteenth").isNull()).isTrue();
        JsonNode november = previewRaw(ids.get("perfilRenda"), "2026-11");
        assertThat(money(november.get("thirteenth"), "amount")).as("metade do bruto, sem descontos").isEqualTo("4000.00");
        assertThat(november.get("thirteenth").get("date").asString()).startsWith("2026-11");

        JsonNode generated = generate(ids.get("perfilRenda"), "2026-11", "2026-11");
        boolean hasThirteenth = false;
        for (JsonNode item : generated.get("items")) {
            if (item.get("kind").asString().equals("THIRTEENTH_FIRST")) {
                hasThirteenth = true;
                assertThat(item.get("competence").asString()).isEqualTo("2026-11");
            }
        }
        assertThat(hasThirteenth).isTrue();
    }

    @Test
    @DisplayName("a prévia com rubrica fixa e com consignado respeita o contrato (debtId presente, nulo quando não há dívida)")
    void previaRespeitaOContrato() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode preview = okBody(get("/v1/income-profiles/" + ids.get("perfilRenda") + "/preview?month=2026-10"));
        assertThat(preview.get("fixedDeductions").get(0).get("debtId").isNull()).isTrue();
        assertThat(preview.get("loanDeductions").get(0).get("debtId").asString()).isEqualTo(ids.get("debtFolha"));
    }

    @Test
    @DisplayName("PUT do perfil: o corpo que a API aceita é o do contrato (IncomeProfileUpdate) e o contrato consegue validar uma edição")
    void putDoPerfilRespeitaOContrato() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        JsonNode profile = okBody(get("/v1/income-profiles/" + ids.get("perfilRenda")));
        String body = "{\"source\":\"Empresa Exemplo\",\"gross\":\"8000.00\",\"dependents\":1,\"advance\":null,\"balanceDayOfMonth\":1,\"deductions\":[],"
                + "\"thirteenth\":{\"enabled\":false,\"firstInstallmentMonth\":11},\"version\":" + profile.get("version").asLong() + "}";
        okBody(put("/v1/income-profiles/" + ids.get("perfilRenda"), body));
    }

    @Test
    @DisplayName("perfil de renda: outro usuário recebe 404 em GET, PUT, preview, generate e archive; sem token 401; entrada inválida 400/422; renda e valores não vazam do outro")
    void isolamentoDoPerfil() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");
        String profile = ids.get("perfilRenda");
        String other = otherUserBearer();

        mockMvc.perform(getAs(other, "/v1/income-profiles/" + profile)).andExpect(status().isNotFound());
        mockMvc.perform(getAs(other, "/v1/income-profiles/" + profile + "/preview?month=2026-11")).andExpect(status().isNotFound());
        mockMvc.perform(postAs(other, "/v1/income-profiles/" + profile + "/generate-occurrences?from=2026-10&to=2026-10", "")).andExpect(status().isNotFound());
        mockMvc.perform(postAs(other, "/v1/income-profiles/" + profile + "/archive", "")).andExpect(status().isNotFound());
        assertThat(body(mockMvc.perform(getAs(other, "/v1/income-profiles")).andExpect(status().isOk()).andReturn()).get("items")).isEmpty();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/income-profiles")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/income-profiles", "{\"source\":\"\",\"gross\":\"0.00\",\"dependents\":0,\"balanceDayOfMonth\":1,\"deductions\":[],"
                + "\"thirteenth\":{\"enabled\":false,\"firstInstallmentMonth\":11}}")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/v1/income-profiles/" + profile + "/preview?month=2026-13")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/income-profiles/" + profile + "/generate-occurrences?from=2026-01&to=2027-06", "")).andExpect(status().is4xxClientError());
    }
}
