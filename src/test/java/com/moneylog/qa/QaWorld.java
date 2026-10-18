package com.moneylog.qa;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

final class QaWorld {

    final MockMvc mvc;
    final ObjectMapper json;
    final JdbcTemplate jdbc;
    final ItAuth auth;
    final PlatformTransactionManager tm;

    QaWorld(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc, ItAuth auth, PlatformTransactionManager tm) {
        this.mvc = mvc;
        this.json = json;
        this.jdbc = jdbc;
        this.auth = auth;
        this.tm = tm;
    }

    final class Actor {
        final UUID id = UUID.randomUUID();
        final String bearer;
        String nubank;
        String xp;
        String xpInvest;
        String food;
        String salary;
        String expenseTx;

        Actor(String tag) throws Exception {
            new TransactionTemplate(tm).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, id, tag + "-" + id + "@example.com"));
            bearer = auth.bearer(id);
            nubank = account("Nubank", "CHECKING", "20000.00");
            xp = account("XP", "CHECKING", "0.00");
            xpInvest = account("XP Invest", "INVESTMENT", "0.00");
            food = category("Alimentação", CategoryKind.EXPENSE);
            salary = category("Salário", CategoryKind.INCOME);
        }

        Actor withExpense() throws Exception {
            expenseTx = create(expense(nubank, food, "100.00", "2026-10-10", "Mercado")).get("id").asString();
            return this;
        }

        MockHttpServletRequestBuilder req(HttpMethod method, String url, String body) {
            MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, url).header(HttpHeaders.AUTHORIZATION, bearer);
            if (body != null) {
                b.contentType(MediaType.APPLICATION_JSON).content(body);
            }
            return b;
        }

        JsonNode get(String url) throws Exception {
            return body(mvc.perform(req(HttpMethod.GET, url, null)).andReturn());
        }

        String account(String name, String type, String balance) throws Exception {
            return body(mvc.perform(req(HttpMethod.POST, "/v1/accounts", "{\"name\":\"" + name + "\",\"type\":\"" + type
                    + "\",\"openingBalance\":{\"amount\":\"" + balance + "\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andReturn())
                    .get("id").asString();
        }

        String category(String name, CategoryKind kind) {
            Category category = Category.create(UUID.randomUUID(), id, name, kind);
            new TransactionTemplate(tm).executeWithoutResult(s -> SqlFixtures.insertCategory(jdbc, category));
            return category.id().toString();
        }

        MvcResult post(String body) throws Exception {
            return mvc.perform(req(HttpMethod.POST, "/v1/transactions", body).header("Idempotency-Key", UUID.randomUUID().toString())).andReturn();
        }

        JsonNode create(String body) throws Exception {
            MvcResult r = post(body);
            if (r.getResponse().getStatus() != 201) {
                throw new AssertionError("create falhou: " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString() + " <- " + body);
            }
            return body(r);
        }
    }

    JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? json.createObjectNode() : json.readTree(content);
    }

    static String firstGroup(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : text;
    }

    static String expense(String account, String category, String amount, String date, String description) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description
                + "\",\"accountId\":\"" + account + "\",\"categoryId\":\"" + category + "\",\"method\":\"PIX\"}";
    }

    static String income(String account, String category, String amount, String date) {
        return "{\"type\":\"INCOME\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Receita\",\"accountId\":\""
                + account + "\",\"categoryId\":\"" + category + "\",\"method\":\"TRANSFER\"}";
    }

    static String transfer(String from, String to, String amount, String date) {
        return "{\"type\":\"TRANSFER\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Transf\",\"fromAccountId\":\""
                + from + "\",\"toAccountId\":\"" + to + "\"}";
    }

    static String investment(String cash, String invest, String direction, String amount, String date) {
        return "{\"type\":\"INVESTMENT\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"CDB\",\"cashAccountId\":\""
                + cash + "\",\"investmentAccountId\":\"" + invest + "\",\"direction\":\"" + direction + "\"}";
    }

    static String adjustment(String account, String direction, String amount, String date) {
        return "{\"type\":\"ADJUSTMENT\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Ajuste\",\"accountId\":\""
                + account + "\",\"direction\":\"" + direction + "\",\"reason\":\"conferência\"}";
    }

    static String refund(String account, String category, String original, String amount, String date) {
        return "{\"type\":\"REFUND\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Reembolso\",\"accountId\":\""
                + account + "\",\"categoryId\":\"" + category + "\",\"refundOfTransactionId\":\"" + original + "\"}";
    }

    long ledgerSum(UUID userId) {
        Long sum = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active", Long.class, userId);
        return sum == null ? 0 : sum;
    }

    long unbalancedActiveGroups(UUID userId) {
        Long n = jdbc.queryForObject("select count(*) from (select source_id from ledger_postings where user_id = ? and active "
                + "group by source_id having sum(amount_cents) <> 0) t", Long.class, userId);
        return n == null ? 0 : n;
    }
}
