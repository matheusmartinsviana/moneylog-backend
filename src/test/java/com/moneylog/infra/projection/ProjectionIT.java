package com.moneylog.infra.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("Projeções determinísticas: série mensal, premissas, tendência opcional e coerência com o dashboard (T96)")
class ProjectionIT extends E4ItBase {

    private void scenario() throws Exception {
        String tech = category("Tecnologia", CategoryKind.EXPENSE);
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1200.00\",\"date\":\"2026-10-10\",\"description\":\"Notebook\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + tech + "\",\"installments\":6}");
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1083.00\",\"date\":\"2026-10-10\",\"description\":\"Mercado\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + tech + "\",\"installments\":1}");
        createdBody(post("/v1/recurrences", "{\"type\":\"EXPENSE\",\"amount\":\"119.00\",\"description\":\"Internet\",\"categoryId\":\"" + bills
                + "\",\"accountId\":\"" + nubank + "\",\"method\":\"PIX\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-11-10\"}"));
        createdBody(post("/v1/recurrences", "{\"type\":\"INCOME\",\"amount\":\"3000.00\",\"description\":\"Salário\",\"categoryId\":\"" + salary
                + "\",\"accountId\":\"" + nubank + "\",\"method\":\"PIX\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-11-05\"}"));
    }

    private JsonNode projection(String query) throws Exception {
        return okBody(get("/v1/projections" + query));
    }

    private static void point(JsonNode p, String competence, String inflows, String outflows, String balance) {
        assertThat(p.get("competence").asString()).isEqualTo(competence);
        assertThat(p.get("inflows").get("amount").asString()).as(competence + " entradas").isEqualTo(inflows);
        assertThat(p.get("outflows").get("amount").asString()).as(competence + " saídas").isEqualTo(outflows);
        assertThat(p.get("balance").get("amount").asString()).as(competence + " saldo").isEqualTo(balance);
    }

    @Test
    @DisplayName("CA1: M6 traz a série mensal acumulada; novembro tem a fatura de 1.283 e a Internet, dezembro a parcela 2/6 (200) uma vez só")
    void deveMontarASerieMensal() throws Exception {
        scenario();

        JsonNode p = projection("?horizon=M6");

        JsonNode series = p.get("series");
        assertThat(series).hasSize(7);
        point(series.get(0), "2026-10", "0.00", "0.00", "20000.00");
        point(series.get(1), "2026-11", "3000.00", "1402.00", "21598.00");
        point(series.get(2), "2026-12", "3000.00", "319.00", "24279.00");
        point(series.get(3), "2027-01", "3000.00", "319.00", "26960.00");
        point(series.get(4), "2027-02", "3000.00", "319.00", "29641.00");
        point(series.get(5), "2027-03", "3000.00", "319.00", "32322.00");
        point(series.get(6), "2027-04", "3000.00", "319.00", "35003.00");
        assertThat(series.get(6).get("periodEnd").asString()).isEqualTo("2027-04-10");
        assertThat(money(p, "startingBalance")).isEqualTo("20000.00");
        assertThat(p.get("horizon").asString()).isEqualTo("M6");
        assertThat(p.get("asOf").asString()).isEqualTo("2026-10-10");
        assertThat(count("transactions")).isEqualTo(2);
    }

    @Test
    @DisplayName("CA2: isProjection é true, as premissas são chaves traduzíveis e o padrão do horizonte é M6; sem includeTrend não há trend")
    void deveRotularComoProjecao() throws Exception {
        scenario();

        for (String query : new String[] {"", "?horizon=D30", "?horizon=D90", "?horizon=M6", "?horizon=M12&includeTrend=false"}) {
            JsonNode p = projection(query);
            assertThat(p.get("isProjection").asBoolean()).as(query).isTrue();
            List<String> assumptions = new ArrayList<>();
            p.get("assumptions").forEach(a -> assumptions.add(a.asString()));
            assertThat(assumptions).containsExactly("ACTIVE_RECURRENCES", "REGISTERED_INVOICES_AND_INSTALLMENTS", "DEBTS_WITH_EXPECTED_DATE",
                    "NO_NEW_SPENDING_TREND");
            assertThat(p.has("trend")).isFalse();
        }
        assertThat(projection("").get("horizon").asString()).isEqualTo("M6");
        assertThat(projection("?horizon=M12").get("series")).hasSize(13);
        assertThat(projection("?horizon=D30").get("series")).hasSize(2);
    }

    @Test
    @DisplayName("CA3: a meta com reserva de 8.500 não muda a série; o campo goals informa o reservado")
    void naoDeveMudarASerieComMeta() throws Exception {
        scenario();
        JsonNode without = projection("?horizon=M12");
        String goal = createdBody(post("/v1/goals", "{\"name\":\"Reserva de emergência\",\"target\":\"20000.00\",\"deadline\":\"2027-12-31\",\"priority\":\"HIGH\"}"))
                .get("id").asString();
        okBody(post("/v1/goals/" + goal + "/reservations", "{\"accountId\":\"" + nubank + "\",\"amount\":\"8500.00\",\"date\":\"2026-10-10\"}")
                .header("Idempotency-Key", UUID.randomUUID().toString()));

        JsonNode with = projection("?horizon=M12");

        assertThat(with.get("series")).isEqualTo(without.get("series"));
        assertThat(with.get("startingBalance")).isEqualTo(without.get("startingBalance"));
        assertThat(money(without.get("goals"), "reserved")).isEqualTo("0.00");
        assertThat(without.get("goals").get("items")).isEmpty();
        assertThat(money(with.get("goals"), "reserved")).isEqualTo("8500.00");
        assertThat(with.get("goals").get("items")).hasSize(1);
        JsonNode item = with.get("goals").get("items").get(0);
        assertThat(item.get("name").asString()).isEqualTo("Reserva de emergência");
        assertThat(money(item, "current")).isEqualTo("8500.00");
        assertThat(money(item, "target")).isEqualTo("20000.00");
        assertThat(count("transactions")).isEqualTo(2);
    }

    @Test
    @DisplayName("CA6: o saldo do último ponto de cada horizonte é o projected do dashboard (D30 = projected.d30), pela mesma função")
    void deveCoincidirComOProjectedDoDashboard() throws Exception {
        scenario();
        JsonNode projected = summary().get("projected");

        assertThat(money(projection("?horizon=D30").get("series").get(1), "balance")).isEqualTo(money(projected, "d30"));
        assertThat(lastBalance(projection("?horizon=D30"))).isEqualTo(money(projected, "d30"));
        assertThat(lastBalance(projection("?horizon=D90"))).isEqualTo(money(projected, "d90"));
        assertThat(lastBalance(projection("?horizon=M6"))).isEqualTo(money(projected, "m6"));
        assertThat(lastBalance(projection("?horizon=M12"))).isEqualTo(money(projected, "m12"));
    }

    private static String lastBalance(JsonNode projection) {
        JsonNode series = projection.get("series");
        return series.get(series.size() - 1).get("balance").get("amount").asString();
    }

    @Test
    @DisplayName("CA7: includeTrend sem 3 meses completos de histórico devolve trend.available false com INSUFFICIENT_HISTORY (não inventa média); a série base é a mesma")
    void naoDeveInventarMedia() throws Exception {
        scenario();

        JsonNode with = projection("?horizon=M6&includeTrend=true");

        JsonNode trend = with.get("trend");
        assertThat(trend.get("available").asBoolean()).isFalse();
        assertThat(trend.get("estimate").asBoolean()).isTrue();
        assertThat(trend.get("reason").asString()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(trend.get("monthlyAverage").isNull()).isTrue();
        assertThat(trend.get("series")).isEmpty();
        assertThat(with.get("series")).isEqualTo(projection("?horizon=M6").get("series"));
    }

    @Test
    @DisplayName("CA4: com 3 meses completos de histórico a tendência vem separada, rotulada estimativa (média do consumo novo) e a série base fica idêntica")
    void deveCalcularATendencia() throws Exception {
        scenario();
        created(expense("300.00", "2026-07-01", "Mercado jul"));
        created(expense("350.00", "2026-08-03", "Mercado ago"));
        created(expense("400.00", "2026-09-03", "Mercado set"));

        JsonNode base = projection("?horizon=M6");
        JsonNode with = projection("?horizon=M6&includeTrend=true");

        JsonNode trend = with.get("trend");
        assertThat(trend.get("available").asBoolean()).isTrue();
        assertThat(trend.get("estimate").asBoolean()).isTrue();
        assertThat(trend.get("reason").isNull()).isTrue();
        assertThat(money(trend, "monthlyAverage")).isEqualTo("350.00");
        assertThat(trend.get("basedOn")).hasSize(3);
        assertThat(trend.get("basedOn").get(0).asString()).isEqualTo("2026-07");
        assertThat(trend.get("series")).hasSize(7);
        assertThat(money(trend.get("series").get(0), "estimatedSpending")).isEqualTo("0.00");
        assertThat(money(trend.get("series").get(2), "estimatedSpending")).isEqualTo("700.00");
        assertThat(new java.math.BigDecimal(money(trend.get("series").get(2), "balance")))
                .isEqualByComparingTo(new java.math.BigDecimal(money(base.get("series").get(2), "balance")).subtract(new java.math.BigDecimal("700.00")));
        assertThat(with.get("series")).as("a série base não muda com a tendência").isEqualTo(base.get("series"));
        assertThat(with.get("assumptions")).isEqualTo(base.get("assumptions"));
    }

    private String expense(String amount, String date, String description) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description + "\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}";
    }

    @Test
    @DisplayName("horizonte fora de D30/D90/M6/M12 e includeTrend inválido são 400; sem token, 401; a projeção é só do usuário do token")
    void deveValidarAEntradaEIsolar() throws Exception {
        scenario();

        for (String query : new String[] {"?horizon=D7", "?horizon=D60", "?horizon=X", "?horizon=m6", "?includeTrend=talvez"}) {
            mockMvc.perform(get("/v1/projections" + query)).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/projections")).andExpect(status().isUnauthorized());
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "proj-" + other + "@moneylog.com"));
        JsonNode theirs = body(mockMvc.perform(getAs(auth.bearer(other), "/v1/projections?horizon=M6")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(money(theirs, "startingBalance")).isEqualTo("0.00");
        assertThat(theirs.get("series").get(1).get("outflows").get("amount").asString()).isEqualTo("0.00");
    }
}
