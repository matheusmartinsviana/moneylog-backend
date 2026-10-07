package com.moneylog.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.moneylog.infra.web.TraceIdFilter;

@DisplayName("ProblemRequestRejectedHandler: recusa do firewall em problem+json (BUG-QA-32, S-261)")
class ProblemRequestRejectedHandlerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ProblemRequestRejectedHandler handler = new ProblemRequestRejectedHandler(mapper);

    private MockHttpServletResponse reject(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setAttribute(TraceIdFilter.ATTRIBUTE, "abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handle(request, response, new RequestRejectedException("The request was rejected because the URL contained a potentially malicious String \"%2e\""));
        return response;
    }

    @Test
    @DisplayName("caminho de /v1/summaries/ recusado vira 400 SUMMARY_MONTH_INVALID com traceId, sem ecoar o caminho nem a mensagem do firewall")
    void deveResponderComoMesInvalido() throws Exception {
        MockHttpServletResponse response = reject("/v1/summaries/2026-10%00");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode problem = mapper.readTree(response.getContentAsString());
        assertThat(problem.get("code").asString()).isEqualTo("SUMMARY_MONTH_INVALID");
        assertThat(problem.get("type").asString()).isEqualTo("urn:moneylog:problem:SUMMARY_MONTH_INVALID");
        assertThat(problem.get("status").asInt()).isEqualTo(400);
        assertThat(problem.get("traceId").asString()).isEqualTo("abc123");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("month");
        assertThat(response.getContentAsString()).doesNotContain("%00").doesNotContain("malicious").doesNotContain("2026-10");
    }

    @Test
    @DisplayName("outras rotas: 400 VALIDATION_FAILED com o campo 'path'")
    void deveResponderComoValidacao() throws Exception {
        MockHttpServletResponse response = reject("/v1/goals/..%2F..");

        JsonNode problem = mapper.readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("path");
        assertThat(problem.get("errors").get(0).get("code").asString()).isEqualTo("INVALID_FORMAT");
    }
}
