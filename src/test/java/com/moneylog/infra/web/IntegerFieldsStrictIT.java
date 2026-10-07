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

import com.moneylog.util.ContractValidation;
import com.moneylog.util.FixedClockConfig;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

/**
 * S-207: o Jackson não aceita mais número com parte fracionária em campo inteiro ({@code ACCEPT_FLOAT_AS_INT} desligado).
 * {@code installments: 2.5} virava 2 em silêncio: em dinheiro isso é perigoso. Agora é {@code 400 VALIDATION_FAILED} com
 * {@code INVALID_FORMAT} no campo, no corpo (installments, closingDay, dueDay, version) e na query (page, pageSize).
 */
@IntegrationTest
@Import(FixedClockConfig.class)
@DisplayName("Campo inteiro com parte fracionária: 400 INVALID_FORMAT (S-207)")
class IntegerFieldsStrictIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private String bearer;

    @BeforeEach
    void cenario() {
        UUID userId = UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, userId, "int-" + userId + "@moneylog.com"));
        bearer = auth.bearer(userId);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, String json) {
        return builder.header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static void invalid(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value(field)).andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT"))
                .andExpect(ContractValidation.responseOnly());
    }

    private String purchase(String installments) {
        return "{\"type\":\"CARD_PURCHASE\",\"amount\":\"100.00\",\"date\":\"2026-10-10\",\"cardId\":\"" + UUID.randomUUID()
                + "\",\"categoryId\":\"" + UUID.randomUUID() + "\",\"installments\":" + installments + "}";
    }

    private static String card(String closingDay, String dueDay) {
        return card(closingDay, dueDay, "");
    }

    private static String card(String closingDay, String dueDay, String extra) {
        return "{\"name\":\"X\",\"limit\":{\"amount\":\"1000.00\",\"currency\":\"BRL\"},\"closingDay\":" + closingDay + ",\"dueDay\":"
                + dueDay + extra + "}";
    }

    @Test
    @DisplayName("installments 2.5 não vira 2: 400 INVALID_FORMAT (e 2.0 também, é número fracionário no JSON)")
    void naoDeveTruncarInstallments() throws Exception {
        for (String value : new String[] {"2.5", "2.0", "1e0", "0.1"}) {
            invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/transactions"), purchase(value))
                    .header("Idempotency-Key", UUID.randomUUID().toString())), "installments");
        }
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/cards/" + UUID.randomUUID() + "/purchase-preview"),
                "{\"amount\":\"100.00\",\"installments\":2.5,\"date\":\"2026-10-10\"}")), "installments");
    }

    @Test
    @DisplayName("closingDay e dueDay fracionários: 400 INVALID_FORMAT no cadastro e na edição do cartão")
    void naoDeveTruncarOsDiasDoCartao() throws Exception {
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/cards"), card("25.5", "5"))), "closingDay");
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/cards"), card("25", "5.9"))), "dueDay");
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.put("/v1/cards/" + UUID.randomUUID()),
                card("25.5", "5", ",\"version\":0"))), "closingDay");
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.put("/v1/cards/" + UUID.randomUUID()),
                card("25", "5.5", ",\"version\":0"))), "dueDay");
    }

    @Test
    @DisplayName("version fracionária: 400 INVALID_FORMAT no cartão e na movimentação")
    void naoDeveTruncarAVersao() throws Exception {
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.put("/v1/cards/" + UUID.randomUUID()),
                card("25", "5", ",\"version\":1.5"))), "version");
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.put("/v1/transactions/" + UUID.randomUUID()),
                "{\"type\":\"EXPENSE\",\"amount\":\"1.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\""
                        + UUID.randomUUID() + "\",\"categoryId\":\"" + UUID.randomUUID() + "\",\"method\":\"PIX\",\"version\":0.5}")),
                "version");
    }

    @Test
    @DisplayName("S-220: numeral entre aspas em campo inteiro (\"25\", \"2\", \"0\") é 400 INVALID_FORMAT, igual a 25.5")
    void naoDeveAceitarTextoEmCampoInteiro() throws Exception {
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/cards"), card("\"25\"", "5"))), "closingDay");
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/cards"), card("25", "\"5\""))), "dueDay");
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.put("/v1/cards/" + UUID.randomUUID()),
                card("25", "5", ",\"version\":\"0\""))), "version");
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/transactions"), purchase("\"2\""))
                .header("Idempotency-Key", UUID.randomUUID().toString())), "installments");
        invalid(mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/cards/" + UUID.randomUUID() + "/purchase-preview"),
                "{\"amount\":\"100.00\",\"installments\":\"2\",\"date\":\"2026-10-10\"}")), "installments");
    }

    @Test
    @DisplayName("page e pageSize fracionários na query: 400 INVALID_FORMAT")
    void naoDeveTruncarAPaginacao() throws Exception {
        invalid(mockMvc.perform(MockMvcRequestBuilders.get("/v1/transactions?page=1.5").header(HttpHeaders.AUTHORIZATION, bearer)), "page");
        invalid(mockMvc.perform(MockMvcRequestBuilders.get("/v1/cards?pageSize=10.5").header(HttpHeaders.AUTHORIZATION, bearer)),
                "pageSize");
    }

    @Test
    @DisplayName("inteiro de verdade continua passando: installments 1 e closingDay/dueDay 25/5 criam normalmente")
    void deveAceitarInteiros() throws Exception {
        mockMvc.perform(withBody(MockMvcRequestBuilders.post("/v1/cards"), card("25", "5"))).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms());
    }
}
