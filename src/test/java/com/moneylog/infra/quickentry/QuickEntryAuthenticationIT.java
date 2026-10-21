package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

import io.swagger.v3.oas.models.PathItem;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.UserStatus;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.notification.domain.SecurityEvent;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@Execution(ExecutionMode.SAME_THREAD)
@ExtendWith(OutputCaptureExtension.class)
@Import({MutableClockConfig.class, QuickEntryAuthenticationIT.PushRecorder.class})
@DisplayName("Autenticação por token de lançamento rápido (T4)")
class QuickEntryAuthenticationIT {

    private static final String PATH = "/v1/quick-entries";
    private static final AtomicInteger IPS = new AtomicInteger();

    record Sent(PushSubscription subscription, PushSender.PushMessage message) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PushRecorder {

        static final List<Sent> SENT = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        PushSender recordingPushSender() {
            return new PushSender() {
                @Override
                public Result send(PushSubscription subscription, PushMessage message) {
                    SENT.add(new Sent(subscription, message));
                    return Result.SENT;
                }

                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public String publicKey() {
                    return "test";
                }
            };
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private QuickEntryTokenGateway tokens;
    @Autowired
    private UserGateway users;
    @Autowired
    private PushSubscriptionGateway subscriptions;
    @Autowired
    private MutableClock clock;
    @Autowired
    private CategoryGateway categories;

    private TransactionTemplate tx;
    private UUID userId;
    private UUID accountId;
    private String ip;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        tx = new TransactionTemplate(transactionManager);
        ip = uniqueIp("203.0");
        userId = newUser();
        accountId = UUID.fromString(createWallet(userId));
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            SqlFixtures.insertUser(jdbc, id, "qa-" + id + "@example.com");
            categories.saveSystem(Category.createSystem(UUID.randomUUID(), id, "Outros", CategoryKind.EXPENSE), "OTHER_EXPENSE");
        });
        return id;
    }

    private String createWallet(UUID owner) throws Exception {
        String body = "{\"name\":\"Carteira\",\"type\":\"WALLET\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},"
                + "\"openingDate\":\"2026-10-01\"}";
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/v1/accounts").header(HttpHeaders.AUTHORIZATION, auth.bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    private QuickEntryTokenSecret issue(UUID owner, UUID account, Duration validity) {
        QuickEntryTokenSecret secret = QuickEntryTokenSecret.generate();
        Instant now = clock.instant();
        tokens.insert(QuickEntryToken.issue(UUID.randomUUID(), owner, account, "iPhone", secret.prefix(), secret.hash(), now, now.plus(validity)));
        return secret;
    }

    private QuickEntryTokenSecret issue() {
        return issue(userId, accountId, Duration.ofDays(30));
    }

    private MockHttpServletRequestBuilder post(String authorization, String remote) {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID().toString()).content("{\"kind\":\"EXPENSE\",\"amount\":\"1,00\"}")
                .with(request -> {
                    request.setRemoteAddr(remote);
                    return request;
                });
        return authorization == null ? builder : builder.header(HttpHeaders.AUTHORIZATION, authorization);
    }

    private MvcResult call(QuickEntryTokenSecret secret) throws Exception {
        return mvc.perform(post("Bearer " + secret.value(), ip)).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> fingerprint(MvcResult result) throws Exception {
        Map<String, Object> out = new TreeMap<>();
        out.put("status", result.getResponse().getStatus());
        JsonNode node = body(result);
        node.properties().forEach(entry -> {
            if (!"traceId".equals(entry.getKey())) {
                out.put("body." + entry.getKey(), entry.getValue().toString());
            }
        });
        result.getResponse().getHeaderNames().forEach(name -> {
            if (!name.toLowerCase().contains("trace") && !name.equalsIgnoreCase("X-Request-Id")) {
                out.put("header." + name.toLowerCase(), String.valueOf(result.getResponse().getHeaderValues(name)));
            }
        });
        return out;
    }

    private UUID ownerOf(String transactionId) {
        return jdbc.queryForObject("select user_id from transactions where id = ?", UUID.class, UUID.fromString(transactionId));
    }

    private int quickEntryRows() {
        return jdbc.queryForObject("select count(*) from quick_entry_audit", Integer.class);
    }

    private Map<String, Object> lastUse(QuickEntryTokenSecret secret) {
        return jdbc.queryForMap("select last_used_at, last_used_ip, last_used_user_agent from quick_entry_tokens where token_hash = ?",
                secret.hash());
    }

    private String uniqueIp(String firstTwoOctets) {
        int n = IPS.incrementAndGet();
        return firstTwoOctets + "." + (n / 250 % 250) + "." + (n % 250 + 1);
    }

    private String subscribe(UUID owner) {
        String endpoint = "https://push.example/" + UUID.randomUUID();
        tx.executeWithoutResult(status -> subscriptions.upsert(
                new PushSubscription(UUID.randomUUID(), owner, endpoint, "p", "a", "ua", Instant.now())));
        return endpoint;
    }

    private List<Sent> sentTo(String endpoint) {
        return PushRecorder.SENT.stream().filter(sent -> sent.subscription().endpoint().equals(endpoint))
                .filter(sent -> !sent.message().titleKey().equals(SecurityEvent.QUICK_ENTRY_CREATED.titleKey())).toList();
    }

    private List<Sent> awaitSent(String endpoint, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (sentTo(endpoint).size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        return sentTo(endpoint);
    }

    @Test
    @DisplayName("token válido autentica como o dono do token, sem Origin, e grava uso com IP mascarado e UA truncado")
    void deveAutenticarEGravarUso() throws Exception {
        QuickEntryTokenSecret secret = issue();

        MvcResult result = mvc.perform(post("Bearer " + secret.value(), ip).header("User-Agent", "Atalhos/" + "x".repeat(300))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(ownerOf(body(result).get("id").asString())).isEqualTo(userId);
        Map<String, Object> use = lastUse(secret);
        assertThat(use.get("last_used_at")).isNotNull();
        assertThat(use.get("last_used_ip")).isEqualTo(ip.substring(0, ip.lastIndexOf('.')) + ".0");
        assertThat((String) use.get("last_used_user_agent")).hasSize(120).startsWith("Atalhos/");
    }

    @Test
    @DisplayName("grava o uso no máximo uma vez por minuto")
    void deveGravarNoMaximoUmaVezPorMinuto() throws Exception {
        QuickEntryTokenSecret secret = issue();
        call(secret);
        Object first = lastUse(secret).get("last_used_at");

        clock.advance(Duration.ofSeconds(30));
        call(secret);
        assertThat(lastUse(secret).get("last_used_at")).isEqualTo(first);

        clock.advance(Duration.ofSeconds(31));
        assertThat(call(secret).getResponse().getStatus()).isEqualTo(201);
        assertThat(lastUse(secret).get("last_used_at")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("cada token autentica o seu próprio usuário")
    void deveIsolarUsuarios() throws Exception {
        UUID other = newUser();
        QuickEntryTokenSecret mine = issue();
        QuickEntryTokenSecret theirs = issue(other, UUID.fromString(createWallet(other)), Duration.ofDays(30));

        assertThat(ownerOf(body(call(mine)).get("id").asString())).isEqualTo(userId);
        assertThat(ownerOf(body(call(theirs)).get("id").asString())).isEqualTo(other);
    }

    @Test
    @DisplayName("inexistente, expirado, revogado e usuário desativado: 401 QUICK_TOKEN_INVALID com o mesmo status, corpo e cabeçalhos")
    void deveDarMesma401NosQuatroCasos() throws Exception {
        QuickEntryTokenSecret unknown = QuickEntryTokenSecret.generate();
        QuickEntryTokenSecret expired = issue(userId, accountId, Duration.ofHours(1));
        QuickEntryTokenSecret revoked = issue();
        tokens.update(tokens.findByTokenHash(userId, revoked.hash()).orElseThrow().revoke(TokenRevocationReason.USER, clock.instant()));
        UUID disabledUser = newUser();
        QuickEntryTokenSecret ofDisabled = issue(disabledUser, UUID.fromString(createWallet(disabledUser)), Duration.ofDays(30));
        tx.executeWithoutResult(status -> users.updateStatus(disabledUser, UserStatus.DISABLED, clock.instant()));
        clock.advance(Duration.ofHours(2));

        MvcResult reference = call(unknown);
        assertThat(reference.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(reference).get("code").asString()).isEqualTo("QUICK_TOKEN_INVALID");
        assertThat(reference.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(reference.getResponse().getContentType()).startsWith("application/problem+json");
        Map<String, Object> expected = fingerprint(reference);
        for (QuickEntryTokenSecret secret : List.of(expired, revoked, ofDisabled)) {
            assertThat(fingerprint(call(secret))).isEqualTo(expected);
        }
        assertThat(fingerprint(mvc.perform(post(null, ip)).andReturn())).isEqualTo(expected);
        assertThat(fingerprint(mvc.perform(post("Bearer lixo", ip)).andReturn())).isEqualTo(expected);
        assertThat(lastUse(revoked).get("last_used_at")).isNull();
    }

    @Test
    @DisplayName("token só vale em Authorization: Bearer; query string e outro esquema não autenticam")
    void deveAceitarSoOCabecalhoBearer() throws Exception {
        QuickEntryTokenSecret secret = issue();

        assertThat(mvc.perform(MockMvcRequestBuilders.post(PATH + "?access_token=" + secret.value()).contentType(MediaType.APPLICATION_JSON)
                .content("{}").with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("Token " + secret.value(), ip)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("Basic " + secret.value(), ip)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("JWT de sessão válido em POST /v1/quick-entries: 401 QUICK_TOKEN_INVALID")
    void deveRecusarJwtDeSessao() throws Exception {
        MvcResult result = mvc.perform(post(auth.bearer(userId), ip)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(result).get("code").asString()).isEqualTo("QUICK_TOKEN_INVALID");
    }

    @Test
    @DisplayName("token válido em toda outra rota do contrato (matriz) responde 401, inclusive nas rotas abertas")
    void deveRecusarOTokenEmTodasAsOutrasRotas() throws Exception {
        QuickEntryTokenSecret secret = issue();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, PathItem> path : ContractValidation.spec().getPaths().entrySet()) {
            String url = path.getKey().replaceAll("\\{[^}/]+}", UUID.randomUUID().toString());
            for (Map.Entry<PathItem.HttpMethod, ?> operation : path.getValue().readOperationsMap().entrySet()) {
                if (operation.getKey() == PathItem.HttpMethod.POST && PATH.equals(path.getKey())) {
                    continue;
                }
                MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(operation.getKey().name()), url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret.value()).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        });
                MvcResult result = mvc.perform(request).andReturn();
                checked++;
                String code = result.getResponse().getContentAsString().contains("\"UNAUTHENTICATED\"") ? "UNAUTHENTICATED" : "outro";
                if (result.getResponse().getStatus() != 401 || !"UNAUTHENTICATED".equals(code)) {
                    failures.add(operation.getKey() + " " + url + " -> " + result.getResponse().getStatus());
                }
            }
        }
        assertThat(checked).isGreaterThan(100);
        assertThat(failures).isEmpty();
        assertThat(lastUse(secret).get("last_used_at")).isNull();
    }

    @Test
    @DisplayName("GET, HEAD, OPTIONS e variantes de caminho com token válido não autenticam nem caem em rota aberta")
    void deveRecusarVariantesDaRota() throws Exception {
        QuickEntryTokenSecret secret = issue();
        List<String> accepted = new ArrayList<>();
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (String url : List.of(PATH, PATH + "/", PATH + ";jsessionid=ABC", "/v1/Quick-Entries", PATH + "/x")) {
                MvcResult result = mvc.perform(MockMvcRequestBuilders.request(method, url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret.value()).with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        })).andReturn();
                int status = result.getResponse().getStatus();
                if (status != 401 && status != 400) {
                    accepted.add(method + " " + url + " -> " + status);
                }
                assertThat(result.getResponse().getContentAsString()).doesNotContain(userId.toString());
            }
        }
        for (String url : List.of(PATH + "/", PATH + ";jsessionid=ABC", "/v1/Quick-Entries")) {
            int status = mvc.perform(MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + secret.value())
                    .contentType(MediaType.APPLICATION_JSON).content("{}").with(r -> {
                        r.setRemoteAddr(ip);
                        return r;
                    })).andReturn().getResponse().getStatus();
            if (status != 401 && status != 400) {
                accepted.add("POST " + url + " -> " + status);
            }
        }
        mvc.perform(MockMvcRequestBuilders.post("//v1/quick-entries").header(HttpHeaders.AUTHORIZATION, "Bearer " + secret.value())
                .contentType(MediaType.APPLICATION_JSON).content("{}").with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })).andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 401));
        assertThat(accepted).isEmpty();
        assertThat(lastUse(secret).get("last_used_at")).isNull();
    }

    private static final List<String> ENCODED_PATHS = List.of("/v1/quick%2Dentries", "/v1/quick%2dentries", "/v1/%71uick-entries",
            "/v1/quick-entrie%73", "/%76%31/quick-entries", "/v1/%51uick-entries", "/v1/QUICK%2DENTRIES", "/v1/quick%2Dentries/",
            "/v1/quick-entries/", "/v1/Quick-Entries", "/v1/quick%2Dentries%2F", "/v1/quick%2Eentries", "/v1/quick-entries%2E",
            "/v1/quick%252Dentries", "/v1/quick-entries%00", "/v1/quick-entries%20", "/v1/quick%2Dentries;jsessionid=1", "/v1/quick-entries;x=1",
            "/v1/./quick-entries", "/v1/x/../quick-entries", "/v1/quick-entries%3Fa=1", "/v1/quick%5Centries", "/v1/quick-entries%2F%2F");

    private MvcResult raw(HttpMethod method, String encodedPath, String authorization) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, URI.create("http://localhost" + encodedPath))
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID().toString())
                .content("{\"kind\":\"EXPENSE\",\"amount\":\"1,00\"}").with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
        return mvc.perform(authorization == null ? builder : builder.header(HttpHeaders.AUTHORIZATION, authorization)).andReturn();
    }

    @Test
    @DisplayName("JWT de sessão nunca alcança o controller da rota, nem por caminho codificado (%2D, %2d, %71, caixa, barra, ponto)")
    void deveBarrarJwtEmCaminhosCodificados() throws Exception {
        int before = quickEntryRows();
        List<String> reached = new ArrayList<>();
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
            for (String path : ENCODED_PATHS) {
                MvcResult result = raw(method, path, auth.bearer(userId));
                int status = result.getResponse().getStatus();
                if (status < 400 || status == 405 || status >= 500) {
                    reached.add(method + " " + path + " -> " + status);
                }
            }
        }
        assertThat(reached).isEmpty();
        assertThat(quickEntryRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("sem credencial, os mesmos caminhos codificados nunca alcançam o controller")
    void deveBarrarAnonimoEmCaminhosCodificados() throws Exception {
        int before = quickEntryRows();
        for (String path : ENCODED_PATHS) {
            assertThat(raw(HttpMethod.POST, path, null).getResponse().getStatus()).isIn(400, 401);
        }
        assertThat(quickEntryRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("token mlq_ nunca alcança outra rota por caminho codificado; na própria rota codificada só autentica como token")
    void deveManterOTokenSoNaRotaDele() throws Exception {
        QuickEntryTokenSecret secret = issue();
        String bearer = "Bearer " + secret.value();

        for (String other : List.of("/v1/transactions", "/v1/%74ransactions", "/v1/quick%2Dentry%2Dtokens", "/v1/auth/%6Cogin",
                "/v1/accounts", "/v1/quick-entry-tokens")) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST)) {
                int status = raw(method, other, bearer).getResponse().getStatus();
                assertThat(status).as(method + " " + other).isIn(400, 401);
            }
        }

        int before = quickEntryRows();
        MvcResult sameRoute = raw(HttpMethod.POST, "/v1/quick%2Dentries", bearer);
        assertThat(sameRoute.getResponse().getStatus()).isEqualTo(201);
        assertThat(ownerOf(body(sameRoute).get("id").asString())).isEqualTo(userId);
        assertThat(quickEntryRows()).isEqualTo(before + 1);
        assertThat(raw(HttpMethod.POST, "/v1/quick%2Dentries", "Bearer " + QuickEntryTokenSecret.generate().value()).getResponse()
                .getContentAsString()).contains("QUICK_TOKEN_INVALID");
    }

    @Test
    @DisplayName("acima de 10 falhas por IP na janela: 429 com Retry-After, até para token válido; outro IP não é afetado; passada a janela, libera")
    void deveLimitarFalhasPorIp() throws Exception {
        QuickEntryTokenSecret secret = issue();
        for (int i = 0; i < 10; i++) {
            assertThat(mvc.perform(post("Bearer " + QuickEntryTokenSecret.generate().value(), ip)).andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }

        MvcResult blocked = mvc.perform(post("Bearer " + secret.value(), ip)).andReturn();
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(body(blocked).get("code").asString()).isEqualTo("TOO_MANY_ATTEMPTS");
        assertThat(mvc.perform(post("Bearer " + secret.value(), uniqueIp("172.16"))).andReturn().getResponse().getStatus()).isEqualTo(201);

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(call(secret).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("acima de 20 chamadas por minuto com token válido: 429 com Retry-After e o token continua válido")
    void deveLimitarChamadasPorToken() throws Exception {
        QuickEntryTokenSecret secret = issue();
        for (int i = 0; i < 20; i++) {
            assertThat(call(secret).getResponse().getStatus()).isEqualTo(201);
        }

        MvcResult limited = call(secret);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotNull();

        clock.advance(Duration.ofSeconds(61));
        assertThat(call(secret).getResponse().getStatus()).isEqualTo(201);
        Integer revoked = jdbc.queryForObject("select count(*) from quick_entry_tokens where token_hash = ? and revoked_at is not null",
                Integer.class, secret.hash());
        assertThat(revoked).isZero();
    }

    @Test
    @DisplayName("rajada de 401 gera um alerta ao dono, com IP mascarado e sem o segredo")
    void deveAlertarRajadaDe401() throws Exception {
        UUID ownerId = auth.ownerId();
        String endpoint = subscribe(ownerId);
        QuickEntryTokenSecret valid = issue();
        String badIp = uniqueIp("198.51");
        for (int i = 0; i < 11; i++) {
            assertThat(mvc.perform(post("Bearer " + QuickEntryTokenSecret.generate().value(), badIp)).andReturn().getResponse().getStatus())
                    .isEqualTo(i < 10 ? 401 : 429);
        }

        List<Sent> sent = awaitSent(endpoint, 1);

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).subscription().userId()).isEqualTo(ownerId);
        assertThat(sent.get(0).message().titleKey()).isEqualTo("security.quick_token_failure_burst");
        assertThat(sent.get(0).message().params()).containsOnly(Map.entry("ip", "198.51.x.x"));
        assertThat(sent.get(0).message().toString()).doesNotContain(valid.value());
        tx.executeWithoutResult(status -> subscriptions.deleteByEndpoint(endpoint));
    }

    @Test
    @DisplayName("primeiro uso a partir de IP não visto alerta o dono do token; o primeiro uso de todos e o mesmo IP não")
    void deveAlertarIpNovo() throws Exception {
        String endpoint = subscribe(userId);
        QuickEntryTokenSecret secret = issue();
        String known = uniqueIp("203.0");
        String unseen = uniqueIp("192.0");

        mvc.perform(post("Bearer " + secret.value(), known));
        clock.advance(Duration.ofMinutes(2));
        mvc.perform(post("Bearer " + secret.value(), known));
        assertThat(sentTo(endpoint)).isEmpty();

        mvc.perform(post("Bearer " + secret.value(), unseen));
        List<Sent> sent = awaitSent(endpoint, 1);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).subscription().userId()).isEqualTo(userId);
        assertThat(sent.get(0).message().titleKey()).isEqualTo("security.quick_token_new_ip");
        assertThat(sent.get(0).message().params()).containsOnly(Map.entry("ip", "192.0.x.x"));
        assertThat(sent.get(0).message().toString()).doesNotContain(secret.value());

        mvc.perform(post("Bearer " + secret.value(), unseen));
        mvc.perform(post("Bearer " + secret.value(), known));
        assertThat(sentTo(endpoint)).hasSize(1);
        tx.executeWithoutResult(status -> subscriptions.deleteByEndpoint(endpoint));
    }

    @Test
    @DisplayName("valor colado com espaço ou tab nas pontas autentica; espaço no meio, cabeçalho ausente e cabeçalho duplicado dão 401")
    void deveAparaOValorColado() throws Exception {
        QuickEntryTokenSecret secret = issue();
        String value = secret.value();
        String remote = uniqueIp("203.1");

        for (String padded : List.of(" " + value, value + " ", "\t" + value + "\t", "  " + value + " \t ")) {
            assertThat(mvc.perform(post("Bearer " + padded, remote)).andReturn().getResponse().getStatus()).isEqualTo(201);
        }
        String middle = value.substring(0, 20) + " " + value.substring(20);
        assertThat(mvc.perform(post("Bearer " + middle, remote)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post(null, remote)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("Bearer " + value, remote).header(HttpHeaders.AUTHORIZATION, "Bearer " + value)).andReturn().getResponse()
                .getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("o valor de Authorization não aparece no log em nenhuma das respostas, inclusive nas recusadas pelo firewall")
    void naoDeveVazarOTokenNoLog(CapturedOutput output) throws Exception {
        QuickEntryTokenSecret secret = issue();
        QuickEntryTokenSecret unknown = QuickEntryTokenSecret.generate();

        for (QuickEntryTokenSecret candidate : List.of(secret, unknown)) {
            call(candidate);
            mvc.perform(MockMvcRequestBuilders.post(PATH + ";jsessionid=1").header(HttpHeaders.AUTHORIZATION, "Bearer " + candidate.value()));
            mvc.perform(MockMvcRequestBuilders.get("/v1/transactions").header(HttpHeaders.AUTHORIZATION, "Bearer " + candidate.value()));
            mvc.perform(MockMvcRequestBuilders.post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + candidate.value()).content("{"));
        }

        assertThat(output.getAll()).doesNotContain(secret.value(), unknown.value(), secret.hash(), unknown.hash());
    }
}
