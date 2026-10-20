package com.moneylog.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCase;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCase.RefreshCommand;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.exception.AuthExceptions.RefreshInvalidException;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("Autenticação e autorização com mais de um usuário")
class MultiUserAuthIT {

    private static final String ORIGIN = "https://app.moneylog.test";
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String OWNER_PASSWORD = "senha-forte-123";
    private static final String MEMBER_PASSWORD = "senha-do-membro-456";
    private static final AtomicInteger IPS = new AtomicInteger(100);
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserGateway users;
    @Autowired
    private PasswordVerifier passwordVerifier;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private ItAuth auth;
    @Autowired
    private MutableClock clock;
    @Autowired
    private RefreshSessionUseCase refreshSession;
    @Autowired
    private RefreshTokenGateway refreshTokens;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void limpar() {
        clock.set(MutableClockConfig.INITIAL);
        for (UUID id : created) {
            write("delete from refresh_tokens where user_id = ?", id);
            write("delete from users where id = ?", id);
        }
        created.clear();
    }

    private void write(String sql, Object... args) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.update(sql, args));
    }

    private static String novoIp() {
        return "203.0.113." + IPS.incrementAndGet() % 250;
    }

    private User member(String status) {
        UUID id = UUID.randomUUID();
        String email = "membro-" + SEQ.incrementAndGet() + "-" + id.toString().substring(0, 8) + "@example.com";
        users.insert(User.member(id, email));
        created.add(id);
        write("update users set password_hash = ?, password_set_at = now(), status = ?, disabled_at = case when ? = 'DISABLED' then now() end where id = ?",
                passwordVerifier.encode(MEMBER_PASSWORD), status, status, id);
        return users.findById(id).orElseThrow();
    }

    private MockHttpServletRequestBuilder login(String email, String password, String ip) {
        return post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }).content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private MvcResult loginOk(String email, String password) throws Exception {
        return mockMvc.perform(login(email, password, novoIp())).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(MvcResult result, ObjectMapper mapper) throws Exception {
        return "Bearer " + mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static String refreshCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        return setCookie.substring("ml_refresh=".length(), setCookie.indexOf(';'));
    }

    private MockHttpServletRequestBuilder refresh(String cookie) {
        return post("/v1/auth/refresh").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", cookie));
    }

    private String forgedToken(UUID subject, String claimedRole) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(subject.toString()).issuedAt(now).expiresAt(now.plusSeconds(600))
                .id(UUID.randomUUID().toString()).claim("role", claimedRole).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    @DisplayName("login do dono devolve user com role OWNER e o JWT leva a claim role")
    void donoRecebeUserOwner() throws Exception {
        MvcResult result = loginOk(OWNER_EMAIL, OWNER_PASSWORD);

        JsonNode user = json(result).get("user");
        assertThat(user.get("email").asText()).isEqualTo(OWNER_EMAIL);
        assertThat(user.get("role").asText()).isEqualTo("OWNER");
        assertThat(user.get("id").asText()).isEqualTo(auth.ownerId().toString());
        String payload = new String(java.util.Base64.getUrlDecoder().decode(json(result).get("accessToken").asText().split("\\.")[1]));
        assertThat(objectMapper.readTree(payload).get("role").asText()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("membro ativo entra, recebe user com role MEMBER e o último acesso é gravado")
    void membroEntra() throws Exception {
        User member = member("ACTIVE");

        MvcResult result = loginOk(member.email(), MEMBER_PASSWORD);

        assertThat(json(result).get("user").get("id").asText()).isEqualTo(member.id().toString());
        assertThat(json(result).get("user").get("role").asText()).isEqualTo("MEMBER");
        assertThat(jdbc.queryForObject("select last_login_at from users where id = ?", java.sql.Timestamp.class, member.id())).isNotNull();
    }

    @Test
    @DisplayName("membro recebe 403 em /v1/users e /v1/invitations; o dono passa pela autorização; preview e accept são públicos")
    void gestaoSoParaODono() throws Exception {
        String memberToken = bearer(loginOk(member("ACTIVE").email(), MEMBER_PASSWORD), objectMapper);
        String ownerToken = auth.ownerBearer();

        mockMvc.perform(get("/v1/users").header("Authorization", memberToken)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN")).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/invitations").header("Authorization", memberToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"x@example.com\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/users/" + UUID.randomUUID() + "/disable").header("Authorization", memberToken))
                .andExpect(status().isForbidden());
        assertThat(mockMvc.perform(get("/v1/users").header("Authorization", ownerToken)).andReturn().getResponse().getStatus())
                .isNotIn(401, 403);
        assertThat(mockMvc.perform(post("/v1/invitations/preview").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}"))
                .andReturn().getResponse().getStatus()).isNotIn(401, 403);
        mockMvc.perform(get("/v1/users")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("JWT de membro com claim role=OWNER assinada com a chave válida continua 403: o papel vem do banco")
    void claimForjadaNaoAutoriza() throws Exception {
        User member = member("ACTIVE");

        mockMvc.perform(get("/v1/users").header("Authorization", forgedToken(member.id(), "OWNER"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("JWT de dono com claim role=MEMBER ainda autoriza a gestão: a claim nem é lida")
    void claimObsoletaDeDonoNaoRebaixa() throws Exception {
        int status = mockMvc.perform(get("/v1/users").header("Authorization", forgedToken(auth.ownerId(), "MEMBER"))).andReturn()
                .getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    @Test
    @DisplayName("IA: membro recebe 403 em previews, análises e configuração; GET /v1/ai/status é 200 com enabled=false; o dono segue normal")
    void iaProibidaParaMembro() throws Exception {
        String memberToken = bearer(loginOk(member("ACTIVE").email(), MEMBER_PASSWORD), objectMapper);

        mockMvc.perform(post("/v1/ai/previews").header("Authorization", memberToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purpose\":\"MONTHLY_SUMMARY\"}")).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/ai/previews/" + UUID.randomUUID()).header("Authorization", memberToken)).andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/ai/analyses").header("Authorization", memberToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"previewId\":\"" + UUID.randomUUID() + "\",\"approve\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/ai/analyses").header("Authorization", memberToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/ai/analyses/" + UUID.randomUUID()).header("Authorization", memberToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/settings/ai").header("Authorization", memberToken)).andExpect(status().isForbidden());
        mockMvc.perform(put("/v1/settings/ai").header("Authorization", memberToken).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/ai/status").header("Authorization", memberToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.provider").value("none"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(get("/v1/ai/status").header("Authorization", auth.ownerBearer())).andExpect(status().isOk());
        mockMvc.perform(get("/v1/settings/ai").header("Authorization", auth.ownerBearer())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("usuário desativado: login genérico; access token ainda válido vira 401; refresh vira 401 sem emitir nada; reativado volta com o mesmo access token")
    void desativadoReativado() throws Exception {
        User member = member("ACTIVE");
        MvcResult session = loginOk(member.email(), MEMBER_PASSWORD);
        String token = bearer(session, objectMapper);
        String cookie = refreshCookie(session);
        mockMvc.perform(get("/v1/ai/status").header("Authorization", token)).andExpect(status().isOk());

        write("update users set status = 'DISABLED', disabled_at = now() where id = ?", member.id());

        mockMvc.perform(login(member.email(), MEMBER_PASSWORD, novoIp())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(get("/v1/ai/status").header("Authorization", token)).andExpect(status().isUnauthorized());
        int tokensBefore = jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class, member.id());
        MvcResult refused = mockMvc.perform(refresh(cookie)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_INVALID")).andReturn();
        assertThat(refused.getResponse().getContentAsString()).doesNotContain("accessToken");
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class, member.id()))
                .isEqualTo(tokensBefore);

        write("update users set status = 'ACTIVE', disabled_at = null where id = ?", member.id());

        mockMvc.perform(get("/v1/ai/status").header("Authorization", token)).andExpect(status().isOk());
        mockMvc.perform(refresh(cookie)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("promoção e rebaixamento no banco valem na próxima requisição com os mesmos tokens")
    void papelDoBancoValeNaProximaRequisicao() throws Exception {
        User member = member("ACTIVE");
        UUID ownerId = auth.ownerId();
        String ownerToken = auth.ownerBearer();
        String token = bearer(loginOk(member.email(), MEMBER_PASSWORD), objectMapper);
        mockMvc.perform(get("/v1/users").header("Authorization", token)).andExpect(status().isForbidden());

        write("update users set role = 'MEMBER' where id = ?", ownerId);
        write("update users set role = 'OWNER' where id = ?", member.id());
        try {
            assertThat(mockMvc.perform(get("/v1/users").header("Authorization", token)).andReturn().getResponse().getStatus())
                    .isNotIn(401, 403);
            mockMvc.perform(get("/v1/users").header("Authorization", ownerToken)).andExpect(status().isForbidden());
        } finally {
            write("update users set role = 'MEMBER' where id = ?", member.id());
            write("update users set role = 'OWNER' where id = ?", ownerId);
        }
    }

    private record Observed(int status, String body, Map<String, List<String>> headers, long nanos) {
    }

    private Observed observe(String email, String password) throws Exception {
        long start = System.nanoTime();
        MvcResult result = mockMvc.perform(login(email, password, novoIp())).andReturn();
        long elapsed = System.nanoTime() - start;
        Map<String, List<String>> headers = new TreeMap<>();
        for (String name : result.getResponse().getHeaderNames()) {
            if (!name.equalsIgnoreCase("Date") && !name.equalsIgnoreCase("X-Trace-Id")) {
                headers.put(name, result.getResponse().getHeaders(name));
            }
        }
        String body = objectMapper.readTree(result.getResponse().getContentAsString()).toString()
                .replaceAll("\"traceId\":\"[0-9a-f]+\"", "");
        return new Observed(result.getResponse().getStatus(), body, headers, elapsed);
    }

    private static long median(List<Observed> samples) {
        return samples.stream().mapToLong(Observed::nanos).sorted().skip(samples.size() / 2).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("e-mail inexistente, usuário desativado e senha errada: mesmo status, corpo e cabeçalhos, com latência equivalente")
    void respostaEquivalenteContraEnumeracao() throws Exception {
        User active = member("ACTIVE");
        User disabled = member("DISABLED");
        observe(active.email(), "aquecimento-bcrypt");

        List<Observed> wrong = new ArrayList<>();
        List<Observed> off = new ArrayList<>();
        List<Observed> unknown = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            wrong.add(observe(active.email(), "errada-" + i));
            off.add(observe(disabled.email(), MEMBER_PASSWORD));
            unknown.add(observe("ninguem-" + i + "-" + UUID.randomUUID() + "@example.com", MEMBER_PASSWORD));
        }

        Observed reference = wrong.get(0);
        assertThat(reference.status()).isEqualTo(401);
        for (Observed other : List.of(off.get(0), unknown.get(0))) {
            assertThat(other.status()).isEqualTo(reference.status());
            assertThat(other.body()).isEqualTo(reference.body());
            assertThat(other.headers()).isEqualTo(reference.headers());
        }
        double base = median(wrong);
        assertThat(median(off) / base).isBetween(0.33, 3.0);
        assertThat(median(unknown) / base).isBetween(0.33, 3.0);
    }

    @Test
    @DisplayName("rate limit por e-mail: excedido para e-mail inexistente e para existente, o 429 é idêntico")
    void rateLimitIdentico() throws Exception {
        User active = member("ACTIVE");
        String unknown = "fantasma-" + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(login(active.email(), "errada" + i, novoIp())).andExpect(status().isUnauthorized());
            mockMvc.perform(login(unknown, "errada" + i, novoIp())).andExpect(status().isUnauthorized());
        }

        Observed existing = observe(active.email(), MEMBER_PASSWORD);
        Observed missing = observe(unknown, MEMBER_PASSWORD);

        assertThat(existing.status()).isEqualTo(429);
        assertThat(missing.status()).isEqualTo(429);
        assertThat(missing.body()).isEqualTo(existing.body());
        assertThat(missing.headers()).isEqualTo(existing.headers());
    }

    @Test
    @DisplayName("membro tentando o primeiro acesso do dono recebe 401 INVALID_SETUP_CODE")
    void membroNaoUsaPrimeiroAcesso() throws Exception {
        User member = member("ACTIVE");

        mockMvc.perform(post("/v1/auth/setup-password").contentType(MediaType.APPLICATION_JSON).with(request -> {
            request.setRemoteAddr(novoIp());
            return request;
        }).content("{\"email\":\"" + member.email() + "\",\"setupCode\":\"qualquer-codigo-123\",\"newPassword\":\"outra-senha-longa-789\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SETUP_CODE"));
    }

    @Test
    @DisplayName("desativação concorrente com rotação de refresh: se o usuário é desativado e revogado antes, a rotação não emite token; se a rotação vem antes, a revogação a alcança")
    void rotacaoConcorrenteComDesativacao() throws Exception {
        User member = member("ACTIVE");
        MvcResult session = loginOk(member.email(), MEMBER_PASSWORD);
        String cookie = refreshCookie(session);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> disabling = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from users where id = ? for update", UUID.class, member.id());
                locked.countDown();
                await(release);
                write("update users set status = 'DISABLED', disabled_at = now() where id = ?", member.id());
                refreshTokens.revokeAllByUser(member.id(), Instant.now());
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<String> rotating = pool.submit(() -> {
                try {
                    refreshSession.handle(new RefreshCommand(cookie));
                    return "EMITIU";
                } catch (RefreshInvalidException e) {
                    return "RECUSOU";
                }
            });
            Thread.sleep(300);
            assertThat(rotating.isDone()).as("a rotação espera a desativação em andamento").isFalse();
            release.countDown();
            disabling.get(10, TimeUnit.SECONDS);

            assertThat(rotating.get(10, TimeUnit.SECONDS)).isEqualTo("RECUSOU");
            assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                    Integer.class, member.id())).isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("rotação que commita antes da desativação: o refresh novo existe e a revogação da desativação o alcança")
    void rotacaoAntesDaDesativacao() throws Exception {
        User member = member("ACTIVE");
        String cookie = refreshCookie(loginOk(member.email(), MEMBER_PASSWORD));

        mockMvc.perform(refresh(cookie)).andExpect(status().isOk());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            jdbc.queryForObject("select id from users where id = ? for update", UUID.class, member.id());
            write("update users set status = 'DISABLED', disabled_at = now() where id = ?", member.id());
            refreshTokens.revokeAllByUser(member.id(), Instant.now());
        });

        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class,
                member.id())).isZero();
        mockMvc.perform(refresh(cookie)).andExpect(status().isUnauthorized());
    }
}
