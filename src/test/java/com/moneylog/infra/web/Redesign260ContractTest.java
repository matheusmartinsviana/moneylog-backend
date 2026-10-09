package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;

import io.swagger.v3.oas.models.media.Schema;

import com.moneylog.util.ContractValidation;

/**
 * T181: o contrato 2.6.0 (redesign) é só aditivo e os exemplos novos fecham. Sem Spring: lê o próprio contrato.
 */
@DisplayName("Contrato 2.6.0 (redesign, T181): acréscimos compatíveis, exemplos válidos e aritmética dos exemplos")
class Redesign260ContractTest {

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(ContractValidation.SPEC_FILE.toUri().toString()).build();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** O exemplo lido do YAML cru (o parser do swagger descarta os {@code null} dos exemplos). */
    private static com.fasterxml.jackson.databind.JsonNode example(String schemaName) throws Exception {
        var root = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(ContractValidation.SPEC_FILE.toFile());
        var examples = root.get("components").get("schemas").get(schemaName).get("examples");
        assertThat(examples).as("exemplo de " + schemaName).isNotNull();
        return examples.get(0);
    }

    private static ValidationReport response(String path, String json) {
        return VALIDATOR.validateResponse(path, Request.Method.GET, SimpleResponse.Builder.ok().withContentType("application/json")
                .withHeader("Cache-Control", "private, no-store").withBody(json).build());
    }

    private static BigDecimal amount(com.fasterxml.jackson.databind.JsonNode money) {
        return new BigDecimal(money.get("amount").asText());
    }

    @Test
    @DisplayName("a versão é 2.19.0 (só acréscimos sobre a 2.6.0) e as rotas do redesign, da Etapa 7 e da Etapa 8 existem")
    void deveDeclararAVersaoEAsRotas() {
        var spec = ContractValidation.spec();
        assertThat(spec.getInfo().getVersion()).isEqualTo("2.19.0");
        assertThat(spec.getPaths()).containsKeys("/v1/reports/income-expenses", "/v1/reports/spending-by-category", "/v1/search");
        assertThat(spec.getPaths()).containsKeys("/v1/ai/status", "/v1/ai/previews", "/v1/ai/previews/{id}", "/v1/ai/analyses", "/v1/ai/analyses/{id}",
                "/v1/insights", "/v1/settings/ai");
        assertThat(spec.getPaths()).containsKeys("/v1/imports", "/v1/imports/{id}", "/v1/imports/{id}/lines", "/v1/imports/{id}/lines/{lineId}",
                "/v1/imports/{id}/lines:bulk", "/v1/imports/{id}/apply", "/v1/imports/{id}/undo", "/v1/import-mappings/{sourceId}",
                "/v1/categorization-rules", "/v1/categorization-rules/{id}", "/v1/notifications", "/v1/notifications/unread-count",
                "/v1/notifications/{id}/read", "/v1/notifications/read-all", "/v1/reminders", "/v1/reminders/{id}", "/v1/reminders/{id}/done",
                "/v1/reminders/{id}/snooze", "/v1/settings/notifications", "/v1/push/config", "/v1/push/subscriptions",
                "/v1/push/subscriptions/{id}", "/v1/daily-message");
    }

    @Test
    @DisplayName("os exemplos de IncomeExpenseSeries e SpendingByCategory validam; totals = soma dos pontos; net = income − expenses; share fecha")
    void deveValidarOsExemplosEFecharAAritmetica() throws Exception {
        var series = example("IncomeExpenseSeries");
        assertThat(response("/v1/reports/income-expenses", JSON.writeValueAsString(series)).getMessages()).isEmpty();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;
        for (var point : series.get("points")) {
            assertThat(amount(point.get("net"))).isEqualByComparingTo(amount(point.get("income")).subtract(amount(point.get("expenses"))));
            income = income.add(amount(point.get("income")));
            expenses = expenses.add(amount(point.get("expenses")));
        }
        assertThat(amount(series.get("totals").get("income"))).isEqualByComparingTo(income);
        assertThat(amount(series.get("totals").get("expenses"))).isEqualByComparingTo(expenses);
        assertThat(amount(series.get("totals").get("net"))).isEqualByComparingTo(income.subtract(expenses));
        assertThat(series.get("points")).hasSize(7);

        var spending = example("SpendingByCategory");
        assertThat(response("/v1/reports/spending-by-category", JSON.writeValueAsString(spending)).getMessages()).isEmpty();
        BigDecimal shown = amount(spending.get("other").get("amount"));
        for (var item : spending.get("items")) {
            shown = shown.add(amount(item.get("amount")));
        }
        assertThat(shown).as("itens + other = base").isEqualByComparingTo(amount(spending.get("base")));
    }

    @Test
    @DisplayName("exemplo quebrado (campo a mais, enum fora, share como número) é reprovado: o validador está conferindo")
    void deveReprovarExemploQuebrado() throws Exception {
        String series = JSON.writeValueAsString(example("IncomeExpenseSeries"));
        assertThat(response("/v1/reports/income-expenses", series.replace("\"basis\":\"DATE\"", "\"basis\":\"X\"")).hasErrors()).isTrue();
        assertThat(response("/v1/reports/income-expenses", series.replaceFirst("\\{", "{\"extra\":1,")).hasErrors()).isTrue();
        String spending = JSON.writeValueAsString(example("SpendingByCategory"));
        assertThat(response("/v1/reports/spending-by-category", spending.replace("\"share\":\"0.8000\"", "\"share\":0.8")).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("os campos novos de schemas existentes são opcionais (fora de required): o cliente antigo continua válido")
    @SuppressWarnings("rawtypes")
    void deveManterOsCamposNovosOpcionais() {
        var schemas = ContractValidation.spec().getComponents().getSchemas();
        assertThat(schemas.get("DashboardBalances").getProperties()).containsKey("inAccountsChange");
        assertThat(schemas.get("DashboardBalances").getRequired()).doesNotContain("inAccountsChange")
                .containsExactlyInAnyOrder("inAccounts", "investments", "available", "reserved", "committed30d");
        assertThat(schemas.get("SummaryTotals").getProperties()).containsKey("savingsRate");
        assertThat(schemas.get("SummaryTotals").getRequired()).doesNotContain("savingsRate");
        Schema comparison = schemas.get("SummaryComparison");
        assertThat(comparison.getProperties()).containsKey("savingsRateDelta");
        assertThat(comparison.getRequired()).doesNotContain("savingsRateDelta");
    }

    @Test
    @DisplayName("a busca declara 429, 400 e o Cache-Control; os relatórios, 400 e o Cache-Control")
    void deveDeclararOsErrosEOCache() {
        var paths = ContractValidation.spec().getPaths();
        var search = paths.get("/v1/search").getGet();
        assertThat(search.getResponses()).containsKeys("200", "400", "401", "429", "500");
        assertThat(search.getResponses().get("200").getHeaders()).containsKey("Cache-Control");
        for (String path : new String[] {"/v1/reports/income-expenses", "/v1/reports/spending-by-category"}) {
            var operation = paths.get(path).getGet();
            assertThat(operation.getResponses()).containsKeys("200", "400", "401", "500");
            assertThat(operation.getResponses().get("200").getHeaders()).containsKey("Cache-Control");
        }
    }
}
