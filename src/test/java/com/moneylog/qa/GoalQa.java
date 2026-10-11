package com.moneylog.qa;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.HttpMethod;

import tools.jackson.databind.JsonNode;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;

final class GoalQa {

    private GoalQa() {
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String goalBody(String name, String target, String deadline, String priority) {
        return "{\"name\":\"" + name + "\",\"target\":\"" + target + "\"" + (deadline == null ? "" : ",\"deadline\":\"" + deadline + "\"")
                + ",\"priority\":\"" + priority + "\"}";
    }

    static Res createGoal(Actor a, String name, String target, String deadline) throws Exception {
        return a.call(HttpMethod.POST, "/v1/goals", goalBody(name, target, deadline, "HIGH"), null);
    }

    static String goal(Actor a, String name, String target, String deadline) throws Exception {
        return CardQaWorld.ok(createGoal(a, name, target, deadline)).node().get("id").asString();
    }

    static String reservationBody(String accountId, String amount, String date) {
        return "{\"accountId\":\"" + accountId + "\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\"}";
    }

    static Res reserve(Actor a, String goalId, String accountId, String amount, LocalDate date) throws Exception {
        return a.call(HttpMethod.POST, "/v1/goals/" + goalId + "/reservations", reservationBody(accountId, amount, date.toString()), key());
    }

    static Res reserveKey(Actor a, String goalId, String accountId, String amount, LocalDate date, String key) throws Exception {
        return a.call(HttpMethod.POST, "/v1/goals/" + goalId + "/reservations", reservationBody(accountId, amount, date.toString()), key);
    }

    static JsonNode getGoal(Actor a, String goalId) throws Exception {
        return a.getOk("/v1/goals/" + goalId);
    }

    static String account(Actor a, String name, String type, String opening) throws Exception {
        return account(a, name, type, opening, "2026-10-01");
    }

    static String account(Actor a, String name, String type, String opening, String openingDate) throws Exception {
        return CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/accounts", "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"institution\":\"X\","
                + "\"openingBalance\":{\"amount\":\"" + opening + "\",\"currency\":\"BRL\"},\"openingDate\":\"" + openingDate + "\"}", null)).node().get("id").asString();
    }

    static long net(org.springframework.jdbc.core.JdbcTemplate jdbc, Actor a, String goalId, String accountId) {
        Long v = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from goal_reservations where user_id = ? and goal_id = ?::uuid and account_id = ?::uuid",
                Long.class, a.id, goalId, accountId);
        return v == null ? 0 : v;
    }
}
