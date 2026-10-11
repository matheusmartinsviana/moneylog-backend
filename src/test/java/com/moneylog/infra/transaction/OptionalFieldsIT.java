package com.moneylog.infra.transaction;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.FixedClockConfig;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@Import(FixedClockConfig.class)
@DisplayName("Campos opcionais: o servidor resolve os padrões (contrato 2.11.0)")
class OptionalFieldsIT {

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
    private String otherExpense;
    private String otherIncome;

    @BeforeEach
    void cenario() throws Exception {
        userId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, userId, "opt-" + userId + "@moneylog.com"));
        bearer = auth.bearer(userId);
        otherExpense = systemCategory("OTHER_EXPENSE", "Outros", "EXPENSE");
        otherIncome = systemCategory("OTHER_INCOME", "Outras receitas", "INCOME");
        nubank = body(send("/v1/accounts", "{\"name\":\"Nubank\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1000.00\",\"currency\":\"BRL\"},"
                + "\"openingDate\":\"2026-10-01\"}").andReturn()).get("id").asString();
    }

    private String systemCategory(String key, String name, String kind) {
        String id = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> jdbc.update("""
                INSERT INTO categories (id, user_id, name, kind, system, system_key, archived, created_at, updated_at, version)
                VALUES (?::uuid, ?, ?, ?, true, ?, false, now(), now(), 0)""", id, userId, name, kind, key));
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions send(String url, String json) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer)
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json);
        return mockMvc.perform(request).andExpect(status().isCreated());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String get(String url) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    @Test
    @DisplayName("despesa só com valor, data e conta: OTHER_EXPENSE, método OTHER, sem descrição; saldo, listas, busca e CSV sem erro")
    void despesaMinima() throws Exception {
        send("/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"100.00\",\"date\":\"2026-10-10\",\"accountId\":\"" + nubank + "\"}")
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.category.id").value(otherExpense))
                .andExpect(jsonPath("$.method").value("OTHER")).andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.impact.cash.amount").value("-100.00")).andExpect(jsonPath("$.impact.consumption[0].amount.amount").value("100.00"));

        assertThat(body(mockMvc.perform(MockMvcRequestBuilders.get("/v1/accounts/" + nubank).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn())
                .get("currentBalance").get("amount").asString()).isEqualTo("900.00");
        assertThat(get("/v1/transactions")).contains("100.00");
        assertThat(get("/v1/transactions?q=abc")).contains("items");
        assertThat(get("/v1/search?q=abc")).isNotNull();
        assertThat(get("/v1/exports/transactions.csv")).contains("100,00");
        assertThat(get("/v1/exports/backup")).contains("100.00");
        assertThat(jdbc.queryForObject("select count(*) from ledger_postings where user_id = ? and transaction_id is not null", Integer.class, userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("receita e transferência/investimento/ajuste/reembolso sem descrição; receita cai em OTHER_INCOME; ajuste continua exigindo o motivo")
    void outrosTipos() throws Exception {
        send("/v1/transactions", "{\"type\":\"INCOME\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"accountId\":\"" + nubank + "\"}")
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.category.id").value(otherIncome))
                .andExpect(jsonPath("$.impact.cash.amount").value("50.00"));
        send("/v1/transactions", "{\"type\":\"REFUND\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"accountId\":\"" + nubank + "\"}")
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.category.id").value(otherExpense));
        send("/v1/transactions", "{\"type\":\"ADJUSTMENT\",\"amount\":\"5.00\",\"date\":\"2026-10-10\",\"accountId\":\"" + nubank
                + "\",\"direction\":\"DECREASE\",\"reason\":\"conferi o extrato\"}").andExpect(ContractValidation.conforms());
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/transactions").header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ADJUSTMENT\",\"amount\":\"5.00\",\"date\":\"2026-10-10\",\"accountId\":\"" + nubank + "\",\"direction\":\"DECREASE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("categoria padrão arquivada: 422 DEFAULT_CATEGORY_UNAVAILABLE (nunca 500)")
    void categoriaPadraoArquivada() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                x -> jdbc.update("update categories set archived = true where id = ?::uuid", otherExpense));
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/transactions").header(HttpHeaders.AUTHORIZATION, bearer).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"EXPENSE\",\"amount\":\"1.00\",\"date\":\"2026-10-10\",\"accountId\":\"" + nubank + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DEFAULT_CATEGORY_UNAVAILABLE"))
                .andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("conta sem saldo e sem data de abertura: 0,00 e hoje; meta sem prioridade: MEDIUM; recorrência sem descrição, categoria e método")
    void contaMetaERecorrencia() throws Exception {
        send("/v1/accounts", "{\"name\":\"Carteira\",\"type\":\"CHECKING\"}").andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.currentBalance.amount").value("0.00")).andExpect(jsonPath("$.openingDate").value("2026-10-10"));
        send("/v1/goals", "{\"name\":\"Viagem\",\"target\":\"3000.00\"}").andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.priority").value("MEDIUM"));
        send("/v1/recurrences", "{\"type\":\"EXPENSE\",\"amount\":\"30.00\",\"accountId\":\"" + nubank + "\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-10-10\"}")
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.category.id").value(otherExpense))
                .andExpect(jsonPath("$.method").value("OTHER"));
        assertThat(get("/v1/recurrences")).contains("OTHER");
    }

    @Test
    @DisplayName("dívida sem descrição; empréstimo sem descrição")
    void dividas() throws Exception {
        String person = body(send("/v1/people", "{\"name\":\"Ana\"}").andReturn()).get("id").asString();
        send("/v1/debts", "{\"personId\":\"" + person + "\",\"direction\":\"RECEIVABLE\",\"principal\":\"80.00\",\"date\":\"2026-10-10\"}")
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.description").doesNotExist());
        send("/v1/transactions", "{\"type\":\"LOAN_GIVEN\",\"amount\":\"20.00\",\"date\":\"2026-10-10\",\"accountId\":\"" + nubank
                + "\",\"newDebt\":{\"personId\":\"" + person + "\"}}").andExpect(ContractValidation.conforms());
        assertThat(get("/v1/debts")).contains("items");
        assertThat(get("/v1/exports/backup")).contains("80.00");
    }

    @Test
    @DisplayName("compra no cartão sem categoria, sem descrição e sem parcelas: à vista (1x) em OTHER_EXPENSE; consumo na competência, caixa zero")
    void compraNoCartaoMinima() throws Exception {
        String card = body(send("/v1/cards", "{\"name\":\"Roxinho\",\"limit\":{\"amount\":\"1000.00\",\"currency\":\"BRL\"},\"closingDay\":25,\"dueDay\":5}")
                .andReturn()).get("id").asString();
        send("/v1/transactions", "{\"type\":\"CARD_PURCHASE\",\"amount\":\"200.00\",\"date\":\"2026-10-10\",\"cardId\":\"" + card + "\"}")
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.category.id").value(otherExpense))
                .andExpect(jsonPath("$.impact.cash.amount").value("0.00")).andExpect(jsonPath("$.impact.consumption[0].amount.amount").value("200.00"))
                .andExpect(jsonPath("$.installments.length()").value(1));
    }
}
