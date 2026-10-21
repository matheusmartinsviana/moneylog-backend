package com.moneylog.infra.quickentry;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@TestPropertySource(properties = {"moneylog.quick-entry.max-validity-days=7", "moneylog.quick-entry.default-validity-days=7"})
@DisplayName("Tokens de lançamento rápido: validade máxima configurada em 7 dias")
class QuickEntryTokenValidityIT {

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

    @Test
    @DisplayName("pedir 8 dias com máximo de 7 dá 422 QUICK_TOKEN_VALIDITY_INVALID conforme o contrato; 7 dias passa e nada é gravado no erro")
    void deveRecusarValidadeAcimaDoMaximo() throws Exception {
        UUID userId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> SqlFixtures.insertUser(jdbc, userId, "qv-" + userId + "@example.com"));
        String bearer = auth.bearer(userId);
        String account = objectMapper.readTree(mockMvc.perform(MockMvcRequestBuilders.post("/v1/accounts").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Carteira\",\"type\":\"WALLET\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asString();

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quick-entry-tokens").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\",\"accountId\":\"" + account + "\",\"expiresInDays\":8}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("QUICK_TOKEN_VALIDITY_INVALID"))
                .andExpect(jsonPath("$.detail").value("A validade do token vai de 1 a 7 dias.")).andExpect(ContractValidation.conforms());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from quick_entry_tokens where user_id = ?", Integer.class, userId)).isZero();

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/quick-entry-tokens").header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\",\"accountId\":\"" + account + "\",\"expiresInDays\":7}"))
                .andExpect(status().isCreated());
    }
}
