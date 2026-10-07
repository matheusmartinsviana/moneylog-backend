package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.moneylog.infra.exception.GlobalExceptionHandler;
import com.moneylog.util.ContractValidation;

/**
 * T21 CA6: o utilitário {@link ContractValidation} reprova resposta e requisição fora do contrato, com a mensagem do
 * validador. Usa um {@code MockMvc} autônomo (sem contexto Spring) com um controller de amostra em
 * {@code /v1/categories}, para os defeitos serem plantados à vontade sem colidir com os controllers reais.
 */
@DisplayName("Conformidade com contracts/openapi.yaml")
class ContractConformanceTest {

    private static final String BEARER = "Bearer qualquer-coisa";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ContractSamples.CategoriesSample())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .addFilters(new TraceIdFilter())
                .build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, BEARER);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(String url) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, BEARER);
    }

    @Test
    @DisplayName("resposta e requisição que batem com o contrato passam")
    void devePassarQuandoConforme() {
        assertThatCode(() -> {
            mockMvc.perform(get("/v1/categories").param("kind", "EXPENSE").param("includeArchived", "false"))
                    .andExpect(status().isOk())
                    .andExpect(ContractValidation.conforms());
            mockMvc.perform(post("/v1/categories").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Pets\",\"kind\":\"EXPENSE\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(ContractValidation.conforms());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("requisição sem o Bearer exigido pelo contrato falha (a segurança é conferida)")
    void deveReprovarRequisicaoSemBearer() {
        assertThatThrownBy(() -> mockMvc.perform(MockMvcRequestBuilders.get("/v1/categories"))
                .andExpect(ContractValidation.conforms()))
                .hasMessageContaining("validation.request.security.missing");
    }

    @Test
    @DisplayName("resposta com campo a mais falha com a mensagem do validador")
    void deveReprovarCampoAMaisNaResposta() {
        assertThatThrownBy(() -> mockMvc.perform(get("/v1/categories").header("X-Sample-Case", "extra-field"))
                .andExpect(ContractValidation.conforms()))
                .hasMessageContaining("campoAMais")
                .hasMessageContaining("additionalProperties");
    }

    @Test
    @DisplayName("resposta com campo de tipo errado falha com a mensagem do validador")
    void deveReprovarTipoErradoNaResposta() {
        assertThatThrownBy(() -> mockMvc.perform(get("/v1/categories").header("X-Sample-Case", "wrong-type"))
                .andExpect(ContractValidation.conforms()))
                .hasMessageContaining("validation.response.body.schema.type");
    }

    @Test
    @DisplayName("requisição com parâmetro de query fora do enum falha; responseOnly() a deixa passar")
    void deveReprovarQueryForaDoEnum() throws Exception {
        assertThatThrownBy(() -> mockMvc.perform(get("/v1/categories").param("kind", "NOPE"))
                .andExpect(ContractValidation.conforms()))
                .hasMessageContaining("validation.request.parameter");
        mockMvc.perform(get("/v1/categories").param("kind", "NOPE")).andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("requisição com campo renomeado no corpo falha; responseOnly() a deixa passar")
    void deveReprovarCampoRenomeadoNaRequisicao() throws Exception {
        String renamed = "{\"nome\":\"Pets\",\"kind\":\"EXPENSE\"}";
        assertThatThrownBy(() -> mockMvc.perform(post("/v1/categories").contentType(MediaType.APPLICATION_JSON)
                .content(renamed)).andExpect(ContractValidation.conforms()))
                .hasMessageContaining("validation.request.body");
        mockMvc.perform(post("/v1/categories").contentType(MediaType.APPLICATION_JSON).content(renamed))
                .andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("os ProblemDetail 400, 409 e 422 do GlobalExceptionHandler batem com o schema Problem")
    void deveConformarOsProblemDetails() throws Exception {
        mockMvc.perform(post("/v1/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pets\",\"kind\":\"EXPENSE\"}")
                        .header("X-Sample-Case", "rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Pets\",\"kind\":\"EXPENSE\"}")
                        .header("X-Sample-Case", "conflict"))
                .andExpect(status().isConflict())
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/categories").contentType(MediaType.APPLICATION_JSON).content("[1, 2]"))
                .andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
    }
}
