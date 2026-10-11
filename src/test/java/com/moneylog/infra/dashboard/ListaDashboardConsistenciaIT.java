package com.moneylog.infra.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
@DisplayName("Lista × entries × dashboard: os mesmos números (T29 CA3, PR 01 regra 10, plano §4.7)")
class ListaDashboardConsistenciaIT {

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

    private JsonNode json(String url) throws Exception {
        return body(mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private static String amount(JsonNode money) {
        return money.get("amount").asString();
    }

    private void assertMonthMatches(String competence, String first, String last) throws Exception {
        JsonNode summary = json("/v1/dashboard/summary?asOf=" + last);
        JsonNode month = summary.get("month");

        assertThat(amount(json("/v1/entries?competence=" + competence + "&lens=CONSUMPTION").get("totals").get("total"))).isEqualTo(amount(month.get("consumption")));
        assertThat(amount(json("/v1/entries?competence=" + competence + "&lens=INCOME").get("totals").get("total"))).isEqualTo(amount(month.get("income")));
        JsonNode cashFlow = json("/v1/transactions?from=" + first + "&to=" + last).get("totals").get("cashFlow");
        assertThat(amount(cashFlow.get("in"))).isEqualTo(amount(month.get("cashFlow").get("in")));
        assertThat(amount(cashFlow.get("out"))).isEqualTo(amount(month.get("cashFlow").get("out")));
        assertThat(amount(cashFlow.get("net"))).isEqualTo(amount(month.get("cashFlow").get("net")));
        assertThat(new BigDecimal(amount(month.get("expensesPaid").get("total"))).add(new BigDecimal(amount(month.get("credit"))))
                .subtract(new BigDecimal(amount(month.get("refunds"))))).isEqualByComparingTo(new BigDecimal(amount(month.get("consumption"))));
        assertThat(new BigDecimal(amount(month.get("income"))).subtract(new BigDecimal(amount(month.get("consumption")))))
                .isEqualByComparingTo(new BigDecimal(amount(month.get("net"))));
    }

    @Test
    @DisplayName("outubro e novembro: entries (consumo e receita), totals.cashFlow da lista e month do dashboard são iguais")
    void deveBaterListaEntriesEDashboard() throws Exception {
        octoberScenario();

        assertMonthMatches("2026-10", "2026-10-01", "2026-10-31");
        assertMonthMatches("2026-11", "2026-11-01", "2026-11-30");
        assertMonthMatches("2026-09", "2026-09-01", "2026-09-30");
    }

    @Test
    @DisplayName("Σ currentBalance das contas CASH (ativas e arquivadas) = summary.balances.inAccounts; Σ das contas de investimento = balances.investments")
    void deveBaterSaldosDasContasEDashboard() throws Exception {
        octoberScenario();
        String savings = account("Reserva", "SAVINGS", "0.00");
        mockMvc.perform(post("/v1/accounts/" + savings + "/archive", "")).andExpect(status().isOk());
        clock.set(java.time.Instant.parse("2026-10-20T15:00:00Z"));
        bearer = auth.bearer(userId);

        JsonNode accounts = json("/v1/accounts?pageSize=50");
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal investments = BigDecimal.ZERO;
        int archived = 0;
        for (JsonNode account : accounts.get("items")) {
            BigDecimal balance = new BigDecimal(amount(account.get("currentBalance")));
            if (account.get("type").asString().equals("INVESTMENT")) {
                investments = investments.add(balance);
            } else {
                cash = cash.add(balance);
            }
            if (account.get("status").asString().equals("ARCHIVED")) {
                archived++;
            }
        }
        JsonNode balances = json("/v1/dashboard/summary").get("balances");

        assertThat(archived).isEqualTo(1);
        assertThat(cash).isEqualByComparingTo(new BigDecimal(amount(balances.get("inAccounts"))));
        assertThat(investments).isEqualByComparingTo(new BigDecimal(amount(balances.get("investments"))));
        assertThat(amount(balances.get("inAccounts"))).isEqualTo("22410.00");
    }

    @Test
    @DisplayName("depois de estornar uma movimentação os três lugares mudam juntos (a mesma fonte)")
    void deveMudarJuntosDepoisDeEstornar() throws Exception {
        octoberScenario();
        String uber = null;
        for (JsonNode item : json("/v1/transactions?from=2026-10-01&to=2026-10-31&type=EXPENSE").get("items")) {
            if (item.get("description").asString().equals("Uber")) {
                uber = item.get("id").asString();
            }
        }
        assertThat(uber).isNotNull();
        mockMvc.perform(post("/v1/transactions/" + uber + "/void", "{\"reason\":\"engano\"}")).andExpect(status().isOk());

        assertMonthMatches("2026-10", "2026-10-01", "2026-10-31");
        assertThat(amount(json("/v1/dashboard/summary?asOf=2026-10-31").get("month").get("consumption"))).isEqualTo("50.00");
        assertThat(amount(json("/v1/entries?competence=2026-10").get("totals").get("total"))).isEqualTo("50.00");
        assertThat(amount(json("/v1/transactions?from=2026-10-01&to=2026-10-31").get("totals").get("cashFlow").get("out"))).isEqualTo("600.00");
    }
}
