package com.moneylog.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;

@DisplayName("Spike S-84: o validador de contrato aceita o dialeto escolhido")
class OpenApiValidatorSpikeTest {

    private static final String SPEC = "openapi-spike/spike-3.1.yaml";

    private final OpenApiInteractionValidator validator = OpenApiInteractionValidator.createForSpecificationUrl(SPEC)
            .build();

    private static SimpleRequest post(String body) {
        return SimpleRequest.Builder.post("/v1/things").withContentType("application/json").withBody(body).build();
    }

    private static SimpleResponse created(String body) {
        return SimpleResponse.Builder.status(201).withContentType("application/json").withBody(body).build();
    }

    private static java.util.List<String> messages(ValidationReport report) {
        return report.getMessages().stream().map(m -> m.getKey() + ": " + m.getMessage()).toList();
    }

    private ValidationReport request(String body) {
        Request request = post(body);
        return validator.validateRequest(request);
    }

    @Test
    @DisplayName("requisição correta (oneOf + discriminator) passa")
    void deveAceitarRequisicaoCorreta() {
        assertThat(request("{\"type\":\"EXPENSE\",\"amount\":\"10.00\"}").hasErrors())
                .as(request("{\"type\":\"EXPENSE\",\"amount\":\"10.00\"}").toString()).isFalse();
        assertThat(request("{\"type\":\"TRANSFER\",\"toAccountId\":\"7c2f6b0e-35c0-4a3d-93f1-2f1f7d9b7a10\"}")
                .hasErrors()).isFalse();
    }

    @Test
    @DisplayName("requisição com campo desconhecido ou discriminador inválido falha")
    void deveRejeitarRequisicaoInvalida() {
        assertThat(messages(request("{\"type\":\"EXPENSE\",\"amount\":\"10.00\",\"extra\":1}")))
                .anyMatch(m -> m.contains("extra") || m.contains("additional"));
        assertThat(messages(request("{\"type\":\"NOPE\",\"amount\":\"10.00\"}"))).isNotEmpty();
        assertThat(messages(request("{\"type\":\"TRANSFER\",\"toAccountId\":\"nao-e-uuid\"}")))
                .anyMatch(m -> m.contains("uuid") || m.contains("toAccountId"));
    }

    @Test
    @DisplayName("resposta correta (type nulável e $ref com irmão) passa, inclusive com note nulo")
    void deveAceitarRespostaCorreta() {
        String ok = "{\"id\":\"1\",\"note\":null,\"price\":{\"amount\":\"1.00\",\"currency\":\"BRL\"}}";
        ValidationReport report = validator.validateResponse("/v1/things", Request.Method.POST, created(ok));
        assertThat(report.hasErrors()).as(report.toString()).isFalse();
        String ok2 = "{\"id\":\"1\",\"note\":\"x\",\"price\":{\"amount\":\"1.00\",\"currency\":\"BRL\"}}";
        assertThat(validator.validateResponse("/v1/things", Request.Method.POST, created(ok2)).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("resposta com tipo errado, nulo onde não pode ou campo extra falha")
    void deveRejeitarRespostaInvalida() {
        String badType = "{\"id\":1,\"note\":null,\"price\":{\"amount\":\"1.00\",\"currency\":\"BRL\"}}";
        String badNull = "{\"id\":\"1\",\"note\":null,\"price\":{\"amount\":null,\"currency\":\"BRL\"}}";
        String badExtra = "{\"id\":\"1\",\"note\":null,\"price\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"x\":1}";
        for (String body : new String[] {badType, badNull, badExtra}) {
            ValidationReport report = validator.validateResponse("/v1/things", Request.Method.POST, created(body));
            assertThat(report.hasErrors()).as(body).isTrue();
            assertThat(messages(report)).isNotEmpty();
        }
    }
}
