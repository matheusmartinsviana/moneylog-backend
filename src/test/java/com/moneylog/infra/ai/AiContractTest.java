package com.moneylog.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.media.Schema;

import com.moneylog.util.ContractValidation;

/** T139 CA2: nenhum campo de chave, segredo ou URL base em qualquer schema de resposta da IA. */
class AiContractTest {

    private static final Set<String> AI_SCHEMAS = Set.of("AiStatus", "AiPreview", "AiAnalysis", "AiAnalysisSummary", "AiResult", "Insight", "AiSettings");
    private static final Set<String> FORBIDDEN = Set.of("apikey", "api_key", "secret", "token", "password", "baseurl", "authorization");

    @Test
    @DisplayName("os schemas da IA não declaram campo de chave, segredo nem URL base")
    @SuppressWarnings("rawtypes")
    void naoDeveDeclararCampoSecreto() {
        Map<String, Schema> schemas = ContractValidation.spec().getComponents().getSchemas();
        for (String name : AI_SCHEMAS) {
            Map<String, Schema> properties = schemas.get(name).getProperties();
            Set<String> fields = properties.keySet().stream().map(String::toLowerCase).collect(Collectors.toSet());
            assertThat(fields).as(name).doesNotContainAnyElementsOf(FORBIDDEN);
        }
    }
}
