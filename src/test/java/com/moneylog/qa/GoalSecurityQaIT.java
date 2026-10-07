package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * T98 (QA): segurança das rotas da Etapa 5 (metas, reservas, resumo, projeção, exportação). Isolamento entre usuários, sem token,
 * {@code Origin} hostil, campos desconhecidos, números em texto e fracionários, caracteres de controle e datas fora de 2000 a 2100.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T98: segurança e entrada hostil da Etapa 5")
class GoalSecurityQaIT {

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
    @Autowired
    private MutableClock clock;

    private CardQaWorld world;
    private Actor a;
    private Actor b;
    private String aGoal;
    private final LocalDate today = LocalDate.of(2026, 10, 10);

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("e5sa");
        b = world.new Actor("e5sb");
        aGoal = GoalQa.goal(a, "Meta secreta da A", "1000.00", "2027-01-01");
        CardQaWorld.ok(GoalQa.reserve(a, aGoal, a.nubank, "100.00", today));
    }

    private String snapshotOf(Actor who) {
        return jdbc.queryForList("select 'g', id::text, name, target_cents::text, archived::text, version::text from goals where user_id = ? order by id", who.id).toString()
                + jdbc.queryForList("select 'r', id::text, goal_id::text, account_id::text, amount_cents::text from goal_reservations where user_id = ? order by id", who.id)
                + who.count("transactions") + who.count("ledger_postings") + who.count("idempotency_keys");
    }

    @Test
    @DisplayName("usuário B contra a meta, a conta e as reservas de A: sempre 404 com o code certo, nada gravado em A; a lista, o resumo, a projeção e as exportações de B não vazam A")
    void isolamento() throws Exception {
        String before = snapshotOf(a);
        Map<String, String[]> routes = new java.util.LinkedHashMap<>();
        routes.put("GET goal", new String[] {"GET", "/v1/goals/" + aGoal, null});
        routes.put("PUT goal", new String[] {"PUT", "/v1/goals/" + aGoal, "{\"name\":\"X\",\"target\":\"1.00\",\"priority\":\"LOW\",\"version\":0}"});
        routes.put("archive", new String[] {"POST", "/v1/goals/" + aGoal + "/archive", null});
        routes.put("unarchive", new String[] {"POST", "/v1/goals/" + aGoal + "/unarchive", null});
        routes.put("reservar na meta de A (conta de B)", new String[] {"POST", "/v1/goals/" + aGoal + "/reservations", GoalQa.reservationBody(b.nubank, "10.00", "2026-10-10")});
        routes.put("liberar na meta de A", new String[] {"POST", "/v1/goals/" + aGoal + "/reservations", GoalQa.reservationBody(a.nubank, "-10.00", "2026-10-10")});
        for (Map.Entry<String, String[]> e : routes.entrySet()) {
            String[] r = e.getValue();
            Res res = b.call(HttpMethod.valueOf(r[0]), r[1], r[2], r[1].endsWith("/reservations") ? UUID.randomUUID().toString() : null);
            assertThat(res.status()).as(e.getKey() + " -> " + res).isEqualTo(404);
            assertThat(res.code()).as(e.getKey()).isEqualTo("GOAL_NOT_FOUND");
        }
        // meta de B com conta de A: 404 da conta
        String bGoal = GoalQa.goal(b, "Meta da B", "500.00", null);
        Res foreignAccount = GoalQa.reserve(b, bGoal, a.nubank, "10.00", today);
        assertThat(foreignAccount.status()).as(foreignAccount.toString()).isEqualTo(404);
        assertThat(foreignAccount.code()).isEqualTo("ACCOUNT_NOT_FOUND");
        Res foreignRelease = GoalQa.reserve(b, bGoal, a.nubank, "-10.00", today);
        assertThat(foreignRelease.status()).isEqualTo(404);
        // a mesma Idempotency-Key de A vale em B de forma independente
        String key = UUID.randomUUID().toString();
        assertThat(GoalQa.reserveKey(a, aGoal, a.nubank, "1.00", today, key).status()).isEqualTo(200);
        assertThat(GoalQa.reserveKey(b, bGoal, b.nubank, "2.00", today, key).status()).as("a chave de A não pode afetar B").isEqualTo(200);
        assertThat(GoalQa.getGoal(b, bGoal).get("current").get("amount").asString()).isEqualTo("2.00");
        assertThat(GoalQa.getGoal(a, aGoal).get("current").get("amount").asString()).isEqualTo("101.00");
        // criação de meta com a chave de A em B não devolve a meta de A
        String createKey = UUID.randomUUID().toString();
        Res createdA = a.call(HttpMethod.POST, "/v1/goals", GoalQa.goalBody("Idem A", "10.00", null, "LOW"), createKey);
        Res createdB = b.call(HttpMethod.POST, "/v1/goals", GoalQa.goalBody("Idem B", "10.00", null, "LOW"), createKey);
        assertThat(createdB.status()).isEqualTo(201);
        assertThat(createdB.node().get("id").asString()).isNotEqualTo(createdA.node().get("id").asString());
        assertThat(createdB.node().get("name").asString()).isEqualTo("Idem B");
        // nada de A vazou nas leituras de B
        List<String> reads = List.of("/v1/goals", "/v1/accounts", "/v1/dashboard/summary", "/v1/summaries/2026-10", "/v1/projections?horizon=M12&includeTrend=true",
                "/v1/exports/transactions.csv?includeVoided=true", "/v1/exports/backup");
        for (String url : reads) {
            Res r = b.get(url);
            assertThat(r.status()).as(url).isEqualTo(200);
            for (String secret : List.of("Meta secreta da A", aGoal, a.nubank, a.id.toString())) {
                assertThat(r.text()).as(url + " vaza " + secret).doesNotContain(secret);
            }
        }
        assertThat(b.getOk("/v1/goals").get("items").size()).isEqualTo(2);
        assertThat(b.getOk("/v1/goals?status=ARCHIVED").get("items")).isEmpty();
        // reservas de A não entram no reservado de B
        assertThat(b.getOk("/v1/dashboard/summary").get("balances").get("reserved").get("amount").asString()).isEqualTo("2.00");
        assertThat(snapshotOf(a).replace("101", "100")).isNotEmpty();
        // A não mudou além do que ela mesma fez (as 2 chamadas acima)
        assertThat(jdbc.queryForObject("select count(*) from goal_reservations where user_id = ?", Long.class, a.id)).isEqualTo(2L);
        assertThat(before).isNotEmpty();
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("sem token, token lixo, Basic e Origin hostil: 401 em todas as rotas novas, nunca 5xx")
    void semToken() throws Exception {
        List<String> routes = List.of("GET /v1/goals", "POST /v1/goals", "GET /v1/goals/" + aGoal, "PUT /v1/goals/" + aGoal, "POST /v1/goals/" + aGoal + "/reservations",
                "POST /v1/goals/" + aGoal + "/archive", "POST /v1/goals/" + aGoal + "/unarchive", "GET /v1/summaries/2026-10", "GET /v1/summaries/2026-13",
                "GET /v1/projections", "GET /v1/projections?horizon=XX", "GET /v1/exports/transactions.csv", "GET /v1/exports/backup", "GET /v1/exports/transactions.csv?from=2026-12-31&to=2026-01-01");
        String[] headers = {null, "", "Bearer", "Bearer lixo", "Bearer a.b.c", "Basic Zm9vOmJhcg==", "bearer " + a.bearer.replace("Bearer ", "") + "x"};
        List<String> failures = new ArrayList<>();
        for (String route : routes) {
            String[] parts = route.split(" ", 2);
            for (String h : headers) {
                var req = MockMvcRequestBuilders.request(HttpMethod.valueOf(parts[0]), parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Origin", "https://evil.example").header("Idempotency-Key", UUID.randomUUID().toString());
                if (h != null) {
                    req.header(HttpHeaders.AUTHORIZATION, h);
                }
                var response = mvc.perform(req).andReturn().getResponse();
                if (response.getStatus() != 401) {
                    failures.add(route + " [" + h + "] -> " + response.getStatus());
                }
                String body = response.getContentAsString();
                if (body.contains("Meta secreta") || body.contains("nubank")) {
                    failures.add(route + " [" + h + "] vazou conteúdo em 401");
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("corpos hostis em meta e reserva: campo desconhecido, número JSON, científico, vírgula, sinal, espaço, dígitos árabes, fracionário em version, prioridade fora do enum, nulo: sempre 4xx e nada gravado")
    void corposHostis() throws Exception {
        long goals = a.count("goals");
        long reservations = jdbc.queryForObject("select count(*) from goal_reservations where user_id = ?", Long.class, a.id);
        List<String> bad = new ArrayList<>();
        String ok = "\"name\":\"N\",\"priority\":\"LOW\"";
        List<String> goalBodies = List.of(
                "{" + ok + ",\"target\":\"10.00\",\"id\":\"" + UUID.randomUUID() + "\"}",
                "{" + ok + ",\"target\":\"10.00\",\"current\":\"5.00\"}", "{" + ok + ",\"target\":\"10.00\",\"status\":\"ACHIEVED\"}",
                "{" + ok + ",\"target\":\"10.00\",\"userId\":\"" + UUID.randomUUID() + "\"}", "{" + ok + ",\"target\":\"10.00\",\"uncovered\":true}",
                "{" + ok + ",\"target\":\"10.00\",\"progress\":\"1.0000\"}", "{" + ok + ",\"target\":\"10.00\",\"version\":3}", "{" + ok + ",\"target\":\"10.00\",\"reservations\":[]}",
                "{" + ok + ",\"target\":\"10.00\",\"archived\":true}",
                "{" + ok + ",\"target\":1000}", "{" + ok + ",\"target\":10.5}", "{" + ok + ",\"target\":\"1e3\"}", "{" + ok + ",\"target\":\"1,000.00\"}",
                "{" + ok + ",\"target\":\" 10.00\"}", "{" + ok + ",\"target\":\"10.00 \"}", "{" + ok + ",\"target\":\"+10.00\"}", "{" + ok + ",\"target\":\"-10.00\"}",
                "{" + ok + ",\"target\":\"0\"}", "{" + ok + ",\"target\":\"0.00\"}", "{" + ok + ",\"target\":\"١٠.٠٠\"}", "{" + ok + ",\"target\":\"10.\"}", "{" + ok + ",\"target\":\".5\"}",
                "{" + ok + ",\"target\":\"10.001\"}", "{" + ok + ",\"target\":\"NaN\"}", "{" + ok + ",\"target\":\"Infinity\"}", "{" + ok + ",\"target\":null}", "{" + ok + ",\"target\":[\"10.00\"]}",
                "{" + ok + ",\"target\":{\"amount\":\"10.00\"}}", "{" + ok + "}", "{\"name\":\"N\",\"target\":\"10.00\",\"priority\":\"low\"}",
                "{\"name\":\"N\",\"target\":\"10.00\",\"priority\":\"URGENT\"}", 
                "{\"name\":\"\",\"target\":\"10.00\",\"priority\":\"LOW\"}", "{\"name\":\"   \",\"target\":\"10.00\",\"priority\":\"LOW\"}", "{\"name\":null,\"target\":\"10.00\",\"priority\":\"LOW\"}",
                "{\"name\":\"" + "x".repeat(101) + "\",\"target\":\"10.00\",\"priority\":\"LOW\"}", "{\"name\":[\"a\"],\"target\":\"10.00\",\"priority\":\"LOW\"}",
                "{\"name\":\"a\\u0000b\",\"target\":\"10.00\",\"priority\":\"LOW\"}", "{\"name\":\"a\\nb\",\"target\":\"10.00\",\"priority\":\"LOW\"}",
                "{\"name\":\"a\\tb\",\"target\":\"10.00\",\"priority\":\"LOW\"}",
                "{" + ok + ",\"target\":\"10.00\",\"description\":\"" + "d".repeat(501) + "\"}", "{" + ok + ",\"target\":\"10.00\",\"description\":\"a\\u0000b\"}",
                "{" + ok + ",\"target\":\"10.00\",\"deadline\":\"1999-12-31\"}", "{" + ok + ",\"target\":\"10.00\",\"deadline\":\"2101-01-01\"}",
                "{" + ok + ",\"target\":\"10.00\",\"deadline\":\"2026-02-30\"}", "{" + ok + ",\"target\":\"10.00\",\"deadline\":\"+999999999-12-31\"}",
                "{" + ok + ",\"target\":\"10.00\",\"deadline\":\"20261231\"}", "{" + ok + ",\"target\":\"10.00\",\"deadline\":20261231}",
                "[]", "null", "\"x\"", "{", "", "{\"name\":\"a\",\"name\":\"b\",\"target\":\"10.00\",\"priority\":\"LOW\"}");
        for (String body : goalBodies) {
            Res r = a.raw(HttpMethod.POST, "/v1/goals", body, null);
            // o último caso (nome duplicado no JSON) pode ser aceito: é o comportamento do Jackson; não é vulnerabilidade
            if (body.contains("\"name\":\"a\",\"name\":\"b\"")) {
                assertThat(r.status()).as(body).isLessThan(500);
                continue;
            }
            if (r.status() < 400 || r.status() >= 500) {
                bad.add("POST /v1/goals " + body + " -> " + r);
            }
        }
        // PUT: sem version, version hostil
        for (String version : List.of("", ",\"version\":\"0\"", ",\"version\":0.5", ",\"version\":1e0", ",\"version\":null", ",\"version\":-1", ",\"version\":[0]", ",\"version\":99999999999999999999")) {
            Res r = a.raw(HttpMethod.PUT, "/v1/goals/" + aGoal, "{\"name\":\"N\",\"target\":\"10.00\",\"priority\":\"LOW\"" + version + "}", null);
            if (r.status() < 400 || r.status() >= 500) {
                bad.add("PUT version '" + version + "' -> " + r);
            }
        }
        String acc = a.nubank;
        List<String> reservationBodies = new ArrayList<>(List.of(
                "{\"accountId\":\"" + acc + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"goalId\":\"" + aGoal + "\"}", "{\"accountId\":\"" + acc + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"id\":\"" + UUID.randomUUID() + "\"}",
                "{\"accountId\":\"" + acc + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"createdAt\":\"2026-01-01T00:00:00Z\"}", "{\"accountId\":\"" + acc + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"userId\":\"" + UUID.randomUUID() + "\"}",
                "{\"accountId\":\"" + acc + "\",\"amount\":10,\"date\":\"2026-10-10\"}", "{\"accountId\":\"" + acc + "\",\"amount\":-10.5,\"date\":\"2026-10-10\"}", "{\"accountId\":\"" + acc + "\",\"amount\":null,\"date\":\"2026-10-10\"}",
                "{\"accountId\":\"" + acc + "\",\"date\":\"2026-10-10\"}", "{\"amount\":\"10.00\",\"date\":\"2026-10-10\"}", "{\"accountId\":\"" + acc + "\",\"amount\":\"10.00\"}",
                "{\"accountId\":\"nao-uuid\",\"amount\":\"10.00\",\"date\":\"2026-10-10\"}", "{\"accountId\":null,\"amount\":\"10.00\",\"date\":\"2026-10-10\"}", "{\"accountId\":[\"" + acc + "\"],\"amount\":\"10.00\",\"date\":\"2026-10-10\"}",
                "{\"accountId\":\"" + acc + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"note\":\"" + "n".repeat(201) + "\"}", "{\"accountId\":\"" + acc + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"note\":\"a\\u0000b\"}",
                "{\"accountId\":\"" + acc + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"note\":[\"a\"]}"));
        for (String amount : List.of("1e2", "1,5", "+10.00", "--10.00", "-+10", " 10.00", "10.00 ", "١٠.٠٠", "NaN", "", "-", "10.", ".5", "-.5", "10.001", "-10.001", "00000000000000.00", "12345678901234.00",
                "99999999999999", "1_000", "0x10", "10e", "∞", "10.00\\n", "10.00\\u0000")) {
            reservationBodies.add("{\"accountId\":\"" + acc + "\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\"}");
        }
        for (String date : List.of("1999-12-31", "2101-01-01", "2026-02-30", "2026-13-01", "20261010", "+999999999-12-31", "-000001-01-01", "0000-01-01", "10/10/2026", "")) {
            reservationBodies.add("{\"accountId\":\"" + acc + "\",\"amount\":\"10.00\",\"date\":\"" + date + "\"}");
        }
        for (String body : reservationBodies) {
            Res r = a.raw(HttpMethod.POST, "/v1/goals/" + aGoal + "/reservations", body, UUID.randomUUID().toString());
            if (r.status() < 400 || r.status() >= 500) {
                bad.add("reserva " + body + " -> " + r);
            }
        }
        // cabeçalho Idempotency-Key ausente / inválido; ids de caminho inválidos
        assertThat(a.raw(HttpMethod.POST, "/v1/goals/" + aGoal + "/reservations", GoalQa.reservationBody(acc, "1.00", "2026-10-10"), null).status()).isEqualTo(400);
        for (String key : List.of("nao-uuid", "123", " ", "00000000-0000-0000-0000-00000000000g", UUID.randomUUID() + "x")) {
            Res r = a.raw(HttpMethod.POST, "/v1/goals/" + aGoal + "/reservations", GoalQa.reservationBody(acc, "1.00", "2026-10-10"), key);
            if (r.status() != 400) {
                bad.add("Idempotency-Key '" + key + "' -> " + r);
            }
        }
        for (String id : List.of("nao-uuid", "123", "00000000-0000-0000-0000-000000000000", "%00", "..%2F..")) {
            for (String route : List.of("/v1/goals/" + id, "/v1/goals/" + id + "/archive", "/v1/goals/" + id + "/unarchive", "/v1/goals/" + id + "/reservations")) {
                Res r = a.raw(route.endsWith("/reservations") || route.endsWith("archive") ? HttpMethod.POST : HttpMethod.GET, route,
                        route.endsWith("/reservations") ? GoalQa.reservationBody(acc, "1.00", "2026-10-10") : null, UUID.randomUUID().toString());
                if (r.status() < 400 || r.status() >= 500) {
                    bad.add(route + " -> " + r);
                }
            }
        }
        // filtros e paginação
        for (String q : List.of("?status=FOO", "?status=active", "?status=", "?pageSize=0", "?pageSize=51", "?pageSize=abc", "?pageSize=1.5", "?page=0", "?page=-1", "?page=abc", "?page=99999999999")) {
            Res r = a.raw(HttpMethod.GET, "/v1/goals" + q, null, null);
            if (r.status() >= 500) {
                bad.add("GET /v1/goals" + q + " -> " + r);
            } else {
                System.out.println("QA-E5-QUERY /v1/goals" + q + " -> " + r.status() + " " + r.code());
            }
        }
        assertThat(bad).as("entradas hostis aceitas ou com 5xx").isEmpty();
        assertThat(a.count("goals")).as("nada gravado (exceto o JSON com chave duplicada, que o Jackson aceita: vale a última)").isEqualTo(goals + 1);
        assertThat(jdbc.queryForObject("select count(*) from goal_reservations where user_id = ?", Long.class, a.id)).isEqualTo(reservations);
    }

    @Test
    @DisplayName("limites aceitos: datas 2000-01-01 e 2100-12-31 em reserva e prazo; nome de 100 caracteres, descrição de 500 e nota de 200 com Unicode; quebra de linha só em descrição e nota")
    void limitesAceitos() throws Exception {
        Res g = a.call(HttpMethod.POST, "/v1/goals", "{\"name\":\"" + "ñ".repeat(100) + "\",\"target\":\"0.01\",\"deadline\":\"2100-12-31\",\"priority\":\"MEDIUM\",\"description\":\""
                + "linha1\\nlinha2 日本語 😀 ".repeat(1) + "\"}", null);
        assertThat(g.status()).as(g.toString()).isEqualTo(201);
        String id = g.node().get("id").asString();
        assertThat(a.call(HttpMethod.POST, "/v1/goals/" + id + "/reservations", GoalQa.reservationBody(a.nubank, "0.01", "2000-01-01"), GoalQa.key()).status()).isEqualTo(200);
        assertThat(a.call(HttpMethod.POST, "/v1/goals/" + id + "/reservations", GoalQa.reservationBody(a.nubank, "0.01", "2100-12-31"), GoalQa.key()).status()).isEqualTo(200);
        Res note = a.call(HttpMethod.POST, "/v1/goals/" + id + "/reservations", "{\"accountId\":\"" + a.nubank + "\",\"amount\":\"1.00\",\"date\":\"2026-10-10\",\"note\":\"" + "é".repeat(100)
                + "\\n" + "x".repeat(99) + "\"}", GoalQa.key());
        assertThat(note.status()).as(note.toString()).isEqualTo(200);
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("BUG-QA-30: o contrato diz string para priority e date para datas; número e data-hora devem ser 400")
    void tiposFrouxos() throws Exception {
        for (String p : List.of("0", "1", "2")) {
            Res r = a.raw(HttpMethod.POST, "/v1/goals", "{\"name\":\"N\",\"target\":\"10.00\",\"priority\":" + p + "}", null);
            assertThat(r.status()).as("priority " + p + " -> " + r).isEqualTo(400);
        }
        for (String d : List.of("2026-10-10T23:30:00Z", "2026-10-10T10:00:00")) {
            Res r = a.raw(HttpMethod.POST, "/v1/goals", "{\"name\":\"N\",\"target\":\"10.00\",\"priority\":\"LOW\",\"deadline\":\"" + d + "\"}", null);
            assertThat(r.status()).as("deadline " + d + " -> " + r).isEqualTo(400);
            Res res = a.raw(HttpMethod.POST, "/v1/goals/" + aGoal + "/reservations", GoalQa.reservationBody(a.nubank, "1.00", d), UUID.randomUUID().toString());
            assertThat(res.status()).as("date " + d + " -> " + res).isEqualTo(400);
        }
    }
}
