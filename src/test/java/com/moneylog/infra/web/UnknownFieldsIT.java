package com.moneylog.infra.web;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.FixedClockConfig;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

/**
 * BUG-E2E-02: os corpos de entrada do contrato têm {@code additionalProperties: false}; campo desconhecido é
 * {@code 400 VALIDATION_FAILED} (erro {@code UNKNOWN_FIELD}) e os corpos legítimos continuam passando.
 */
@IntegrationTest
@Import(FixedClockConfig.class)
@DisplayName("Campos desconhecidos no corpo: 400 VALIDATION_FAILED (BUG-E2E-02)")
class UnknownFieldsIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID userId;
    private String bearer;
    private String nubank;
    private String xp;
    private String food;

    @BeforeEach
    void cenario() throws Exception {
        userId = UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, userId, "unk-" + userId + "@moneylog.com"));
        bearer = auth.bearer(userId);
        nubank = account("Nubank");
        xp = account("XP");
        Category category = Category.create(UUID.randomUUID(), userId, "Alimentação", CategoryKind.EXPENSE);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> SqlFixtures.insertCategory(jdbc, category));
        food = category.id().toString();
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, String json) {
        return builder.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private String account(String name) throws Exception {
        String json = "{\"name\":\"" + name + "\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1000.00\",\"currency\":\"BRL\"},"
                + "\"openingDate\":\"2026-10-01\"}";
        return objectMapper.readTree(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/accounts"), json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private ResultActions createTx(String json) throws Exception {
        return mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/transactions"), json)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private static void unknown(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field)).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD"))
                .andExpect(ContractValidation.responseOnly());
    }

    private String common(String type) {
        return "\"type\":\"" + type + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\"";
    }

    private String[] allTypes() {
        return new String[] {
                "{" + common("EXPENSE") + ",\"accountId\":\"" + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"",
                "{" + common("INCOME") + ",\"accountId\":\"" + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"",
                "{" + common("TRANSFER") + ",\"fromAccountId\":\"" + nubank + "\",\"toAccountId\":\"" + xp + "\"",
                "{" + common("INVESTMENT") + ",\"cashAccountId\":\"" + nubank + "\",\"investmentAccountId\":\"" + xp
                        + "\",\"direction\":\"CONTRIBUTION\"",
                "{" + common("ADJUSTMENT") + ",\"accountId\":\"" + nubank + "\",\"direction\":\"INCREASE\",\"reason\":\"r\"",
                "{" + common("REFUND") + ",\"accountId\":\"" + nubank + "\",\"categoryId\":\"" + food + "\""};
    }

    @Test
    @DisplayName("POST /v1/transactions com campo desconhecido, em todos os tipos: 400 UNKNOWN_FIELD e nada é gravado")
    void deveRecusarCampoDesconhecidoNoPostDeMovimentacao() throws Exception {
        for (String body : allTypes()) {
            unknown(createTx(body + ",\"surprise\":1}"), "surprise");
        }
        unknown(createTx(allTypes()[0] + ",\"version\":0}"), "version");
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("select count(*) from transactions where user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    @DisplayName("PUT /v1/transactions/{id}: campo desconhecido é 400; o corpo legítimo com version continua 200")
    void deveRecusarCampoDesconhecidoNoPut() throws Exception {
        String expense = allTypes()[0];
        String id = objectMapper.readTree(createTx(expense + "}").andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()).get("id").asString();
        unknown(mockMvc.perform(withBody(MockMvcRequestBuilders.put("/v1/transactions/" + id), expense + ",\"version\":0,\"surprise\":1}")),
                "surprise");
        mockMvc.perform(withBody(MockMvcRequestBuilders.put("/v1/transactions/" + id), expense + ",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("tipos indisponíveis continuam 422 mesmo com campos que o contrato deixa em aberto")
    void deveManterTiposIndisponiveisAbertos() throws Exception {
        createTx("{\"type\":\"INVOICE_PAYMENT\",\"cardId\":\"" + UUID.randomUUID() + "\",\"amount\":\"1.00\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_TYPE_NOT_AVAILABLE"));
    }

    @Test
    @DisplayName("contas, categorias, estorno e login: campo desconhecido é 400 UNKNOWN_FIELD")
    void deveRecusarCampoDesconhecidoNosDemaisCorpos() throws Exception {
        unknown(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/accounts"),
                "{\"name\":\"A\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},"
                        + "\"openingDate\":\"2026-10-01\",\"surprise\":1}")), "surprise");
        unknown(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/accounts"),
                "{\"name\":\"A\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\",\"x\":1},"
                        + "\"openingDate\":\"2026-10-01\"}")), "openingBalance.x");
        unknown(mockMvc.perform(withBody(MockMvcRequestBuilders.put("/v1/accounts/" + nubank),
                "{\"name\":\"B\",\"version\":0,\"surprise\":1}")), "surprise");
        unknown(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/categories"),
                "{\"name\":\"C\",\"kind\":\"EXPENSE\",\"surprise\":1}")), "surprise");
        unknown(mockMvc.perform(withBody(MockMvcRequestBuilders.put("/v1/categories/" + food),
                "{\"name\":\"C\",\"version\":0,\"surprise\":1}")), "surprise");
        unknown(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/transactions/" + UUID.randomUUID() + "/void"),
                "{\"reason\":\"r\",\"surprise\":1}")), "surprise");
        unknown(mockMvc.perform(MockMvcRequestBuilders.post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.com\",\"password\":\"x\",\"surprise\":1}")), "surprise");
    }
}
