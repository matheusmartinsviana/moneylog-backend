package com.moneylog.infra.debt;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("Visão total de dívidas, parcelado em andamento e meta de quitação (ADR-013)")
class DebtOverviewIT {

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
    @Autowired
    private MutableClock clock;

    private UUID userId;
    private String bearer;
    private String card;
    private String tech;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        userId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, userId, "ov-" + userId + "@example.com"));
        bearer = auth.bearer(userId);
        String nubank = body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"Nubank\",\"type\":\"CHECKING\",\"institution\":\"Nubank\","
                + "\"openingBalance\":{\"amount\":\"20000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        card = body(mockMvc.perform(post("/v1/cards", "{\"name\":\"Roxinho\",\"institution\":\"Nubank\",\"limit\":{\"amount\":\"10000.00\","
                + "\"currency\":\"BRL\"},\"closingDay\":25,\"dueDay\":5,\"defaultPaymentAccountId\":\"" + nubank + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        Category category = Category.create(UUID.randomUUID(), userId, "Casa", CategoryKind.EXPENSE);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> SqlFixtures.insertCategory(jdbc, category));
        tech = category.id().toString();
    }

    private MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    private MockHttpServletRequestBuilder post(String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private MockHttpServletRequestBuilder baseline(String id) {
        return MockMvcRequestBuilders.post("/v1/goals/" + id + "/baseline").header(HttpHeaders.AUTHORIZATION, bearer);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode overview() throws Exception {
        return body(mockMvc.perform(get("/v1/debts/overview")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private ResultActions ongoing(String path, String extra) throws Exception {
        return mockMvc.perform(post("/v1/cards/" + card + "/ongoing-installments" + path, "{\"description\":\"Geladeira\","
                + "\"installmentAmount\":\"150.00\",\"totalInstallments\":10,\"remainingInstallments\":8" + extra + "}"));
    }

    @Test
    @DisplayName("sem token: 401; months fora de 1..36: 400; usuário sem nada: total 0,00, listas vazias e hasRecords false")
    void deveAutenticarValidarEResponderVazio() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/debts/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/debts/overview?months=0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/debts/overview?months=37")).andExpect(status().isBadRequest());
        UUID other = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, other, "vazio-" + other + "@example.com"));
        bearer = auth.bearer(other);
        mockMvc.perform(get("/v1/debts/overview?months=6")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.total.amount").value("0.00")).andExpect(jsonPath("$.hasRecords").value(false))
                .andExpect(jsonPath("$.cards.items.length()").value(0)).andExpect(jsonPath("$.people.items.length()").value(0))
                .andExpect(jsonPath("$.schedule.points.length()").value(6)).andExpect(jsonPath("$.comparison.reason").value("NO_INCOME"))
                .andExpect(jsonPath("$.payoffGoal").doesNotExist());
    }

    @Test
    @DisplayName("parcelado em andamento 8 × 150: prévia e criação 201 batem, total sobe 1.200, consumo do mês passado não muda e dados de outro usuário não aparecem")
    void deveCadastrarParceladoEmAndamentoENoTotal() throws Exception {
        JsonNode preview = body(ongoing("/preview", "").andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.remaining.amount").value("1200.00")).andExpect(jsonPath("$.remainingInstallments").value(8))
                .andExpect(jsonPath("$.purchase.installments[0].number").value(3)).andReturn());
        assertThat(preview.get("lastDueDate").asString()).isEqualTo("2027-06-05");
        assertThat(overview().get("total").get("amount").asString()).isEqualTo("0.00");

        MvcResult created = ongoing("", "").andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.source").value("ONGOING")).andExpect(jsonPath("$.installments.length()").value(8))
                .andExpect(jsonPath("$.installments[0].number").value(3)).andExpect(jsonPath("$.installments[7].number").value(10))
                .andExpect(jsonPath("$.impact.netWorth.amount").value("-150.00")).andReturn();
        assertThat(created.getResponse().getHeader("Location")).startsWith("/v1/transactions/");

        JsonNode o = overview();
        assertThat(o.get("total").get("amount").asString()).isEqualTo("1200.00");
        assertThat(o.get("parts").get("cards").get("amount").asString()).isEqualTo("1200.00");
        JsonNode plan = o.get("cards").get("items").get(0).get("plans").get(0);
        assertThat(plan.get("ongoing").asBoolean()).isTrue();
        assertThat(plan.get("remainingInstallments").asInt()).isEqualTo(8);
        assertThat(plan.get("nextInstallmentNumber").asInt()).isEqualTo(3);
        assertThat(o.get("hasRecords").asBoolean()).isTrue();
        for (String month : new String[] {"2026-08", "2026-09", "2026-10", "2026-11"}) {
            mockMvc.perform(get("/v1/summaries/" + month)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.expenses.amount").value("0.00"));
        }
        ongoing("", "").andExpect(status().isCreated());
        assertThat(overview().get("total").get("amount").asString()).isEqualTo("2400.00");

        UUID other = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, other, "o2-" + other + "@example.com"));
        bearer = auth.bearer(other);
        assertThat(overview().get("total").get("amount").asString()).isEqualTo("0.00");
        ongoing("", "").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("erros do parcelado: restantes > total 422, fatura paga 422, categoria retroativa 422, corpo inválido 400, categoria futura consome só na competência")
    void deveRecusarParceladoInvalido() throws Exception {
        mockMvc.perform(post("/v1/cards/" + card + "/ongoing-installments", "{\"description\":\"x\",\"installmentAmount\":\"10.00\","
                + "\"totalInstallments\":3,\"remainingInstallments\":4}")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("ONGOING_INVALID_COUNT"));
        ongoing("", ",\"firstInvoiceDate\":\"2026-09-20\"").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ONGOING_FIRST_INVOICE_NOT_PAYABLE"));
        mockMvc.perform(post("/v1/cards/" + card + "/ongoing-installments", "{\"installmentAmount\":\"10.00\",\"totalInstallments\":1,"
                + "\"remainingInstallments\":1}")).andExpect(status().isBadRequest());
        ongoing("", ",\"categoryId\":\"" + tech + "\"").andExpect(status().isCreated());
        mockMvc.perform(get("/v1/summaries/2026-10")).andExpect(jsonPath("$.expenses.amount").value("150.00"));
        mockMvc.perform(get("/v1/summaries/2026-09")).andExpect(jsonPath("$.expenses.amount").value("0.00"));
    }

    @Test
    @DisplayName("credor livre: 201 sem Person, vai em 'other' do overview; pessoa e credor juntos 422; custo fixo e total ficam separados")
    void deveCadastrarOutraDivida() throws Exception {
        mockMvc.perform(post("/v1/debts", "{\"creditorName\":\"Banco do Brasil\",\"direction\":\"PAYABLE\",\"principal\":\"30000.00\","
                + "\"date\":\"2026-10-10\",\"expectedDate\":\"2027-01-10\",\"description\":\"Financiamento\"}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.person").doesNotExist())
                .andExpect(jsonPath("$.creditorName").value("Banco do Brasil"));
        mockMvc.perform(post("/v1/debts", "{\"direction\":\"PAYABLE\",\"principal\":\"10.00\",\"date\":\"2026-10-10\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DEBT_CREDITOR_INVALID"));
        mockMvc.perform(post("/v1/debts", "{\"creditorName\":\"Loja\",\"direction\":\"RECEIVABLE\",\"principal\":\"10.00\",\"date\":\"2026-10-10\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DEBT_CREDITOR_INVALID"));
        JsonNode o = overview();
        assertThat(o.get("parts").get("other").get("amount").asString()).isEqualTo("30000.00");
        assertThat(o.get("parts").get("people").get("amount").asString()).isEqualTo("0.00");
        assertThat(o.get("other").get("items").get(0).get("creditorName").asString()).isEqualTo("Banco do Brasil");
        assertThat(o.get("other").get("items").get(0).get("person").isNull()).isTrue();
        mockMvc.perform(get("/v1/debts")).andExpect(status().isOk()).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("meta DEBT_PAYOFF: nasce montando, baseline com total 0 é 422, ancora, progresso, dívida nova não deixa negativo, uma ativa, não reserva")
    void deveRodarAMetaDeQuitacao() throws Exception {
        JsonNode goal = body(mockMvc.perform(post("/v1/goals", "{\"kind\":\"DEBT_PAYOFF\"}")).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.kind").value("DEBT_PAYOFF"))
                .andExpect(jsonPath("$.name").value("Quitar todas as dívidas")).andExpect(jsonPath("$.baselineSet").value(false))
                .andExpect(jsonPath("$.progress").value("0.0000")).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn());
        String id = goal.get("id").asString();
        mockMvc.perform(post("/v1/goals", "{\"kind\":\"DEBT_PAYOFF\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBT_PAYOFF_GOAL_EXISTS"));
        mockMvc.perform(baseline(id)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBT_PAYOFF_NOTHING_TO_BASELINE"));
        mockMvc.perform(get("/v1/goals/" + id)).andExpect(jsonPath("$.status").value("ACTIVE"));

        ongoing("", "").andExpect(status().isCreated());
        mockMvc.perform(baseline(id)).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.baselineSet").value(true)).andExpect(jsonPath("$.baseline.amount").value("1200.00"))
                .andExpect(jsonPath("$.target.amount").value("1200.00")).andExpect(jsonPath("$.progress").value("0.0000"))
                .andExpect(jsonPath("$.remaining.amount").value("1200.00")).andExpect(jsonPath("$.grewSinceBaseline").value(false));
        mockMvc.perform(post("/v1/debts", "{\"creditorName\":\"Loja\",\"direction\":\"PAYABLE\",\"principal\":\"800.00\",\"date\":\"2026-10-10\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/v1/goals/" + id)).andExpect(jsonPath("$.progress").value("0.0000"))
                .andExpect(jsonPath("$.remaining.amount").value("2000.00")).andExpect(jsonPath("$.grewSinceBaseline").value(true))
                .andExpect(jsonPath("$.current.amount").value("0.00"));
        assertThat(overview().get("payoffGoal").get("remaining").get("amount").asString()).isEqualTo("2000.00");
        mockMvc.perform(baseline(id)).andExpect(jsonPath("$.baseline.amount").value("2000.00"))
                .andExpect(jsonPath("$.grewSinceBaseline").value(false));

        String account = body(mockMvc.perform(get("/v1/accounts")).andReturn()).get("items").get(0).get("id").asString();
        mockMvc.perform(post("/v1/goals/" + id + "/reservations", "{\"accountId\":\"" + account + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\"}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GOAL_KIND_NOT_RESERVABLE"));
        mockMvc.perform(get("/v1/dashboard/summary")).andExpect(jsonPath("$.balances.reserved.amount").value("0.00"));
        mockMvc.perform(get("/v1/goals")).andExpect(status().isOk()).andExpect(ContractValidation.conforms());

        String savings = body(mockMvc.perform(post("/v1/goals", "{\"name\":\"Viagem\",\"target\":\"500.00\"}")).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.kind").value("SAVINGS")).andReturn()).get("id").asString();
        mockMvc.perform(baseline(savings)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GOAL_KIND_MISMATCH"));
    }
}
