package com.moneylog.infra.summary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

/**
 * T95: {@code GET /v1/summaries/{yyyy-MM}}. Cenário do plano: outubro com Salário 3.000,00, Pix 100,00 (Alimentação), compra de 1.200,00 em 6x no
 * cartão (Tecnologia), aplicação de 10.000,00 e reembolso de 50,00 (Alimentação) em 12/10; setembro com consumo de 150,00 e receita de
 * 3.000,00. Relógio 10/10/2026. O resumo bate com o dashboard e com a lista por competência (PR 01, regra 10).
 */
@DisplayName("Resumo mensal: cálculo no backend, comparação e consistência com o dashboard e a lista (T95)")
class MonthlySummaryIT extends E4ItBase {

    private String tech;
    private String investment;

    private String expense(String amount, String date, String description, String category) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description + "\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + category + "\",\"method\":\"PIX\"}";
    }

    private String incomeOf(String amount, String date, String description) {
        return "{\"type\":\"INCOME\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description + "\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + salary + "\",\"method\":\"PIX\"}";
    }

    private JsonNode seed() throws Exception {
        tech = category("Tecnologia", CategoryKind.EXPENSE);
        investment = createdBody(post("/v1/accounts", "{\"name\":\"XP Invest\",\"type\":\"INVESTMENT\",\"openingBalance\":{\"amount\":\"0.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        created(incomeOf("3000.00", "2026-10-05", "Salário"));
        JsonNode pix = created(expense("100.00", "2026-10-10", "Restaurante", food));
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1200.00\",\"date\":\"2026-10-10\",\"description\":\"Notebook\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + tech + "\",\"installments\":6}");
        created("{\"type\":\"INVESTMENT\",\"amount\":\"10000.00\",\"date\":\"2026-10-10\",\"description\":\"CDB\",\"cashAccountId\":\"" + nubank
                + "\",\"investmentAccountId\":\"" + investment + "\",\"direction\":\"CONTRIBUTION\"}");
        created("{\"type\":\"REFUND\",\"amount\":\"50.00\",\"date\":\"2026-10-12\",\"description\":\"Reembolso\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"refundOfTransactionId\":\"" + pix.get("id").asString() + "\"}");
        created(incomeOf("3000.00", "2026-09-05", "Salário de setembro"));
        created(expense("150.00", "2026-09-15", "Mercado", food));
        return pix;
    }

    private JsonNode summaryOf(String month) throws Exception {
        return okBody(get("/v1/summaries/" + month));
    }

    @Test
    @DisplayName("CA1 a CA4: receitas 3.000, consumo 250, investimentos 10.000 fora do consumo, net 2.750, savingsRate 0.9167, maior categoria Tecnologia, maior gasto a parcela 1/6, cartão, parcelas futuras e comparação com setembro")
    void deveCalcularOCenarioDoPlano() throws Exception {
        JsonNode pix = seed();

        JsonNode s = summaryOf("2026-10");

        assertThat(s.get("competence").asString()).isEqualTo("2026-10");
        assertThat(money(s, "income")).isEqualTo("3000.00");
        assertThat(money(s, "expenses")).isEqualTo("250.00");
        assertThat(money(s, "investments")).isEqualTo("10000.00");
        assertThat(money(s, "net")).isEqualTo("2750.00");
        assertThat(s.get("savingsRate").asString()).isEqualTo("0.9167");
        assertThat(s.get("largestCategory").get("category").get("name").asString()).isEqualTo("Tecnologia");
        assertThat(money(s.get("largestCategory"), "amount")).isEqualTo("200.00");
        JsonNode largest = s.get("largestExpense");
        assertThat(largest.get("description").asString()).isEqualTo("Notebook");
        assertThat(money(largest, "amount")).isEqualTo("200.00");
        assertThat(largest.get("installmentNumber").asInt()).isEqualTo(1);
        assertThat(largest.get("installmentTotal").asInt()).isEqualTo(6);
        assertThat(largest.get("transactionId").asString()).isNotBlank().isNotEqualTo(pix.get("id").asString());
        assertThat(largest.get("debtId").isNull()).isTrue();
        assertThat(largest.get("category").get("name").asString()).isEqualTo("Tecnologia");
        assertThat(largest.get("date").asString()).isEqualTo("2026-10-10");
        assertThat(money(s.get("card"), "consumption")).isEqualTo("200.00");
        assertThat(money(s.get("card"), "outstanding")).isEqualTo("0.00");
        assertThat(money(s, "futureInstallments")).isEqualTo("1000.00");
        JsonNode comparison = s.get("comparison");
        assertThat(comparison.get("previousMonth").get("competence").asString()).isEqualTo("2026-09");
        assertThat(money(comparison.get("previousMonth"), "expenses")).isEqualTo("150.00");
        assertThat(money(comparison.get("expensesDelta"), "amount")).isEqualTo("100.00");
        assertThat(comparison.get("expensesDelta").get("percent").asString()).isEqualTo("0.6667");
        assertThat(money(comparison.get("incomeDelta"), "amount")).isEqualTo("0.00");
        assertThat(comparison.get("incomeDelta").get("percent").asString()).isEqualTo("0.0000");
        assertThat(money(comparison.get("investmentsDelta"), "amount")).isEqualTo("10000.00");
        assertThat(comparison.get("investmentsDelta").get("percent").isNull()).as("base 0").isTrue();
        assertThat(money(comparison.get("netDelta"), "amount")).isEqualTo("-100.00");
        assertThat(comparison.get("previousMonth").get("savingsRate").asString()).as("T187: 2850 / 3000").isEqualTo("0.9500");
        assertThat(comparison.get("savingsRateDelta").asString()).as("T187: 0.9167 − 0.9500, em pontos percentuais").isEqualTo("-0.0333");
        assertThat(s.get("partial").asBoolean()).isTrue();
        assertThat(s.get("isProjection").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("CA3: em novembro o consumo do crédito é a parcela 2/6 e o outstanding é o da fatura que vence em 05/11 (a parcela 1/6)")
    void deveTrazerAFaturaQueVenceNoMes() throws Exception {
        seed();

        JsonNode november = summaryOf("2026-11");

        assertThat(money(november.get("card"), "consumption")).isEqualTo("200.00");
        assertThat(money(november.get("card"), "outstanding")).isEqualTo("200.00");
        assertThat(money(november, "futureInstallments")).isEqualTo("800.00");
        assertThat(november.get("partial").asBoolean()).isTrue();
        assertThat(november.get("isProjection").asBoolean()).isTrue();
        assertThat(money(november, "income")).isEqualTo("0.00");
        assertThat(november.get("savingsRate").isNull()).as("sem receitas").isTrue();
        assertThat(november.get("comparison").get("previousMonth").get("savingsRate").asString()).isEqualTo("0.9167");
        assertThat(november.get("comparison").get("savingsRateDelta").isNull()).as("T187: a taxa do mês é null").isTrue();
    }

    @Test
    @DisplayName("CA5: mês passado não é partial; mês futuro é partial e projeção; formato inválido → 400 SUMMARY_MONTH_INVALID")
    void deveMarcarParcialEValidarOMes() throws Exception {
        seed();

        JsonNode september = summaryOf("2026-09");
        assertThat(september.get("partial").asBoolean()).isFalse();
        assertThat(september.get("isProjection").asBoolean()).isFalse();
        assertThat(money(september, "expenses")).isEqualTo("150.00");
        assertThat(money(september, "net")).isEqualTo("2850.00");
        assertThat(summaryOf("2027-03").get("isProjection").asBoolean()).isTrue();

        for (String month : new String[] {"2026-13", "2026-00", "1999-12", "2101-01"}) {
            mockMvc.perform(get("/v1/summaries/" + month)).andExpect(status().isBadRequest()).andExpect(ContractValidation.conforms())
                    .andExpect(jsonPath("$.code").value("SUMMARY_MONTH_INVALID")).andExpect(jsonPath("$.errors[0].field").value("month"));
        }
        for (String month : new String[] {"abc", "2026-1", "202610", "2026-10-01"}) {
            mockMvc.perform(get("/v1/summaries/" + month)).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly())
                    .andExpect(jsonPath("$.code").value("SUMMARY_MONTH_INVALID"));
        }
    }

    @Test
    @DisplayName("CA6: mês sem receitas devolve savingsRate null; mês vazio devolve zeros, sem maior categoria nem maior gasto")
    void deveTratarMesVazio() throws Exception {
        seed();

        JsonNode empty = summaryOf("2026-06");

        assertThat(empty.get("savingsRate").isNull()).isTrue();
        assertThat(empty.get("largestCategory").isNull()).isTrue();
        assertThat(empty.get("largestExpense").isNull()).isTrue();
        assertThat(money(empty, "income")).isEqualTo("0.00");
        assertThat(money(empty, "expenses")).isEqualTo("0.00");
        assertThat(money(empty, "futureInstallments")).as("as parcelas da compra de outubro são todas futuras em relação a junho").isEqualTo("1200.00");
    }

    @Test
    @DisplayName("CA7: o resumo, a lista por competência e o dashboard do mesmo mês batem em receitas, consumo e net")
    void deveBaterComAListaEComODashboard() throws Exception {
        seed();

        for (String month : new String[] {"2026-09", "2026-10", "2026-11", "2026-12"}) {
            JsonNode summary = summaryOf(month);
            JsonNode consumption = okBody(get("/v1/entries?competence=" + month + "&lens=CONSUMPTION&pageSize=50"));
            JsonNode income = okBody(get("/v1/entries?competence=" + month + "&lens=INCOME&pageSize=50"));
            JsonNode dashboard = okBody(get("/v1/dashboard/summary?asOf=" + month + "-28")).get("month");

            assertThat(dashboard.get("competence").asString()).isEqualTo(month);
            assertThat(money(summary, "expenses")).as(month + " consumo").isEqualTo(money(dashboard, "consumption"))
                    .isEqualTo(consumption.get("totals").get("total").get("amount").asString());
            assertThat(money(summary, "income")).as(month + " receitas").isEqualTo(money(dashboard, "income"))
                    .isEqualTo(income.get("totals").get("total").get("amount").asString());
            assertThat(money(summary, "net")).as(month + " net").isEqualTo(money(dashboard, "net"));
            assertThat(money(summary, "investments")).isEqualTo(money(dashboard, "investedNet"));
            assertThat(money(summary.get("card"), "consumption")).isEqualTo(money(dashboard, "credit"));
        }
    }

    @Test
    @DisplayName("o resumo é só do usuário do token; sem token, 401")
    void deveIsolarPorUsuario() throws Exception {
        seed();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "resumo-" + other + "@moneylog.com"));

        JsonNode theirs = body(mockMvc.perform(getAs(auth.bearer(other), "/v1/summaries/2026-10")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());

        assertThat(money(theirs, "income")).isEqualTo("0.00");
        assertThat(money(theirs, "expenses")).isEqualTo("0.00");
        assertThat(theirs.get("largestCategory").isNull()).isTrue();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/summaries/2026-10")).andExpect(status().isUnauthorized());
        assertThat(investment).isNotBlank();
        assertThat(tech).isNotBlank();
    }
}
