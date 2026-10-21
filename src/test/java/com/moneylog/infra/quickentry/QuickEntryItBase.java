package com.moneylog.infra.quickentry;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.quickentry.app.CreateQuickEntryCommand;
import com.moneylog.core.quickentry.app.CreateQuickEntryUseCase;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.domain.QuickAmountInput;
import com.moneylog.core.quickentry.domain.QuickEntryKind;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@Import(MutableClockConfig.class)
abstract class QuickEntryItBase {

    protected static final Instant AFTERNOON = Instant.parse("2026-10-21T15:00:00Z");

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ItAuth auth;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected CreateQuickEntryUseCase quick;
    @Autowired
    protected QuickEntryTokenGateway tokens;
    @Autowired
    protected CategoryGateway categories;

    protected record World(UUID userId, String bearer, UUID accountId, UUID tokenId, UUID food, UUID leisure, UUID otherExpense,
                           UUID salary, UUID otherIncome) {
    }

    @BeforeEach
    void relogio() {
        clock.set(AFTERNOON);
    }

    protected void committed(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    protected World world(String zone) throws Exception {
        UUID userId = UUID.randomUUID();
        committed(() -> {
            SqlFixtures.insertUser(jdbc, userId, "qe-" + userId + "@example.com");
            jdbc.update("update users set time_zone = ? where id = ?", zone, userId);
        });
        String bearer = auth.bearer(userId);
        UUID account = UUID.fromString(post(bearer, "/v1/accounts", "{\"name\":\"Carteira\",\"type\":\"WALLET\",\"openingBalance\":{\"amount\":\"1000.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}", null, 201).get("id").asString());
        UUID food = systemCategory(userId, "FOOD", "Alimentação", CategoryKind.EXPENSE);
        UUID leisure = systemCategory(userId, "LEISURE", "Lazer", CategoryKind.EXPENSE);
        UUID otherExpense = systemCategory(userId, "OTHER_EXPENSE", "Outros", CategoryKind.EXPENSE);
        UUID salary = systemCategory(userId, "SALARY", "Salário", CategoryKind.INCOME);
        UUID otherIncome = systemCategory(userId, "OTHER_INCOME", "Outras receitas", CategoryKind.INCOME);
        UUID tokenId = newToken(userId, account);
        return new World(userId, bearer, account, tokenId, food, leisure, otherExpense, salary, otherIncome);
    }

    protected World world() throws Exception {
        return world("America/Sao_Paulo");
    }

    protected UUID newToken(UUID userId, UUID accountId) {
        UUID id = UUID.randomUUID();
        String hash = (id.toString().replace("-", "") + id.toString().replace("-", "")).substring(0, 64);
        committed(() -> tokens.insert(QuickEntryToken.issue(id, userId, accountId, "Toque duplo", "abcd1234", hash, AFTERNOON.minusSeconds(60),
                AFTERNOON.plusSeconds(30L * 24 * 3600))));
        return id;
    }

    private UUID systemCategory(UUID userId, String key, String name, CategoryKind kind) {
        Category category = Category.createSystem(UUID.randomUUID(), userId, name, kind);
        committed(() -> categories.saveSystem(category, key));
        return category.id();
    }

    protected CreateQuickEntryCommand expense(World w, String key, String amount, String category, LocalDate date) {
        return new CreateQuickEntryCommand(w.userId(), w.tokenId(), w.accountId(), key, QuickEntryKind.EXPENSE, new QuickAmountInput.Text(amount),
                category, "Almoço", null, date, "203.0.113.7");
    }

    protected CreateQuickEntryCommand income(World w, String key, String amount, String category) {
        return new CreateQuickEntryCommand(w.userId(), w.tokenId(), w.accountId(), key, QuickEntryKind.INCOME, new QuickAmountInput.Text(amount),
                category, null, null, null, "203.0.113.7");
    }

    protected JsonNode post(String bearer, String url, String json, String idempotencyKey, int expected) throws Exception {
        var request = MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    protected JsonNode get(String bearer, String url) throws Exception {
        return objectMapper.readTree(mockMvc.perform(MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    protected BigDecimal balance(World w) throws Exception {
        return new BigDecimal(get(w.bearer(), "/v1/accounts/" + w.accountId()).get("currentBalance").get("amount").asString());
    }

    protected BigDecimal netWorth(World w) throws Exception {
        return new BigDecimal(get(w.bearer(), "/v1/dashboard/summary").get("netWorth").get("total").get("amount").asString());
    }

    protected long count(String table, UUID userId) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Long.class, userId);
    }

    protected long idempotencyRows(UUID userId) {
        return jdbc.queryForObject("select count(*) from idempotency_keys where user_id = ? and scope = 'POST /v1/quick-entries'", Long.class, userId);
    }
}
