package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;


import com.moneylog.util.ContractValidation;

/**
 * T91: o contrato da Etapa 5 em si (sem Spring): os exemplos de {@code Goal}, {@code MonthlySummary} e {@code Projection} validam contra o próprio
 * contrato, o {@code amount} da reserva aceita só decimal com sinal e o {@code horizon} fora do enum é recusado pelo validador.
 */
@DisplayName("Contrato da Etapa 5: exemplos, valor com sinal da reserva e horizonte (T91)")
class Etapa5ContractTest {

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(ContractValidation.SPEC_FILE.toUri().toString()).build();
    private static final com.fasterxml.jackson.databind.ObjectMapper NULLS_KEPT = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final String GOAL_ID = "5b0f6c3e-7e0a-4f6b-9d57-0e8f3a1c2b10";

    /** O exemplo lido do YAML cru: o parser do swagger descarta os {@code null} dos exemplos, e os campos anuláveis obrigatórios precisam deles. */
    private static String example(String schemaName) throws Exception {
        var root = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(ContractValidation.SPEC_FILE.toFile());
        var examples = root.get("components").get("schemas").get(schemaName).get("examples");
        assertThat(examples).as("exemplo de " + schemaName).isNotNull();
        assertThat(examples.size()).isPositive();
        return NULLS_KEPT.writeValueAsString(examples.get(0));
    }

    private static ValidationReport response(String path, String json) {
        return VALIDATOR.validateResponse(path, Request.Method.GET, SimpleResponse.Builder.ok().withContentType("application/json").withBody(json).build());
    }

    private static ValidationReport reservation(String body) {
        return VALIDATOR.validateRequest(SimpleRequest.Builder.post("/v1/goals/" + GOAL_ID + "/reservations").withContentType("application/json")
                .withHeader("Authorization", "Bearer x").withHeader("Idempotency-Key", "0c8a1d52-3f1e-4c0b-8a67-5d2e9b7f4a31").withBody(body).build());
    }

    private static ValidationReport projectionRequest(String query) {
        SimpleRequest.Builder builder = SimpleRequest.Builder.get("/v1/projections").withHeader("Authorization", "Bearer x");
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                builder.withQueryParam(parts[0], parts[1]);
            }
        }
        return VALIDATOR.validateRequest(builder.build());
    }

    @Test
    @DisplayName("CA1: os exemplos de Goal, MonthlySummary e Projection validam contra o contrato")
    void deveValidarOsExemplos() throws Exception {
        assertThat(response("/v1/goals/" + GOAL_ID, example("Goal")).getMessages()).isEmpty();
        assertThat(response("/v1/summaries/2026-10", example("MonthlySummary")).getMessages()).isEmpty();
        assertThat(response("/v1/projections", example("Projection")).getMessages()).isEmpty();
    }

    @Test
    @DisplayName("um exemplo com campo a mais ou tipo errado é reprovado (o validador está de fato conferindo)")
    void deveReprovarExemploQuebrado() throws Exception {
        String broken = example("Goal").replace("\"progress\":\"0.4250\"", "\"progress\":0.425");
        String extra = example("Goal").replaceFirst("\\{", "{\"campoAMais\":1,");

        assertThat(response("/v1/goals/" + GOAL_ID, broken).hasErrors()).isTrue();
        assertThat(response("/v1/goals/" + GOAL_ID, extra).hasErrors()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8500.00", "-2000.00", "0", "-0.5", "100", "1234567890123.99", "-1234567890123"})
    @DisplayName("CA2: amount da reserva aceita string decimal com sinal (-?\\d{1,13}(\\.\\d{1,2})?)")
    void deveAceitarValorComSinal(String amount) {
        assertThat(reservation("{\"accountId\":\"3d6f1a90-1b2c-4d5e-8f70-a1b2c3d4e5f6\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\"}").getMessages())
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.005", "--1", "1e3", "", "12345678901234", "+5", "1,50", " 1", "abc"})
    @DisplayName("CA2: amount fora do formato é recusado")
    void deveRecusarValorForaDoFormato(String amount) {
        assertThat(reservation("{\"accountId\":\"3d6f1a90-1b2c-4d5e-8f70-a1b2c3d4e5f6\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\"}").hasErrors()).isTrue();
    }

    @Test
    @DisplayName("CA2: amount como número JSON, campo desconhecido e data ausente são recusados")
    void deveRecusarNumeroCampoDesconhecidoEDataAusente() {
        assertThat(reservation("{\"accountId\":\"3d6f1a90-1b2c-4d5e-8f70-a1b2c3d4e5f6\",\"amount\":10.5,\"date\":\"2026-10-10\"}").hasErrors()).isTrue();
        assertThat(reservation("{\"accountId\":\"3d6f1a90-1b2c-4d5e-8f70-a1b2c3d4e5f6\",\"amount\":\"1.00\",\"date\":\"2026-10-10\",\"x\":1}").hasErrors()).isTrue();
        assertThat(reservation("{\"accountId\":\"3d6f1a90-1b2c-4d5e-8f70-a1b2c3d4e5f6\",\"amount\":\"1.00\"}").hasErrors()).isTrue();
    }

    static Stream<String> horizonsOk() {
        return Stream.of("horizon=D30", "horizon=D90", "horizon=M6", "horizon=M12&includeTrend=true", "includeTrend=false", "");
    }

    @ParameterizedTest
    @MethodSource("horizonsOk")
    @DisplayName("CA3: horizontes D30, D90, M6 e M12 (e includeTrend) são aceitos")
    void deveAceitarOsHorizontes(String query) {
        assertThat(projectionRequest(query).getMessages()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"horizon=D7", "horizon=D60", "horizon=X", "horizon=m6", "includeTrend=talvez"})
    @DisplayName("CA3: horizon fora do enum (e includeTrend não booleano) é recusado pelo validador")
    void deveRecusarHorizonteForaDoEnum(String query) {
        assertThat(projectionRequest(query).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("as operações novas existem no contrato com o operationId esperado, e a tabela de erros traz os códigos novos")
    void deveTerAsOperacoesECodigos() {
        var spec = ContractValidation.spec();
        List<String> ids = spec.getPaths().values().stream().flatMap(p -> p.readOperations().stream()).map(o -> o.getOperationId()).toList();

        assertThat(ids).contains("listGoals", "createGoal", "getGoal", "updateGoal", "reserveOnGoal", "archiveGoal", "unarchiveGoal", "getMonthlySummary",
                "getProjection", "exportTransactionsCsv", "exportBackup");
        String description = spec.getInfo().getDescription();
        assertThat(description).contains("GOAL_NOT_FOUND", "GOAL_ARCHIVED", "GOAL_RESERVATION_ACCOUNT_NOT_ELIGIBLE", "GOAL_RELEASE_EXCEEDS_RESERVED",
                "SUMMARY_MONTH_INVALID");
    }
}
