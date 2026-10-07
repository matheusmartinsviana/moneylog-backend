package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClockConfig;

/** T43 (QA): entradas extremas nunca devem produzir 500 (o contrato só admite 500 para falha interna real). */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T43: robustez de entrada")
class RobustnessQaIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager tm;

    private QaWorld world;
    private QaWorld.Actor a;

    @BeforeEach
    void cenario() throws Exception {
        world = new QaWorld(mvc, om, jdbc, auth, tm);
        a = world.new Actor("rob").withExpense();
    }

    private static String acct(String name, String institution, String date) {
        return "{\"name\":\"" + name + "\",\"institution\":" + (institution == null ? "null" : "\"" + institution + "\"")
                + ",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"openingDate\":\"" + date + "\"}";
    }

    private String exp(String desc, String notes, String date) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"1.00\",\"date\":\"" + date + "\",\"description\":\"" + desc + "\",\"notes\":" + (notes == null ? "null" : "\"" + notes + "\"")
                + ",\"accountId\":\"" + a.nubank + "\",\"categoryId\":\"" + a.food + "\",\"method\":\"PIX\"}";
    }

    private int status(HttpMethod m, String url, String body) throws Exception {
        MockHttpServletRequestBuilder r = a.req(m, url, body);
        if (m == HttpMethod.POST && url.equals("/v1/transactions")) {
            r.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        MvcResult res = mvc.perform(r).andReturn();
        int s = res.getResponse().getStatus();
        System.out.println("QA-ROB " + s + " " + m + " " + url + " " + (body == null ? "" : body.substring(0, Math.min(90, body.length()))));
        return s;
    }

    private record Probe(HttpMethod m, String url, String body) {
    }

    private void assertNo500(List<Probe> probes) throws Exception {
        List<String> bad = new ArrayList<>();
        for (Probe p : probes) {
            int s = status(p.m(), p.url(), p.body());
            if (s >= 500) {
                bad.add(s + " " + p.m() + " " + p.url() + " " + (p.body() == null ? "" : p.body().substring(0, Math.min(100, p.body().length()))));
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    @DisplayName("BUG-QA-02: NUL em nome, instituição, descrição, notas e motivo deve ser 400, não 500")
    void nulNaoPodeDar500() throws Exception {
        assertNo500(List.of(
                new Probe(HttpMethod.POST, "/v1/accounts", acct("a\\u0000b", null, "2026-10-01")),
                new Probe(HttpMethod.POST, "/v1/accounts", acct("ok", "i\\u0000", "2026-10-01")),
                new Probe(HttpMethod.POST, "/v1/categories", "{\"name\":\"a\\u0000b\",\"kind\":\"EXPENSE\"}"),
                new Probe(HttpMethod.POST, "/v1/transactions", exp("a\\u0000b", null, "2026-10-10")),
                new Probe(HttpMethod.POST, "/v1/transactions", exp("ok", "n\\u0000", "2026-10-10")),
                new Probe(HttpMethod.POST, "/v1/transactions/" + a.expenseTx + "/void", "{\"reason\":\"a\\u0000b\"}"),
                new Probe(HttpMethod.PUT, "/v1/transactions/" + a.expenseTx, exp("a\\u0000b", null, "2026-10-10").replace("}", ",\"version\":0}")),
                new Probe(HttpMethod.PUT, "/v1/categories/" + a.food, "{\"name\":\"a\\u0000b\",\"version\":0}"),
                new Probe(HttpMethod.PUT, "/v1/accounts/" + a.xp, acct("a\\u0000b", null, "2026-10-01").replace("}", ",\"version\":0}"))));
    }

    @Test
    @DisplayName("BUG-QA-03: datas 0000-01-01 e +999999999-12-31 devem ser 400/422, não 500")
    void dataForaDoIntervaloNaoPodeDar500() throws Exception {
        assertNo500(List.of(
                new Probe(HttpMethod.POST, "/v1/accounts", acct("ok", null, "0000-01-01")),
                new Probe(HttpMethod.POST, "/v1/accounts", acct("ok", null, "-0001-01-01")),
                new Probe(HttpMethod.POST, "/v1/accounts", acct("ok", null, "+999999999-12-31")),
                new Probe(HttpMethod.POST, "/v1/transactions", exp("ok", null, "0000-01-01")),
                new Probe(HttpMethod.POST, "/v1/transactions", exp("ok", null, "+999999999-12-31"))));
    }

    @Test
    @DisplayName("BUG-QA-04: page=2147483647 deve ser 400 ou página vazia, não 500")
    void paginaGiganteNaoPodeDar500() throws Exception {
        assertNo500(List.of(
                new Probe(HttpMethod.GET, "/v1/transactions?page=2147483647&pageSize=50", null),
                new Probe(HttpMethod.GET, "/v1/transactions?page=2147483647", null),
                new Probe(HttpMethod.GET, "/v1/accounts?page=2147483647&pageSize=50", null)));
    }

    @Test
    @DisplayName("NUL, datas extremas e limites nunca viram 500")
    void nuncaDa500() throws Exception {
        List<String> bad = new ArrayList<>();
        List<Probe> probes = List.of(
                new Probe(HttpMethod.POST, "/v1/accounts", acct("ok", null, "0001-01-01")),
                new Probe(HttpMethod.POST, "/v1/accounts", acct("ok", null, "9999-12-31")),
                new Probe(HttpMethod.POST, "/v1/transactions", exp("ok", null, "0001-01-01")),
                new Probe(HttpMethod.POST, "/v1/transactions", exp("ok", null, "9999-12-31")),
                new Probe(HttpMethod.POST, "/v1/transactions", exp("ok", null, "2026-02-30")),
                new Probe(HttpMethod.POST, "/v1/transactions", exp("😀 emoji", "çãõ", "2026-10-10")),
                new Probe(HttpMethod.POST, "/v1/transactions/" + a.expenseTx + "/void", "{\"reason\":\"" + "r".repeat(1000) + "\"}"),
                new Probe(HttpMethod.GET, "/v1/transactions?from=0000-01-01&to=9999-12-31", null),
                new Probe(HttpMethod.GET, "/v1/transactions?from=%2B999999999-01-01", null),
                new Probe(HttpMethod.GET, "/v1/transactions?q=a%00b", null),
                new Probe(HttpMethod.GET, "/v1/transactions?q=%25%5F%5C", null),
                new Probe(HttpMethod.GET, "/v1/entries?competence=0000-01", null),
                new Probe(HttpMethod.GET, "/v1/entries?competence=9999-12", null),
                new Probe(HttpMethod.GET, "/v1/dashboard/summary?asOf=0000-01-01", null),
                new Probe(HttpMethod.GET, "/v1/dashboard/summary?asOf=9999-12-31", null),
                new Probe(HttpMethod.GET, "/v1/dashboard/summary?asOf=%2B999999999-12-31", null),
                new Probe(HttpMethod.GET, "/v1/dashboard/upcoming?from=0000-01-01&to=9999-12-31", null),
                new Probe(HttpMethod.GET, "/v1/dashboard/upcoming?from=-999999999-01-01&to=%2B999999999-12-31", null));
        for (Probe p : probes) {
            int s = status(p.m(), p.url(), p.body());
            if (s >= 500) {
                bad.add(s + " " + p.m() + " " + p.url() + " " + (p.body() == null ? "" : p.body().substring(0, Math.min(100, p.body().length()))));
            }
        }
        System.out.println("QA-ROB-BAD " + bad);
        assertThat(bad).isEmpty();
    }
}
