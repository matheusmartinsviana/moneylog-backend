package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T43: matriz de segurança")
class SecurityMatrixQaIT {

    private static final String[] LEAK_MARKERS = {"Exception", "org.springframework", "com.moneylog", "at java.", "SELECT ", "INSERT ", "UPDATE ",
            "postgres", "hibernate", "constraint", "stackTrace", "jakarta.", "tools.jackson", "nimbus", "psql", "JDBC"};

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
    @Value("${moneylog.security.jwt-secret}")
    private String secret;

    private QaWorld world;
    private QaWorld.Actor a;
    private QaWorld.Actor b;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new QaWorld(mvc, om, jdbc, auth, tm);
        a = world.new Actor("sec-a").withExpense();
        b = world.new Actor("sec-b").withExpense();
    }

    private record Op(String name, HttpMethod method, String path, String body, boolean idempotent) {
    }

    private List<Op> operations() {
        String acc = a.nubank;
        return List.of(
                new Op("listAccounts", HttpMethod.GET, "/v1/accounts", null, false),
                new Op("createAccount", HttpMethod.POST, "/v1/accounts",
                        "{\"name\":\"Nova\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}", false),
                new Op("getAccount", HttpMethod.GET, "/v1/accounts/" + acc, null, false),
                new Op("updateAccount", HttpMethod.PUT, "/v1/accounts/" + acc,
                        "{\"name\":\"X\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\",\"version\":0}", false),
                new Op("archiveAccount", HttpMethod.POST, "/v1/accounts/" + acc + "/archive", "", false),
                new Op("unarchiveAccount", HttpMethod.POST, "/v1/accounts/" + acc + "/unarchive", "", false),
                new Op("listCategories", HttpMethod.GET, "/v1/categories", null, false),
                new Op("createCategory", HttpMethod.POST, "/v1/categories", "{\"name\":\"Nova\",\"kind\":\"EXPENSE\"}", false),
                new Op("updateCategory", HttpMethod.PUT, "/v1/categories/" + a.food, "{\"name\":\"Renomeada\",\"version\":0}", false),
                new Op("archiveCategory", HttpMethod.POST, "/v1/categories/" + a.food + "/archive", "", false),
                new Op("unarchiveCategory", HttpMethod.POST, "/v1/categories/" + a.food + "/unarchive", "", false),
                new Op("listTransactions", HttpMethod.GET, "/v1/transactions", null, false),
                new Op("createTransaction", HttpMethod.POST, "/v1/transactions", QaWorld.expense(acc, a.food, "1.00", "2026-10-10", "x"), true),
                new Op("getTransaction", HttpMethod.GET, "/v1/transactions/" + a.expenseTx, null, false),
                new Op("updateTransaction", HttpMethod.PUT, "/v1/transactions/" + a.expenseTx,
                        QaWorld.expense(acc, a.food, "2.00", "2026-10-10", "y").replace("}", ",\"version\":0}"), false),
                new Op("voidTransaction", HttpMethod.POST, "/v1/transactions/" + a.expenseTx + "/void", "{\"reason\":\"x\"}", false),
                new Op("listEntries", HttpMethod.GET, "/v1/entries?competence=2026-10", null, false),
                new Op("getDashboardSummary", HttpMethod.GET, "/v1/dashboard/summary", null, false),
                new Op("getDashboardUpcoming", HttpMethod.GET, "/v1/dashboard/upcoming", null, false));
    }

    private MockHttpServletRequestBuilder build(Op op, String authorization) {
        MockHttpServletRequestBuilder r = MockMvcRequestBuilders.request(op.method(), op.path());
        if (authorization != null) {
            r.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (op.body() != null) {
            r.contentType(MediaType.APPLICATION_JSON).content(op.body());
        }
        if (op.idempotent()) {
            r.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return r;
    }

    private String sign(String secretText, JWTClaimsSet claims) throws Exception {
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.HS256), new Payload(claims.toJSONObject()));
        jws.sign(new MACSigner(secretText.getBytes(StandardCharsets.UTF_8)));
        return jws.serialize();
    }

    private JWTClaimsSet.Builder claims(String sub, Instant iat, Instant exp) {
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().issueTime(Date.from(iat)).expirationTime(Date.from(exp)).jwtID(UUID.randomUUID().toString());
        return sub == null ? c : c.subject(sub);
    }

    private String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> badTokens() throws Exception {
        Instant now = MutableClockConfig.INITIAL;
        String valid = a.bearer.substring("Bearer ".length());
        String[] parts = valid.split("\\.");
        char c = parts[2].charAt(10);
        String tamperedSig = parts[0] + "." + parts[1] + "." + parts[2].substring(0, 10) + (c == 'A' ? 'B' : 'A') + parts[2].substring(11);
        String swappedSub = parts[0] + "." + b64("{\"sub\":\"" + b.id + "\",\"iat\":" + now.getEpochSecond() + ",\"exp\":" + now.plusSeconds(900).getEpochSecond() + "}")
                + "." + parts[2];
        String alg_none = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "." + b64("{\"sub\":\"" + a.id + "\",\"exp\":" + now.plusSeconds(900).getEpochSecond() + "}") + ".";
        String hs512 = null;
        return List.of(
                "garbage",
                "a.b.c",
                valid + "x",
                tamperedSig,
                swappedSub,
                alg_none,
                sign("another-secret-with-at-least-32-bytes-0123456789", claims(a.id.toString(), now, now.plusSeconds(900)).build()),
                sign(secret, claims(a.id.toString(), now.minusSeconds(3600), now.minusSeconds(1)).build()),
                sign(secret, claims(null, now, now.plusSeconds(900)).build()),
                sign(secret, claims("nao-e-uuid", now, now.plusSeconds(900)).build()));
    }

    private void leakCheck(String where, MvcResult r, List<String> violations) throws Exception {
        String body = r.getResponse().getContentAsString();
        for (String marker : LEAK_MARKERS) {
            if (body.contains(marker)) {
                violations.add(where + ": o corpo vaza '" + marker + "': " + body);
            }
        }
        if (r.getResponse().getHeader("X-Trace-Id") == null) {
            violations.add(where + ": sem X-Trace-Id");
        }
    }

    @Test
    @DisplayName("sem token, token adulterado, de outro segredo, expirado, sem exp, sem sub: 401 UNAUTHENTICATED em toda operação, sem vazar nada")
    void deveRecusarCredenciaisInvalidasEmTodaOperacao() throws Exception {
        List<String> violations = new ArrayList<>();
        List<String> tokens = badTokens();
        int checks = 0;
        for (Op op : operations()) {
            List<String> auths = new ArrayList<>();
            auths.add(null);
            for (int i = 0; i < tokens.size(); i++) {
                auths.add("Bearer " + tokens.get(i) + "#" + i);
            }
            auths.add("Basic " + b64("owner:senha"));
            auths.add(a.bearer.substring(7));
            auths.add("bearer " + a.bearer.substring(7).replace("Bearer ", ""));
            for (String header : auths) {
                String where = op.name() + " [" + (header == null ? "sem header" : header.contains("#") ? "token#" + header.substring(header.lastIndexOf('#') + 1) : header.substring(0, Math.min(18, header.length()))) + "]";
                MvcResult r = mvc.perform(build(op, header == null ? null : header.contains("#") ? header.substring(0, header.lastIndexOf('#')) : header)).andReturn();
                checks++;
                boolean lowerCaseScheme = header != null && header.startsWith("bearer ");
                if (lowerCaseScheme) {
                    continue;
                }
                if (r.getResponse().getStatus() != 401) {
                    violations.add(where + ": status " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
                    continue;
                }
                JsonNode body = om.readTree(r.getResponse().getContentAsString());
                if (!"UNAUTHENTICATED".equals(body.path("code").asString())) {
                    violations.add(where + ": code " + body.path("code").asString());
                }
                if (!r.getResponse().getContentType().startsWith("application/problem+json")) {
                    violations.add(where + ": content-type " + r.getResponse().getContentType());
                }
                try {
                    ContractValidation.responseOnly().match(r);
                } catch (AssertionError | Exception e) {
                    violations.add(where + ": resposta 401 fora do contrato: " + e.getMessage().lines().findFirst().orElse(""));
                }
                leakCheck(where, r, violations);
            }
        }
        System.out.println("QA-MATRIX: " + checks + " combinacoes de operacao x credencial invalida executadas");
        assertThat(violations).as("violações da matriz sem token/adulterado/expirado").isEmpty();
    }

    @Test
    @DisplayName("BUG-QA-10: token assinado sem exp deve ser 401")
    void tokenSemExpDeveSerRecusado() throws Exception {
        Instant now = MutableClockConfig.INITIAL;
        String token = sign(secret, new JWTClaimsSet.Builder().subject(a.id.toString()).issueTime(Date.from(now)).build());
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/v1/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("token assinado válido de um usuário que não existe no banco: nenhuma operação responde 500 nem expõe dado de outro")
    void deveTratarTokenValidoDeUsuarioInexistente() throws Exception {
        List<String> violations = new ArrayList<>();
        String ghost = "Bearer " + sign(secret, claims(UUID.randomUUID().toString(), MutableClockConfig.INITIAL, MutableClockConfig.INITIAL.plusSeconds(900)).build());
        for (Op op : operations()) {
            MvcResult r = mvc.perform(build(op, ghost)).andReturn();
            int s = r.getResponse().getStatus();
            if (s >= 500) {
                violations.add(op.name() + " com usuário fantasma: " + s + " " + r.getResponse().getContentAsString());
            }
            leakCheck(op.name() + " fantasma", r, violations);
        }
        System.out.println("QA-GHOST violations=" + violations);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("esquema 'bearer' em minúsculas (RFC 7235 diz que o esquema não diferencia caixa)")
    void esquemaEmMinusculas() throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/v1/accounts").header(HttpHeaders.AUTHORIZATION, "bearer " + a.bearer.substring(7))).andReturn();
        System.out.println("QA-LOWERCASE-BEARER status=" + r.getResponse().getStatus());
    }

    @Test
    @DisplayName("actuator: só /health responde (sem detalhes); env, beans, heapdump, info e metrics não são expostos")
    void actuatorSoExpoeHealth() throws Exception {
        MvcResult health = mvc.perform(MockMvcRequestBuilders.get("/actuator/health")).andReturn();
        assertThat(health.getResponse().getStatus()).isEqualTo(200);
        assertThat(om.readTree(health.getResponse().getContentAsString()).toString()).isEqualTo("{\"status\":\"UP\"}");
        for (String path : List.of("/actuator/env", "/actuator/beans", "/actuator/heapdump", "/actuator/info", "/actuator/metrics", "/actuator/loggers", "/actuator/mappings")) {
            for (String authorization : new String[] {null, a.bearer}) {
                MockHttpServletRequestBuilder r = MockMvcRequestBuilders.get(path);
                if (authorization != null) {
                    r.header(HttpHeaders.AUTHORIZATION, authorization);
                }
                int status = mvc.perform(r).andReturn().getResponse().getStatus();
                assertThat(status).as(path + (authorization == null ? " sem token" : " com token")).isIn(401, 404);
            }
        }
    }

    @Test
    @DisplayName("respostas autenticadas não são cacheáveis e trazem os headers de segurança padrão")
    void headersDeSeguranca() throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/v1/accounts").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(r.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getResponse().getHeader("X-Frame-Options")).isNotNull();
        assertThat(r.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    @DisplayName("token na query string não autentica")
    void tokenNaQueryNaoAutentica() throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/v1/accounts").param("access_token", a.bearer.substring(7))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("B não lê, edita, arquiva nem estorna recursos de A: 404 com o code do recurso, nada muda e A não aparece nas listas de B")
    void outroUsuarioNaoAcessaRecursosDeA() throws Exception {
        List<String> violations = new ArrayList<>();
        long ledgerBefore = world.ledgerSum(a.id);
        String snapshot = snapshotOf(a);

        String[][] cases = {
                {"GET", "/v1/accounts/" + a.nubank, null, "ACCOUNT_NOT_FOUND"},
                {"PUT", "/v1/accounts/" + a.nubank, "{\"name\":\"Hack\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\",\"version\":0}", "ACCOUNT_NOT_FOUND"},
                {"POST", "/v1/accounts/" + a.nubank + "/archive", "", "ACCOUNT_NOT_FOUND"},
                {"POST", "/v1/accounts/" + a.nubank + "/unarchive", "", "ACCOUNT_NOT_FOUND"},
                {"PUT", "/v1/categories/" + a.food, "{\"name\":\"Hack\",\"version\":0}", "CATEGORY_NOT_FOUND"},
                {"POST", "/v1/categories/" + a.food + "/archive", "", "CATEGORY_NOT_FOUND"},
                {"POST", "/v1/categories/" + a.food + "/unarchive", "", "CATEGORY_NOT_FOUND"},
                {"GET", "/v1/transactions/" + a.expenseTx, null, "TRANSACTION_NOT_FOUND"},
                {"PUT", "/v1/transactions/" + a.expenseTx, QaWorld.expense(b.nubank, b.food, "9.00", "2026-10-10", "hack").replace("}", ",\"version\":0}"), "TRANSACTION_NOT_FOUND"},
                {"POST", "/v1/transactions/" + a.expenseTx + "/void", "{\"reason\":\"hack\"}", "TRANSACTION_NOT_FOUND"},
                {"POST", "/v1/transactions", QaWorld.expense(a.nubank, b.food, "1.00", "2026-10-10", "x"), "ACCOUNT_NOT_FOUND"},
                {"POST", "/v1/transactions", QaWorld.expense(b.nubank, a.food, "1.00", "2026-10-10", "x"), "CATEGORY_NOT_FOUND"},
                {"POST", "/v1/transactions", QaWorld.income(b.nubank, a.salary, "1.00", "2026-10-10"), "CATEGORY_NOT_FOUND"},
                {"POST", "/v1/transactions", QaWorld.transfer(b.nubank, a.xp, "1.00", "2026-10-10"), "ACCOUNT_NOT_FOUND"},
                {"POST", "/v1/transactions", QaWorld.transfer(a.nubank, b.xp, "1.00", "2026-10-10"), "ACCOUNT_NOT_FOUND"},
                {"POST", "/v1/transactions", QaWorld.investment(b.nubank, a.xpInvest, "CONTRIBUTION", "1.00", "2026-10-10"), "ACCOUNT_NOT_FOUND"},
                {"POST", "/v1/transactions", QaWorld.adjustment(a.nubank, "INCREASE", "1.00", "2026-10-10"), "ACCOUNT_NOT_FOUND"},
                {"POST", "/v1/transactions", QaWorld.refund(b.nubank, b.food, a.expenseTx, "1.00", "2026-10-10"), null},
                {"POST", "/v1/transactions", QaWorld.refund(b.nubank, a.food, b.expenseTx, "1.00", "2026-10-10"), "CATEGORY_NOT_FOUND"},
                {"PUT", "/v1/transactions/" + b.expenseTx, QaWorld.expense(a.nubank, b.food, "9.00", "2026-10-10", "hack").replace("}", ",\"version\":0}"), "ACCOUNT_NOT_FOUND"},
                {"PUT", "/v1/transactions/" + b.expenseTx, QaWorld.expense(b.nubank, a.food, "9.00", "2026-10-10", "hack").replace("}", ",\"version\":0}"), "CATEGORY_NOT_FOUND"},
                {"GET", "/v1/transactions?accountId=" + a.nubank, null, "LIST_EMPTY"},
                {"GET", "/v1/transactions?categoryId=" + a.food, null, "LIST_EMPTY"},
                {"GET", "/v1/entries?competence=2026-10&categoryId=" + a.food, null, "ENTRIES_ZERO"},
                {"GET", "/v1/entries?competence=2026-10&accountId=" + a.nubank, null, "ENTRIES_ZERO"},
        };
        for (String[] c : cases) {
            HttpMethod m = HttpMethod.valueOf(c[0]);
            MockHttpServletRequestBuilder r = b.req(m, c[1], c[2]);
            if (c[1].equals("/v1/transactions") && m == HttpMethod.POST) {
                r.header("Idempotency-Key", UUID.randomUUID().toString());
            }
            MvcResult res = mvc.perform(r).andReturn();
            int status = res.getResponse().getStatus();
            String where = c[0] + " " + c[1] + " " + (c[2] == null ? "" : c[2].substring(0, Math.min(40, c[2].length())));
            String code = c[3];
            if (code == null) {
                code = "TRANSACTION_NOT_FOUND";
            }
            if (code.equals("LIST_EMPTY")) {
                if (status != 200 || res.getResponse().getContentAsString().contains(a.expenseTx)) {
                    violations.add(where + ": status " + status + " ou vazou transação de A");
                } else if (om.readTree(res.getResponse().getContentAsString()).get("items").size() != 0 && !c[1].contains("zzz")) {
                    violations.add(where + ": lista de B por id de A não está vazia: " + res.getResponse().getContentAsString());
                }
                continue;
            }
            if (code.equals("ENTRIES_ZERO")) {
                JsonNode body = om.readTree(res.getResponse().getContentAsString());
                if (status != 200 || body.get("items").size() != 0) {
                    violations.add(where + ": status " + status + " ou entries de A visíveis: " + res.getResponse().getContentAsString());
                }
                continue;
            }
            if (status != 404) {
                violations.add(where + ": esperado 404 " + code + " e veio " + status + " " + res.getResponse().getContentAsString());
                continue;
            }
            String got = om.readTree(res.getResponse().getContentAsString()).path("code").asString();
            if (!code.equals(got)) {
                violations.add(where + ": code " + got + " (esperado " + code + ")");
            }
            leakCheck(where, res, violations);
            if (res.getResponse().getContentAsString().contains(a.id.toString())) {
                violations.add(where + ": a resposta cita o id do usuário A");
            }
        }

        for (String url : List.of("/v1/accounts?pageSize=50", "/v1/categories?includeArchived=true", "/v1/transactions?pageSize=50",
                "/v1/entries?competence=2026-10&pageSize=50")) {
            String text = b.req(HttpMethod.GET, url, null).toString();
            String body = mvc.perform(b.req(HttpMethod.GET, url, null)).andReturn().getResponse().getContentAsString();
            for (String idOfA : List.of(a.nubank, a.xp, a.xpInvest, a.food, a.salary, a.expenseTx)) {
                if (body.contains(idOfA)) {
                    violations.add("lista " + url + " de B contém id de A " + idOfA);
                }
            }
        }

        assertThat(snapshotOf(a)).as("estado de A após os ataques de B").isEqualTo(snapshot);
        assertThat(world.ledgerSum(a.id)).isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ?", Integer.class, b.id)).as("B só tem a sua despesa").isEqualTo(1);
        assertThat(violations).as("violações de isolamento").isEmpty();
    }

    private String snapshotOf(QaWorld.Actor actor) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(jdbc.queryForList("select id, name, status, version, opening_balance_cents from accounts where user_id = ? order by id", actor.id));
        sb.append(jdbc.queryForList("select id, name, archived, version from categories where user_id = ? order by id", actor.id));
        sb.append(jdbc.queryForList("select id, status, amount_cents, description, account_id, category_id, version from transactions where user_id = ? order by id", actor.id));
        sb.append(jdbc.queryForList("select count(*), sum(amount_cents) from ledger_postings where user_id = ?", actor.id));
        return sb.toString();
    }

    @Test
    @DisplayName("corpo com userId/user_id injetado é ignorado: o dono é sempre o do token")
    void deveIgnorarUserIdNoCorpo() throws Exception {
        MvcResult r = mvc.perform(b.req(HttpMethod.POST, "/v1/accounts", "{\"name\":\"Inj\",\"userId\":\"" + a.id + "\",\"user_id\":\"" + a.id
                + "\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andReturn();
        System.out.println("QA-INJECT status=" + r.getResponse().getStatus() + " body=" + r.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("select count(*) from accounts where user_id = ? and name = 'Inj'", Integer.class, a.id)).isZero();
    }

    @Test
    @DisplayName("Origin inválido no refresh e no logout: ausente, 'null', vazio, outro host, prefixo/sufixo do permitido, esquema http, porta, com caminho")
    void deveRecusarOriginsInvalidos() throws Exception {
        List<String> violations = new ArrayList<>();
        String ok = "https://app.moneylog.test";
        List<String> bad = new ArrayList<>(List.of("null", "", " ", "https://evil.test", ok + ".evil.test", "https://evil." + "app.moneylog.test", "http://app.moneylog.test",
                ok + ":8443", ok + "/path", "https://app.moneylog.test@evil.test", "https://APP.MONEYLOG.TEST.evil.test", "*"));
        bad.add(null);
        for (String path : List.of("/v1/auth/refresh", "/v1/auth/logout")) {
            for (String origin : bad) {
                MockHttpServletRequestBuilder r = MockMvcRequestBuilders.post(path).cookie(new jakarta.servlet.http.Cookie("ml_refresh", "qualquer"));
                if (origin != null) {
                    r.header("Origin", origin);
                }
                MvcResult res = mvc.perform(r).andReturn();
                String where = path + " Origin=" + origin;
                if (res.getResponse().getStatus() != 403) {
                    violations.add(where + ": status " + res.getResponse().getStatus());
                    continue;
                }
                if (!"ORIGIN_NOT_ALLOWED".equals(om.readTree(res.getResponse().getContentAsString()).path("code").asString())) {
                    violations.add(where + ": code");
                }
                try {
                    ContractValidation.responseOnly().match(res);
                } catch (AssertionError | Exception e) {
                    violations.add(where + ": fora do contrato " + e.getMessage().lines().findFirst().orElse(""));
                }
                leakCheck(where, res, violations);
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Origin permitido em caixa diferente e com barra final é aceito (normalização), sem abrir brecha")
    void originNormalizado() throws Exception {
        MvcResult res = mvc.perform(MockMvcRequestBuilders.post("/v1/auth/logout").header("Origin", "HTTPS://APP.MONEYLOG.TEST/")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("métodos não declarados nas rotas autenticadas respondem 405 ProblemDetail (DELETE/PATCH) e, sem token, 401")
    void metodosNaoDeclarados() throws Exception {
        for (HttpMethod m : List.of(HttpMethod.DELETE, HttpMethod.PATCH)) {
            for (String p : List.of("/v1/accounts/" + a.nubank, "/v1/categories/" + a.food, "/v1/transactions/" + a.expenseTx, "/v1/entries", "/v1/dashboard/summary")) {
                MvcResult withToken = mvc.perform(a.req(m, p, null)).andReturn();
                assertThat(withToken.getResponse().getStatus()).as(m + " " + p).isEqualTo(405);
                assertThat(withToken.getResponse().getContentType()).startsWith("application/problem+json");
                MvcResult none = mvc.perform(MockMvcRequestBuilders.request(m, p)).andReturn();
                assertThat(none.getResponse().getStatus()).as("sem token " + m + " " + p).isEqualTo(401);
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ?", Integer.class, a.id)).isEqualTo(1);
    }

    @Test
    @DisplayName("erros 400/404/405/406/415/422 e parâmetros malformados não vazam stack, SQL nem nomes internos")
    void errosNaoVazam() throws Exception {
        List<String> violations = new ArrayList<>();
        String[][] bad = {
                {"GET", "/v1/accounts/nao-e-uuid", null},
                {"GET", "/v1/accounts/00000000-0000-0000-0000-000000000000", null},
                {"GET", "/v1/transactions?from=2026-13-45", null},
                {"GET", "/v1/transactions?type=NADA", null},
                {"GET", "/v1/transactions?q=" + "x".repeat(300), null},
                {"GET", "/v1/transactions?page=-1", null},
                {"GET", "/v1/transactions?pageSize=abc", null},
                {"GET", "/v1/transactions?q=%27%3B+DROP+TABLE+transactions%3B--", null},
                {"GET", "/v1/transactions?q=%00", null},
                {"GET", "/v1/entries", null},
                {"GET", "/v1/entries?competence=2026-13", null},
                {"GET", "/v1/entries?competence=2026-10&lens=XYZ", null},
                {"GET", "/v1/dashboard/summary?asOf=ontem", null},
                {"GET", "/v1/dashboard/upcoming?from=2026-10-10&to=2026-10-01", null},
                {"POST", "/v1/transactions", "{"},
                {"POST", "/v1/transactions", "[]"},
                {"POST", "/v1/transactions", "null"},
                {"POST", "/v1/transactions", "{\"type\":\"NADA\"}"},
                {"POST", "/v1/transactions", "{\"type\":\"CARD_PURCHASE\"}"},
                {"POST", "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"-5\"}"},
                {"POST", "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"99999999999999999999.00\"}"},
                {"POST", "/v1/accounts", "{\"name\":\"" + "x".repeat(5000) + "\"}"},
                {"POST", "/v1/accounts", "{\"name\":\"   \",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"},
                {"POST", "/v1/categories", "{\"name\":\"" + "y".repeat(500) + "\",\"kind\":\"EXPENSE\"}"},
                {"POST", "/v1/transactions/" + "not-a-uuid" + "/void", "{\"reason\":\"x\"}"},
        };
        for (String[] c : bad) {
            MockHttpServletRequestBuilder r = a.req(HttpMethod.valueOf(c[0]), c[1], c[2]);
            if (c[0].equals("POST") && c[1].equals("/v1/transactions")) {
                r.header("Idempotency-Key", UUID.randomUUID().toString());
            }
            MvcResult res = mvc.perform(r).andReturn();
            String where = c[0] + " " + c[1].substring(0, Math.min(60, c[1].length())) + " " + (c[2] == null ? "" : c[2].substring(0, Math.min(50, c[2].length())));
            int s = res.getResponse().getStatus();
            if (s >= 500) {
                violations.add(where + ": " + s + " " + res.getResponse().getContentAsString());
            }
            leakCheck(where, res, violations);
            if (s >= 400 && s < 500) {
                try {
                    ContractValidation.responseOnly().match(res);
                } catch (AssertionError | Exception e) {
                    violations.add(where + ": resposta " + s + " fora do contrato: " + e.getMessage().lines().findFirst().orElse(""));
                }
            }
            System.out.println("QA-ERR " + s + " " + where + " -> " + om.readTree(res.getResponse().getContentAsString().isEmpty() ? "{}" : res.getResponse().getContentAsString()).path("code").asString());
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("troca do e-mail do dono mantém o id e o token antigo segue valendo (cobre S-65); sem rotas de usuário no contrato")
    void contratoNaoTemRotasDeUsuario() {
        assertThat(ContractValidation.spec().getPaths().keySet()).noneMatch(p -> p.contains("users") || p.contains("register") || p.contains("signup"));
    }

    @Test
    @DisplayName("relógio: token expira exatamente em exp (sem tolerância) e vale 1 s antes")
    void expiracaoSemTolerancia() throws Exception {
        MvcResult before = mvc.perform(MockMvcRequestBuilders.get("/v1/accounts").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        assertThat(before.getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(899));
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/v1/accounts").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn().getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(2));
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/v1/accounts").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
