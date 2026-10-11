package com.moneylog.infra.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
@DisplayName("Dashboard real (T29)")
class DashboardIT {

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
    private String nubank;
    private String xp;
    private String xpInvest;
    private String food;
    private String transport;
    private String salary;

    @BeforeEach
    void cenario() {
        clock.set(MutableClockConfig.INITIAL);
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "dash-" + userId + "@moneylog.com"));
        bearer = auth.bearer(userId);
    }

    private void committed(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    private MockHttpServletRequestBuilder post(String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String account(String name, String type, String balance) throws Exception {
        return body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"openingBalance\":{\"amount\":\""
                + balance + "\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private String category(String name, CategoryKind kind) {
        Category category = Category.create(UUID.randomUUID(), userId, name, kind);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        return category.id().toString();
    }

    private String create(String json) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        return body(mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private String expense(String amount, String date, String description, String category, String method) throws Exception {
        return create("{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description + "\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + category + "\",\"method\":\"" + method + "\"}");
    }

    private void octoberScenario() throws Exception {
        nubank = account("Nubank", "CHECKING", "20000.00");
        xp = account("XP", "CHECKING", "0.00");
        xpInvest = account("XP Invest", "INVESTMENT", "0.00");
        food = category("Alimentação", CategoryKind.EXPENSE);
        transport = category("Transporte", CategoryKind.EXPENSE);
        salary = category("Salário", CategoryKind.INCOME);
        String pix = expense("100.00", "2026-10-10", "Mercado do bairro", food, "PIX");
        expense("40.00", "2026-10-11", "Uber", transport, "DEBIT");
        create("{\"type\":\"INCOME\",\"amount\":\"3000.00\",\"date\":\"2026-10-05\",\"description\":\"Salário\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + salary + "\",\"method\":\"TRANSFER\"}");
        create("{\"type\":\"TRANSFER\",\"amount\":\"1000.00\",\"date\":\"2026-10-10\",\"description\":\"Para a XP\",\"fromAccountId\":\""
                + nubank + "\",\"toAccountId\":\"" + xp + "\"}");
        create("{\"type\":\"INVESTMENT\",\"amount\":\"500.00\",\"date\":\"2026-10-15\",\"description\":\"CDB\",\"cashAccountId\":\"" + nubank
                + "\",\"investmentAccountId\":\"" + xpInvest + "\",\"direction\":\"CONTRIBUTION\"}");
        create("{\"type\":\"REFUND\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"description\":\"Estorno do mercado\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"refundOfTransactionId\":\"" + pix + "\"}");
        String wrong = expense("30.00", "2026-10-12", "Lançada errado", food, "PIX");
        mockMvc.perform(post("/v1/transactions/" + wrong + "/void", "{\"reason\":\"engano\"}")).andExpect(status().isOk());
        expense("70.00", "2026-11-05", "Conta de novembro", food, "BOLETO");
    }

    private ResultActions summary(String query) throws Exception {
        return mockMvc.perform(get("/v1/dashboard/summary" + query));
    }

    @Test
    @DisplayName("CA1: sem contas, hasAccounts é false e todos os valores são 0,00; só uma conta arquivada, hasAccounts é true")
    void deveInformarSeHaContas() throws Exception {
        summary("").andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.hasAccounts").value(false))
                .andExpect(jsonPath("$.balances.inAccounts.amount").value("0.00")).andExpect(jsonPath("$.balances.available.amount").value("0.00"))
                .andExpect(jsonPath("$.month.consumption.amount").value("0.00")).andExpect(jsonPath("$.month.cashFlow.net.amount").value("0.00"))
                .andExpect(jsonPath("$.netWorth.total.amount").value("0.00")).andExpect(jsonPath("$.isProjection").value(false));

        String account = account("Conta", "CHECKING", "0.00");
        mockMvc.perform(post("/v1/accounts/" + account + "/archive", "")).andExpect(status().isOk());
        summary("").andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.hasAccounts").value(true));
    }

    @Test
    @DisplayName("CA2: com asOf=2026-10-31: em contas 22.410,00 (Nubank + XP), investimentos 500,00, comprometido 70,00 (a despesa de 05/11, S-258), available = em contas - 70,00, mês: receita 3.000,00, despesas 140,00 (Pix 100,00, débito 40,00), reembolsos 50,00, consumo 90,00, fluxo 3.050,00 / 640,00 / 2.410,00, investido 500,00, net 2.910,00")
    void deveResumirOMes() throws Exception {
        octoberScenario();

        MvcResult result = summary("?asOf=2026-10-31").andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.asOf").value("2026-10-31")).andExpect(jsonPath("$.isProjection").value(true))
                .andExpect(jsonPath("$.hasAccounts").value(true)).andExpect(jsonPath("$.balances.inAccounts.amount").value("22410.00"))
                .andExpect(jsonPath("$.balances.investments.amount").value("500.00")).andExpect(jsonPath("$.balances.available.amount").value("22340.00"))
                .andExpect(jsonPath("$.balances.reserved.amount").value("0.00")).andExpect(jsonPath("$.balances.committed30d.amount").value("70.00"))
                .andExpect(jsonPath("$.committed.d7.amount").value("70.00")).andExpect(jsonPath("$.committed.m12.amount").value("70.00"))
                .andExpect(jsonPath("$.projected.d30.amount").value("22340.00")).andExpect(jsonPath("$.month.competence").value("2026-10"))
                .andExpect(jsonPath("$.month.income.amount").value("3000.00")).andExpect(jsonPath("$.month.expensesPaid.total.amount").value("140.00"))
                .andExpect(jsonPath("$.month.expensesPaid.byMethod.PIX.amount").value("100.00"))
                .andExpect(jsonPath("$.month.expensesPaid.byMethod.DEBIT.amount").value("40.00"))
                .andExpect(jsonPath("$.month.expensesPaid.byMethod.BOLETO.amount").value("0.00"))
                .andExpect(jsonPath("$.month.credit.amount").value("0.00")).andExpect(jsonPath("$.month.refunds.amount").value("50.00"))
                .andExpect(jsonPath("$.month.consumption.amount").value("90.00")).andExpect(jsonPath("$.month.cashFlow.in.amount").value("3050.00"))
                .andExpect(jsonPath("$.month.cashFlow.out.amount").value("640.00")).andExpect(jsonPath("$.month.cashFlow.net.amount").value("2410.00"))
                .andExpect(jsonPath("$.month.investedNet.amount").value("500.00")).andExpect(jsonPath("$.month.net.amount").value("2910.00"))
                .andExpect(jsonPath("$.netWorth.accounts.amount").value("22910.00")).andReturn();

        JsonNode list = body(mockMvc.perform(get("/v1/transactions?from=2026-10-01&to=2026-10-31")).andExpect(status().isOk()).andReturn());
        assertThat(list.get("totals").get("cashFlow")).isEqualTo(body(result).get("month").get("cashFlow"));
    }

    @Test
    @DisplayName("CA4: asOf maior que hoje marca isProjection; sem asOf é hoje no fuso do usuário (relógio fixo) e não é projeção")
    void deveMarcarProjecaoEUsarHoje() throws Exception {
        octoberScenario();

        summary("").andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.asOf").value("2026-10-10"))
                .andExpect(jsonPath("$.isProjection").value(false)).andExpect(jsonPath("$.month.competence").value("2026-10"))
                .andExpect(jsonPath("$.balances.inAccounts.amount").value("22950.00")).andExpect(jsonPath("$.balances.investments.amount").value("0.00"));
        summary("?asOf=2026-10-11").andExpect(status().isOk()).andExpect(jsonPath("$.isProjection").value(true)).andExpect(ContractValidation.conforms());
        summary("?asOf=2026-10-10").andExpect(status().isOk()).andExpect(jsonPath("$.isProjection").value(false));
        summary("?asOf=2026-11-30").andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.month.competence").value("2026-11"))
                .andExpect(jsonPath("$.month.consumption.amount").value("70.00")).andExpect(jsonPath("$.month.income.amount").value("0.00"));
        summary("?asOf=ontem").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("CA5 + S-258: upcoming sem parâmetros devolve só as movimentações futuras do cenário (Uber 11/10, aplicação 15/10 e a conta de 05/11, como SCHEDULED_TRANSACTION), undated vazio; com from > to é 400")
    void deveListarCompromissosVazios() throws Exception {
        octoberScenario();

        mockMvc.perform(get("/v1/dashboard/upcoming")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.items.length()").value(3)).andExpect(jsonPath("$.items[*].kind").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("SCHEDULED_TRANSACTION"))))
                .andExpect(jsonPath("$.items[*].refType").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("TRANSACTION"))))
                .andExpect(jsonPath("$.items[0].dueDate").value("2026-10-11")).andExpect(jsonPath("$.items[0].amount.amount").value("40.00"))
                .andExpect(jsonPath("$.items[0].label").value("Uber")).andExpect(jsonPath("$.items[0].bucket").value("TOMORROW"))
                .andExpect(jsonPath("$.items[1].dueDate").value("2026-10-15")).andExpect(jsonPath("$.items[1].amount.amount").value("500.00"))
                .andExpect(jsonPath("$.items[2].dueDate").value("2026-11-05")).andExpect(jsonPath("$.items[2].amount.amount").value("70.00"))
                .andExpect(jsonPath("$.undated").isEmpty())
                .andExpect(jsonPath("$.totals.out.amount").value("610.00")).andExpect(jsonPath("$.totals.in.amount").value("0.00"));
        mockMvc.perform(get("/v1/dashboard/upcoming?from=2026-10-01&to=2026-12-31")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.items.length()").value(3));
        mockMvc.perform(get("/v1/dashboard/upcoming?from=2026-10-12&to=2026-10-31")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].dueDate").value("2026-10-15"));
        mockMvc.perform(get("/v1/dashboard/upcoming?from=2026-11-01&to=2026-10-01")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from")).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/dashboard/upcoming?from=amanha")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("isolamento: cada usuário só vê os próprios números")
    void deveIsolarPorUsuario() throws Exception {
        octoberScenario();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));

        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dashboard/summary?asOf=2026-10-31").header(HttpHeaders.AUTHORIZATION, auth.bearer(other)))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.hasAccounts").value(false))
                .andExpect(jsonPath("$.balances.inAccounts.amount").value("0.00")).andExpect(jsonPath("$.month.consumption.amount").value("0.00"));
    }
}
