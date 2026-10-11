package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.FixedClockConfig;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;

@IntegrationTest
@Import({WebFoundationSamples.class, FixedClockConfig.class})
@DisplayName("Fundação web: erros, Money, paginação e relógio")
class WebFoundationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ItAuth auth;

    private MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, auth.ownerBearer());
    }

    private MockHttpServletRequestBuilder post(String url) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, auth.ownerBearer());
    }

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Nested
    @DisplayName("T21 CA2: ProblemDetail com code e traceId")
    class Erros {

        @Test
        @DisplayName("NotFoundException vira 404 com o code da exceção")
        void deveMapearNotFound() throws Exception {
            assertProblem("/test/not-found", 404, "SAMPLE_NOT_FOUND");
        }

        @Test
        @DisplayName("BusinessRuleException vira 422 com o code da exceção")
        void deveMapearRegraDeNegocio() throws Exception {
            assertProblem("/test/rule", 422, "SAMPLE_RULE");
        }

        @Test
        @DisplayName("ConflictException vira 409 com o code da exceção")
        void deveMapearConflito() throws Exception {
            assertProblem("/test/conflict", 409, "SAMPLE_CONFLICT");
        }

        @Test
        @DisplayName("RuntimeException vira 500 INTERNAL_ERROR sem a mensagem interna")
        void deveEsconderMensagemInternaNo500() throws Exception {
            MvcResult result = assertProblem("/test/boom", 500, "INTERNAL_ERROR");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("segredo").doesNotContain("jdbc");
        }

        @Test
        @DisplayName("DomainException direta (invariante quebrada) vira 500 sem vazar o detalhe")
        void deveTratarInvarianteComo500() throws Exception {
            MvcResult result = assertProblem("/test/invariant", 500, "INTERNAL_ERROR");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("detalhe interno");
        }

        @Test
        @DisplayName("rota inexistente vira 404 com ProblemDetail")
        void deveMapearRotaInexistente() throws Exception {
            assertProblem("/test/nao-existe", 404, "NOT_FOUND");
        }

        @Test
        @DisplayName("o traceId do corpo é gerado por requisição e nunca vem do cliente")
        void deveGerarTraceIdPorRequisicao() throws Exception {
            MvcResult first = mockMvc.perform(get("/test/rule").header("X-Trace-Id", "forjado")).andReturn();
            MvcResult second = mockMvc.perform(get("/test/rule")).andReturn();
            String a = first.getResponse().getHeader("X-Trace-Id");
            String b = second.getResponse().getHeader("X-Trace-Id");
            assertThat(a).isNotEqualTo("forjado").isNotEqualTo(b).matches("[0-9a-f]{32}");
        }

        private MvcResult assertProblem(String path, int expectedStatus, String expectedCode) throws Exception {
            MvcResult result = mockMvc.perform(get(path))
                    .andExpect(status().is(expectedStatus))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value(expectedCode))
                    .andExpect(jsonPath("$.status").value(expectedStatus))
                    .andExpect(jsonPath("$.title").isNotEmpty())
                    .andReturn();
            String traceId = result.getResponse().getHeader("X-Trace-Id");
            assertThat(traceId).isNotBlank();
            assertThat(body(result).get("traceId").asString()).isEqualTo(traceId);
            return result;
        }
    }

    @Nested
    @DisplayName("T21 CA3: validação e Money")
    class Validacao {

        @Test
        @DisplayName("amount com 3 casas vira 400 VALIDATION_FAILED apontando o campo")
        void deveRejeitarTresCasas() throws Exception {
            mockMvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\",\"amount\":\"10.005\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("amount"))
                    .andExpect(jsonPath("$.errors[0].code").value("PATTERN"));
        }

        @Test
        @DisplayName("amount numérico (10.5) vira 400 apontando o campo, sem coerção para string")
        void deveRejeitarAmountNumerico() throws Exception {
            mockMvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\",\"amount\":10.5}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("amount"))
                    .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"));
        }

        @Test
        @DisplayName("campo obrigatório ausente vira 400 apontando o campo")
        void deveRejeitarCampoObrigatorioAusente() throws Exception {
            mockMvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":\"10.00\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("name"))
                    .andExpect(jsonPath("$.errors[0].code").value("NOT_BLANK"));
        }

        @Test
        @DisplayName("JSON malformado e corpo ausente viram 400 sem vazar o parser")
        void deveRejeitarJsonInvalido() throws Exception {
            mockMvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON).content("{nao é json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("body"))
                    .andExpect(jsonPath("$.errors[0].code").value("MALFORMED_JSON"));
            mockMvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }

        @Test
        @DisplayName("header obrigatório ausente vira 400 apontando o header")
        void deveRejeitarHeaderAusente() throws Exception {
            mockMvc.perform(get("/test/header"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }

        @Test
        @DisplayName("Money.of(\"1234.5\") sai como {\"amount\":\"1234.50\",\"currency\":\"BRL\"}")
        void deveSerializarMoneyComoString() throws Exception {
            mockMvc.perform(get("/test/money"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"amount\":\"1234.50\",\"currency\":\"BRL\"}",
                            org.springframework.test.json.JsonCompareMode.STRICT));
        }

        @Test
        @DisplayName("Money na entrada: objeto com amount string; número, moeda inválida e 3 casas viram 400")
        void deveDesserializarMoney() throws Exception {
            mockMvc.perform(post("/test/money").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"x\",\"price\":{\"amount\":\"20000.00\",\"currency\":\"BRL\"}}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.amount").value("20000.00"));
            for (String bad : new String[] {
                    "{\"amount\":20000.00,\"currency\":\"BRL\"}",
                    "{\"amount\":\"20000.005\",\"currency\":\"BRL\"}",
                    "{\"amount\":\"20000.00\",\"currency\":\"XXX\"}",
                    "{\"amount\":\"20000.00\"}",
                    "\"20000.00\""}) {
                mockMvc.perform(post("/test/money").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"x\",\"price\":" + bad + "}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                        .andExpect(jsonPath("$.errors[0].field").value("price"));
            }
        }
    }

    @Nested
    @DisplayName("T21 CA4: paginação e competência")
    class Paginacao {

        @Test
        @DisplayName("sem parâmetros: page=1 e pageSize=10")
        void devePadronizar() throws Exception {
            mockMvc.perform(get("/test/page"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.pageSize").value(10));
        }

        @Test
        @DisplayName("pageSize=50 é aceito; pageSize=51, page=0 e valor não numérico viram 400")
        void deveValidarLimites() throws Exception {
            mockMvc.perform(get("/test/page").param("page", "3").param("pageSize", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(3))
                    .andExpect(jsonPath("$.pageSize").value(50));
            mockMvc.perform(get("/test/page").param("pageSize", "51"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("pageSize"));
            mockMvc.perform(get("/test/page").param("page", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("page"));
            mockMvc.perform(get("/test/page").param("page", "abc").param("pageSize", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.length()").value(2));
        }

        @Test
        @DisplayName("competência YYYY-MM é lida da query; formato inválido vira 400 apontando o parâmetro")
        void deveLerCompetencia() throws Exception {
            mockMvc.perform(get("/test/competence").param("competence", "2026-10"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("2026-10"));
            mockMvc.perform(get("/test/competence").param("competence", "2026-13"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("competence"))
                    .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"));
            mockMvc.perform(get("/test/competence"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        }
    }

    @Nested
    @DisplayName("T21 CA7: TimeProvider")
    class Relogio {

        @Test
        @DisplayName("\"hoje\" é calculado em America/Sao_Paulo e o relógio é fixável nos ITs")
        void deveCalcularHojeNoFusoDoUsuario() throws Exception {
            mockMvc.perform(get("/test/today"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.today").value("2026-10-10"))
                    .andExpect(jsonPath("$.zone").value("America/Sao_Paulo"))
                    .andExpect(jsonPath("$.now").value("2026-10-11T01:30:00Z"));
        }
    }
}
