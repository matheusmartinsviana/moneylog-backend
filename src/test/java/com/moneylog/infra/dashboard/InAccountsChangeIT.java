package com.moneylog.infra.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("Dashboard: variação do saldo em contas desde o fim do mês anterior (T188, redesign R4)")
class InAccountsChangeIT extends E4ItBase {

    private String september;

    private void seed() throws Exception {
        september = createdBody(post("/v1/accounts", "{\"name\":\"Conta de setembro\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1000.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-09-01\"}")).get("id").asString();
        created("{\"type\":\"INCOME\",\"amount\":\"84.00\",\"date\":\"2026-10-05\",\"description\":\"Receita\",\"accountId\":\"" + september
                + "\",\"categoryId\":\"" + salary + "\",\"method\":\"PIX\"}");
    }

    @Test
    @DisplayName("previous = inAccounts em 30/09, amount = 21.084,00 − 1.000,00, percent com 4 casas e since; previous é o inAccounts de um dashboard pedido em 30/09")
    void deveInformarAVariacao() throws Exception {
        seed();

        JsonNode balances = summary().get("balances");
        JsonNode change = balances.get("inAccountsChange");

        assertThat(balances.get("inAccounts").get("amount").asString()).isEqualTo("21084.00");
        assertThat(change.get("since").asString()).isEqualTo("2026-09-30");
        assertThat(money(change, "previous")).isEqualTo("1000.00");
        assertThat(money(change, "amount")).isEqualTo("20084.00");
        assertThat(change.get("percent").asString()).isEqualTo("20.0840");
        JsonNode atSince = okBody(get("/v1/dashboard/summary?asOf=2026-09-30")).get("balances");
        assertThat(atSince.get("inAccounts").get("amount").asString()).isEqualTo(money(change, "previous"));
    }

    @Test
    @DisplayName("aplicação neste mês reduz amount (o campo é de contas, não de patrimônio) e investments segue em balances; movimentação futura não entra")
    void deveReduzirComAAplicacaoEIgnorarOFuturo() throws Exception {
        seed();
        String invest = createdBody(post("/v1/accounts", "{\"name\":\"Invest\",\"type\":\"INVESTMENT\",\"openingBalance\":{\"amount\":\"0.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-09-01\"}")).get("id").asString();
        created("{\"type\":\"INVESTMENT\",\"amount\":\"400.00\",\"date\":\"2026-10-06\",\"description\":\"CDB\",\"cashAccountId\":\"" + september
                + "\",\"investmentAccountId\":\"" + invest + "\",\"direction\":\"CONTRIBUTION\"}");
        created("{\"type\":\"INCOME\",\"amount\":\"999.00\",\"date\":\"2026-10-20\",\"description\":\"Futura\",\"accountId\":\"" + september
                + "\",\"categoryId\":\"" + salary + "\",\"method\":\"PIX\"}");

        JsonNode balances = summary().get("balances");

        assertThat(money(balances.get("inAccountsChange"), "amount")).isEqualTo("19684.00");
        assertThat(balances.get("inAccountsChange").get("percent").asString()).isEqualTo("19.6840");
        assertThat(money(balances, "investments")).isEqualTo("400.00");
    }

    @Test
    @DisplayName("projeção (asOf futuro) e usuário sem contas: o campo não vem (ausente, não null)")
    void deveOmitirEmProjecaoESemContas() throws Exception {
        seed();

        JsonNode projection = okBody(get("/v1/dashboard/summary?asOf=2026-11-30"));
        assertThat(projection.get("isProjection").asBoolean()).isTrue();
        assertThat(projection.get("balances").has("inAccountsChange")).isFalse();

        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "empty-" + other + "@moneylog.com"));
        JsonNode empty = body(mockMvc.perform(getAs(auth.bearer(other), "/v1/dashboard/summary")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(empty.get("hasAccounts").asBoolean()).isFalse();
        assertThat(empty.get("balances").has("inAccountsChange")).isFalse();
    }

    @Test
    @DisplayName("previous = 0 (a conta abriu este mês) → percent null; o campo existe")
    void deveTerPercentNuloComBaseZero() throws Exception {
        JsonNode change = summary().get("balances").get("inAccountsChange");

        assertThat(money(change, "previous")).isEqualTo("0.00");
        assertThat(money(change, "amount")).isEqualTo("20000.00");
        assertThat(change.get("percent").isNull()).isTrue();
        assertThat(change.has("percent")).isTrue();
    }
}
