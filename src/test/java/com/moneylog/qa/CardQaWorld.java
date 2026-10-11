package com.moneylog.qa;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.SqlFixtures;

final class CardQaWorld {

    final MockMvc mvc;
    final ObjectMapper json;
    final JdbcTemplate jdbc;
    final ItAuth auth;
    final PlatformTransactionManager tm;
    final MutableClock clock;
    final java.util.Set<String> seenCodes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    final List<String> contractViolations = java.util.Collections.synchronizedList(new ArrayList<>());

    CardQaWorld(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc, ItAuth auth, PlatformTransactionManager tm, MutableClock clock) {
        this.mvc = mvc;
        this.json = json;
        this.jdbc = jdbc;
        this.auth = auth;
        this.tm = tm;
        this.clock = clock;
    }

    record Res(int status, String text, JsonNode node) {
        String code() {
            return node == null ? "" : node.path("code").asString("");
        }

        @Override
        public String toString() {
            return status + " " + (text.length() > 300 ? text.substring(0, 300) : text);
        }
    }

    final class Actor {
        final UUID id = UUID.randomUUID();
        String bearer;
        String nubank;
        String card;
        String tech;
        String food;
        String income;

        Actor(String tag) throws Exception {
            this(tag, 25, 5);
        }

        Actor(String tag, int closingDay, int dueDay) throws Exception {
            new TransactionTemplate(tm).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, id, tag + "-" + id + "@moneylog.com"));
            bearer = auth.bearer(id);
            nubank = ok(call(HttpMethod.POST, "/v1/accounts", "{\"name\":\"Nubank\",\"type\":\"CHECKING\",\"institution\":\"Nubank\","
                    + "\"openingBalance\":{\"amount\":\"20000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}", null)).node().get("id").asString();
            card = newCard("Roxinho", closingDay, dueDay, "10000.00");
            tech = category("Tecnologia", CategoryKind.EXPENSE);
            food = category("Alimentação", CategoryKind.EXPENSE);
            income = category("Salário", CategoryKind.INCOME);
        }

        void at(Instant instant) {
            clock.set(instant);
            bearer = auth.bearer(id);
        }

        String newCard(String name, int closingDay, int dueDay, String limit) throws Exception {
            return ok(call(HttpMethod.POST, "/v1/cards", "{\"name\":\"" + name + "\",\"institution\":\"Nubank\",\"limit\":{\"amount\":\"" + limit
                    + "\",\"currency\":\"BRL\"},\"closingDay\":" + closingDay + ",\"dueDay\":" + dueDay + ",\"defaultPaymentAccountId\":\"" + nubank
                    + "\"}", null)).node().get("id").asString();
        }

        String category(String name, CategoryKind kind) {
            Category category = Category.create(UUID.randomUUID(), id, name, kind);
            new TransactionTemplate(tm).executeWithoutResult(s -> SqlFixtures.insertCategory(jdbc, category));
            return category.id().toString();
        }

        MockHttpServletRequestBuilder req(HttpMethod method, String url, String body) {
            MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, url).header(HttpHeaders.AUTHORIZATION, bearer);
            if (body != null) {
                b.contentType(MediaType.APPLICATION_JSON).content(body);
            }
            return b;
        }

        Res call(HttpMethod method, String url, String body, String key) throws Exception {
            MockHttpServletRequestBuilder b = req(method, url, body);
            if (key != null) {
                b.header("Idempotency-Key", key);
            }
            MvcResult r = mvc.perform(b).andReturn();
            try {
                ContractValidation.conforms().match(r);
            } catch (AssertionError | Exception e) {
                contractViolations.add(method + " " + url + " -> " + r.getResponse().getStatus() + ": "
                        + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
            }
            return res(r);
        }

        Res raw(HttpMethod method, String url, String body, String key) throws Exception {
            MockHttpServletRequestBuilder b = req(method, url, body);
            if (key != null) {
                b.header("Idempotency-Key", key);
            }
            MvcResult r = mvc.perform(b).andReturn();
            try {
                ContractValidation.responseOnly().match(r);
            } catch (AssertionError | Exception e) {
                contractViolations.add("RAW " + method + " " + url + " -> " + r.getResponse().getStatus() + ": "
                        + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
            }
            return res(r);
        }

        Res get(String url) throws Exception {
            return call(HttpMethod.GET, url, null, null);
        }

        JsonNode getOk(String url) throws Exception {
            return ok(get(url)).node();
        }

        Res purchase(String amount, int installments, String date, String description) throws Exception {
            return purchaseOn(card, tech, amount, installments, date, description);
        }

        Res purchaseOn(String cardId, String categoryId, String amount, int installments, String date, String description) throws Exception {
            return call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"CARD_PURCHASE\",\"amount\":\"" + amount + "\",\"date\":\"" + date
                    + "\",\"description\":\"" + description + "\",\"cardId\":\"" + cardId + "\",\"categoryId\":\"" + categoryId
                    + "\",\"installments\":" + installments + "}", UUID.randomUUID().toString());
        }

        Res cardRefund(String amount, String date, String original) throws Exception {
            return call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"REFUND\",\"amount\":\"" + amount + "\",\"date\":\"" + date
                    + "\",\"description\":\"Reembolso\",\"cardId\":\"" + card + "\",\"categoryId\":\"" + tech + "\""
                    + (original == null ? "" : ",\"refundOfTransactionId\":\"" + original + "\"") + "}", UUID.randomUUID().toString());
        }

        Res pay(UUID invoiceId, String amount, String date, String key) throws Exception {
            return call(HttpMethod.POST, "/v1/invoices/" + invoiceId + "/payments", "{\"accountId\":\"" + nubank + "\",\"amount\":\""
                    + amount + "\",\"date\":\"" + date + "\"}", key == null ? UUID.randomUUID().toString() : key);
        }

        Res voidTx(String txId) throws Exception {
            return call(HttpMethod.POST, "/v1/transactions/" + txId + "/void", "{\"reason\":\"qa\"}", null);
        }

        Res cancel(String txId) throws Exception {
            return call(HttpMethod.POST, "/v1/transactions/" + txId + "/cancel-installments", "{\"reason\":\"qa\"}", null);
        }

        UUID invoiceId(LocalDate closing) {
            return InvoiceIds.of(UUID.fromString(card), closing);
        }

        JsonNode cardNow() throws Exception {
            return getOk("/v1/cards/" + card);
        }

        List<JsonNode> invoices() throws Exception {
            return invoicesOf(card);
        }

        List<JsonNode> invoicesOf(String cardId) throws Exception {
            List<JsonNode> out = new ArrayList<>();
            for (int page = 1; page < 20; page++) {
                JsonNode slice = getOk("/v1/cards/" + cardId + "/invoices?pageSize=50&page=" + page);
                slice.get("items").forEach(out::add);
                if (!slice.get("hasNext").asBoolean()) {
                    break;
                }
            }
            return out;
        }

        Res changeDays(String cardId, int closing, int due) throws Exception {
            JsonNode card0 = getOk("/v1/cards/" + cardId);
            JsonNode preview = getOk("/v1/cards/" + cardId + "/billing-change-preview?closingDay=" + closing + "&dueDay=" + due);
            return putDays(cardId, closing, due, preview.get("effectiveFromClosingDate").asString(), card0.get("version").asInt());
        }

        Res putDays(String cardId, int closing, int due, String confirmed, int version) throws Exception {
            return call(HttpMethod.PUT, "/v1/cards/" + cardId, "{\"name\":\"Roxinho\",\"institution\":\"Nubank\",\"limit\":{\"amount\":\"10000.00\","
                    + "\"currency\":\"BRL\"},\"closingDay\":" + closing + ",\"dueDay\":" + due + ",\"defaultPaymentAccountId\":\"" + nubank + "\""
                    + (confirmed == null ? "" : ",\"confirmedEffectiveFrom\":\"" + confirmed + "\"") + ",\"version\":" + version + "}", null);
        }

        long cardBook(String cardId) {
            Long v = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active "
                    + "and book_type = 'CARD' and book_id = ?::uuid", Long.class, id, cardId);
            return v == null ? 0 : v;
        }

        long ledgerSum() {
            Long v = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active", Long.class, id);
            return v == null ? 0 : v;
        }

        long unbalancedGroups() {
            Long n = jdbc.queryForObject("select count(*) from (select source_id from ledger_postings where user_id = ? and active "
                    + "group by source_id having sum(amount_cents) <> 0) t", Long.class, id);
            return n == null ? 0 : n;
        }

        long count(String table) {
            Long n = jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Long.class, id);
            return n == null ? 0 : n;
        }
    }

    Res res(MvcResult r) throws Exception {
        String text = r.getResponse().getContentAsString();
        JsonNode node = text.isEmpty() || !(text.startsWith("{") || text.startsWith("[")) ? null : json.readTree(text);
        Res out = new Res(r.getResponse().getStatus(), text, node);
        if (r.getResponse().getStatus() >= 400 && !out.code().isEmpty()) {
            seenCodes.add(out.code());
        }
        return out;
    }

    static Res ok(Res r) {
        if (r.status() >= 300) {
            throw new AssertionError("esperava sucesso: " + r);
        }
        return r;
    }

    static long cents(String amount) {
        return new java.math.BigDecimal(amount).movePointRight(2).longValueExact();
    }

    static long cents(JsonNode money) {
        return cents(money.get("amount").asString());
    }

    static String money(long cents) {
        return java.math.BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
