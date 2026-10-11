package com.moneylog.qa;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpMethod;

import tools.jackson.databind.JsonNode;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;

final class DebtQa {

    private DebtQa() {
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String person(Actor a, String name) throws Exception {
        return CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/people", "{\"name\":\"" + name + "\"}", null)).node().get("id").asString();
    }

    static Res createTx(Actor a, String body) throws Exception {
        return a.call(HttpMethod.POST, "/v1/transactions", body, key());
    }

    static String loanBody(Actor a, String amount, String date, String personId, String expectedDate) {
        return "{\"type\":\"LOAN_GIVEN\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Empréstimo\",\"accountId\":\""
                + a.nubank + "\",\"newDebt\":{\"personId\":\"" + personId + "\"" + (expectedDate == null ? "" : ",\"expectedDate\":\"" + expectedDate + "\"")
                + "}}";
    }

    static Res loan(Actor a, String amount, String date, String personId, String expectedDate) throws Exception {
        return createTx(a, loanBody(a, amount, date, personId, expectedDate));
    }

    static String receivedBody(Actor a, String amount, String date, String debtId) {
        return "{\"type\":\"MONEY_RECEIVED\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Devolução\",\"accountId\":\""
                + a.nubank + "\",\"debtId\":\"" + debtId + "\"}";
    }

    static Res received(Actor a, String amount, String date, String debtId) throws Exception {
        return createTx(a, receivedBody(a, amount, date, debtId));
    }

    static Res borrowed(Actor a, String amount, String date, String personId) throws Exception {
        return createTx(a, "{\"type\":\"MONEY_RECEIVED\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Captação\",\"accountId\":\""
                + a.nubank + "\",\"newDebt\":{\"personId\":\"" + personId + "\"}}");
    }

    static Res payment(Actor a, String amount, String date, String debtId) throws Exception {
        return createTx(a, "{\"type\":\"DEBT_PAYMENT\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Pagamento\",\"accountId\":\""
                + a.nubank + "\",\"debtId\":\"" + debtId + "\"}");
    }

    static String shareJson(String amount, String personId) {
        return "{\"amount\":\"" + amount + "\",\"newDebt\":{\"personId\":\"" + personId + "\"}}";
    }

    static String sharedExpenseBody(Actor a, String amount, String date, List<String> shares) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Dividida\",\"accountId\":\"" + a.nubank
                + "\",\"categoryId\":\"" + a.food + "\",\"method\":\"PIX\",\"shares\":[" + String.join(",", shares) + "]}";
    }

    static Res sharedExpense(Actor a, String amount, String date, List<String> shares) throws Exception {
        return createTx(a, sharedExpenseBody(a, amount, date, shares));
    }

    static Res accountRefund(Actor a, String amount, String date, String categoryId, String original) throws Exception {
        return createTx(a, "{\"type\":\"REFUND\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Reembolso\",\"accountId\":\""
                + a.nubank + "\",\"categoryId\":\"" + categoryId + "\",\"refundOfTransactionId\":\"" + original + "\"}");
    }

    static Res plainDebt(Actor a, String personId, String direction, String principal, String date, String expectedDate, String categoryId) throws Exception {
        return a.call(HttpMethod.POST, "/v1/debts", "{\"personId\":\"" + personId + "\",\"direction\":\"" + direction + "\",\"principal\":\"" + principal
                + "\",\"date\":\"" + date + "\",\"description\":\"Dívida\"" + (expectedDate == null ? "" : ",\"expectedDate\":\"" + expectedDate + "\"")
                + (categoryId == null ? "" : ",\"categoryId\":\"" + categoryId + "\"") + "}", null);
    }

    static Res cancelDebt(Actor a, String debtId) throws Exception {
        return a.call(HttpMethod.POST, "/v1/debts/" + debtId + "/cancel", "{\"reason\":\"qa\"}", null);
    }

    static Res voidDebt(Actor a, String debtId) throws Exception {
        return a.call(HttpMethod.POST, "/v1/debts/" + debtId + "/void", "{\"reason\":\"qa\"}", null);
    }

    static List<JsonNode> all(Actor a, String url) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page < 200; page++) {
            JsonNode slice = a.getOk(url + (url.contains("?") ? "&" : "?") + "pageSize=50&page=" + page);
            slice.get("items").forEach(out::add);
            if (!slice.get("hasNext").asBoolean()) {
                break;
            }
        }
        return out;
    }

    static List<JsonNode> debts(Actor a) throws Exception {
        return all(a, "/v1/debts");
    }

    static List<JsonNode> pending(Actor a, LocalDate to) throws Exception {
        return all(a, "/v1/recurrences/occurrences?status=PENDING&to=" + to);
    }

    static String recurrenceBody(String type, String amount, String description, String categoryId, String target, String frequency, String start,
                                 String extra) {
        return "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"description\":\"" + description + "\",\"categoryId\":\"" + categoryId + "\","
                + target + ",\"frequency\":\"" + frequency + "\",\"startDate\":\"" + start + "\"" + (extra == null ? "" : "," + extra) + "}";
    }

    static String accountTarget(Actor a, String type) {
        return "\"accountId\":\"" + a.nubank + "\",\"method\":\"" + ("INCOME".equals(type) ? "TRANSFER" : "PIX") + "\"";
    }

    static Res realize(Actor a, String recurrenceId, String date, String amount) throws Exception {
        return a.call(HttpMethod.POST, "/v1/recurrences/" + recurrenceId + "/occurrences/" + date + "/realize",
                amount == null ? "{}" : "{\"amount\":\"" + amount + "\"}", key());
    }

    static Res skip(Actor a, String recurrenceId, String date) throws Exception {
        return a.call(HttpMethod.POST, "/v1/recurrences/" + recurrenceId + "/occurrences/" + date + "/skip", "", null);
    }
}
