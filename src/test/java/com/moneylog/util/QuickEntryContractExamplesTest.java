package com.moneylog.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;

@DisplayName("Exemplos do contrato do lançamento rápido validam contra os próprios schemas")
class QuickEntryContractExamplesTest {

    private static final String TOKEN_ID = "0d4b7a52-91c3-4e8f-a6b0-3c2e5f7a8b14";
    private static final Path SPEC = Path.of("").toAbsolutePath().resolve("contracts/openapi.yaml").normalize();

    private final OpenAPI spec = ContractValidation.spec();
    private final OpenApiInteractionValidator validator = OpenApiInteractionValidator
            .createForSpecificationUrl(SPEC.toUri().toString()).build();

    @Test
    @DisplayName("todo exemplo de requisição e de resposta das rotas quick-entry passa no validador")
    void deveValidarTodosOsExemplos() throws Exception {
        List<String> falhas = new ArrayList<>();
        int exemplos = 0;
        boolean valorNumerico = false;
        for (Map.Entry<String, PathItem> path : spec.getPaths().entrySet()) {
            if (!path.getKey().startsWith("/v1/quick-entr")) {
                continue;
            }
            String url = path.getKey().replace("{id}", TOKEN_ID);
            for (Map.Entry<PathItem.HttpMethod, Operation> op : path.getValue().readOperationsMap().entrySet()) {
                String method = op.getKey().name();
                if (op.getValue().getRequestBody() != null) {
                    for (Map.Entry<String, Example> ex : examples(op.getValue().getRequestBody().getContent())
                            .entrySet()) {
                        exemplos++;
                        valorNumerico |= Json.mapper().valueToTree(ex.getValue().getValue()).path("amount").isNumber();
                        SimpleRequest.Builder request = new SimpleRequest.Builder(method, url)
                                .withContentType("application/json")
                                .withAuthorization("Bearer qualquer-coisa")
                                .withBody(Json.mapper().writeValueAsString(ex.getValue().getValue()));
                        if ("/v1/quick-entries".equals(path.getKey())) {
                            request.withHeader("Idempotency-Key", "20261021123045");
                        }
                        collect(falhas, method + " " + path.getKey() + " requisição " + ex.getKey(),
                                validator.validateRequest(request.build()));
                    }
                }
                for (Map.Entry<String, ApiResponse> response : op.getValue().getResponses().entrySet()) {
                    ApiResponse resolved = resolve(response.getValue());
                    if (resolved.getContent() == null) {
                        continue;
                    }
                    for (Map.Entry<String, MediaType> media : resolved.getContent().entrySet()) {
                        for (Map.Entry<String, Example> ex : examples(Map.of(media.getKey(), media.getValue()))
                                .entrySet()) {
                            exemplos++;
                            SimpleResponse.Builder out = SimpleResponse.Builder
                                    .status(Integer.parseInt(response.getKey()))
                                    .withContentType(media.getKey())
                                    .withBody(Json.mapper().writeValueAsString(ex.getValue().getValue()));
                            headers(resolved).forEach(out::withHeader);
                            collect(falhas, method + " " + path.getKey() + " " + response.getKey() + " " + ex.getKey(),
                                    validator.validateResponse(url, Request.Method.valueOf(method), out.build()));
                        }
                    }
                }
            }
        }
        assertThat(exemplos).as("exemplos encontrados").isGreaterThan(20);
        assertThat(valorNumerico).as("exemplo com amount numérico").isTrue();
        assertThat(falhas).isEmpty();
    }

    private static Map<String, Example> examples(Map<String, MediaType> content) {
        Map<String, Example> all = new java.util.LinkedHashMap<>();
        if (content != null) {
            content.values().forEach(m -> {
                if (m.getExamples() != null) {
                    all.putAll(m.getExamples());
                }
            });
        }
        return all;
    }

    private ApiResponse resolve(ApiResponse response) {
        if (response.get$ref() == null) {
            return response;
        }
        String name = response.get$ref().substring(response.get$ref().lastIndexOf('/') + 1);
        return spec.getComponents().getResponses().get(name);
    }

    private Map<String, String> headers(ApiResponse response) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        if (response.getHeaders() != null) {
            for (Map.Entry<String, Header> h : response.getHeaders().entrySet()) {
                boolean numeric = h.getValue().getSchema() != null && h.getValue().getSchema().getTypes() != null
                        && h.getValue().getSchema().getTypes().contains("integer");
                String ref = h.getValue().get$ref();
                values.put(h.getKey(), numeric ? "30" : ref != null && ref.endsWith("NoStore") ? "private, no-store" : "valor");
            }
        }
        return values;
    }

    private static void collect(List<String> falhas, String rotulo, ValidationReport report) {
        if (report.hasErrors()) {
            report.getMessages().forEach(m -> falhas.add(rotulo + ": " + m.getKey() + " " + m.getMessage()));
        }
    }
}
