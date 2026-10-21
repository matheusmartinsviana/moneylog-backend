package com.moneylog.infra.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;
import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "moneylog.users.max=1000")
@DisplayName("Convites: preview e aceite públicos")
class InvitationAcceptanceIT extends InvitationITSupport {

    private ApplicationLogs applicationLogs;

    @BeforeEach
    void captureLogs() {
        applicationLogs = ApplicationLogs.attach();
    }

    @AfterEach
    void releaseLogs() {
        applicationLogs.detach();
    }

    @Test
    @DisplayName("preview devolve e-mail e validade, com no-store, sem exigir JWT")
    void previewsValidToken() throws Exception {
        String email = newEmail();
        inviteOk(email);

        MvcResult result = mockMvc.perform(preview(lastToken(), newIp())).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(header().string("Cache-Control", "private, no-store")).andExpect(jsonPath("$.email").value(email)).andReturn();

        assertThat(Instant.parse(body(result).get("expiresAt").asText())).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(body(result).has("email")).isTrue();
        assertThat(body(result).has("expiresAt")).isTrue();
    }

    @Test
    @DisplayName("preview traz o apelido de quem convidou quando definido e nulo quando em branco, e o e-mail do dono nunca vai")
    void previewsInviterName() throws Exception {
        String email = newEmail();
        inviteOk(email);
        String token = lastToken();
        UUID ownerId = jdbc.queryForObject("select id from users where email = ?", UUID.class, OWNER_EMAIL);
        write("insert into notification_settings (user_id, summary_enabled, summary_frequency, display_name, push_enabled, version, updated_at) "
                + "values (?, false, 'DAILY', 'Matheus', false, 1, now()) on conflict (user_id) do update set display_name = 'Matheus'", ownerId);

        MvcResult named = mockMvc.perform(preview(token, newIp())).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.invitedByName").value("Matheus")).andReturn();
        assertThat(named.getResponse().getContentAsString()).doesNotContain(OWNER_EMAIL);

        write("update notification_settings set display_name = '   ' where user_id = ?", ownerId);
        MvcResult blank = mockMvc.perform(preview(token, newIp())).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn();
        assertThat(body(blank).path("invitedByName").isMissingNode() || body(blank).path("invitedByName").isNull()).isTrue();
        write("delete from notification_settings where user_id = ?", ownerId);
    }

    @Test
    @DisplayName("token inválido responde 404 sem apelido nem e-mail do dono, mesmo com apelido definido")
    void invalidTokenLeaksNoName() throws Exception {
        UUID ownerId = jdbc.queryForObject("select id from users where email = ?", UUID.class, OWNER_EMAIL);
        write("insert into notification_settings (user_id, summary_enabled, summary_frequency, display_name, push_enabled, version, updated_at) "
                + "values (?, false, 'DAILY', 'Matheus', false, 1, now()) on conflict (user_id) do update set display_name = 'Matheus'", ownerId);
        inviteOk(newEmail());

        MvcResult result = mockMvc.perform(preview(SecureToken.generate(), newIp())).andExpect(status().isNotFound()).andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("Matheus").doesNotContain(OWNER_EMAIL);
        assertThat(applicationLogs.toString()).doesNotContain("Matheus");
        write("delete from notification_settings where user_id = ?", ownerId);
    }

    @Test
    @DisplayName("fluxo completo: convite, aceite com cookie de sessão, MEMBER/ACTIVE, categorias semeadas, login, 403 na gestão, e o token não funciona de novo")
    void fullFlow() throws Exception {
        String email = newEmail();
        UUID invitationId = inviteOk(email);
        String token = lastToken();

        MvcResult accepted = mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.user.email").value(email)).andExpect(jsonPath("$.user.role").value("MEMBER")).andReturn();

        String setCookie = accepted.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("ml_refresh=").contains("HttpOnly").contains("Secure").contains("SameSite=");
        UUID userId = UUID.fromString(body(accepted).get("user").get("id").asText());
        Map<String, Object> user = jdbc.queryForMap("select role, status, password_hash, last_login_at from users where id = ?", userId);
        assertThat(user.get("role")).isEqualTo("MEMBER");
        assertThat(user.get("status")).isEqualTo("ACTIVE");
        assertThat((String) user.get("password_hash")).startsWith("$2").doesNotContain(NEW_PASSWORD);
        assertThat(user.get("last_login_at")).isNotNull();
        assertThat(dbStatus(invitationId)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("select accepted_user_id from user_invitations where id = ?", UUID.class, invitationId)).isEqualTo(userId);
        assertThat(jdbc.queryForObject("select count(*) from categories where user_id = ?", Integer.class, userId)).isPositive();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class, userId)).isEqualTo(1);

        mockMvc.perform(preview(token, newIp())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isNotFound());

        String bearer = "Bearer " + body(accepted).get("accessToken").asText();
        mockMvc.perform(get("/v1/invitations").header("Authorization", bearer)).andExpect(status().isForbidden());
        MvcResult loggedIn = mockMvc.perform(login(email, NEW_PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("MEMBER")).andReturn();
        assertThat(body(loggedIn).get("user").get("id").asText()).isEqualTo(userId.toString());
        mockMvc.perform(login(email, "outra-senha-errada-000")).andExpect(status().isUnauthorized());
        String cookieValue = setCookie.substring("ml_refresh=".length(), setCookie.indexOf(';'));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/auth/refresh")
                .header("Origin", "https://app.moneylog.test").cookie(new Cookie("ml_refresh", cookieValue))).andExpect(status().isOk());

        assertThat(applicationLogs.messages()).isNotEmpty().noneMatch(m -> m.contains(token) || m.contains(NEW_PASSWORD)
                || m.contains(SecureToken.hash(token)));
        assertThat(fake().sent()).hasSize(1);
    }

    @Test
    @DisplayName("senha fraca: 422 WEAK_PASSWORD e o token continua válido")
    void weakPasswordKeepsToken() throws Exception {
        String email = newEmail();
        UUID id = inviteOk(email);
        String token = lastToken();

        mockMvc.perform(accept(token, "curta", newIp())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD")).andExpect(ContractValidation.conforms());
        mockMvc.perform(accept(token, email, newIp())).andExpect(status().isUnprocessableEntity());

        assertThat(dbStatus(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
        mockMvc.perform(preview(token, newIp())).andExpect(status().isOk());
        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("senha fraca com token inválido responde 404, não 422")
    void weakPasswordWithBadTokenIsNotFound() throws Exception {
        mockMvc.perform(accept(SecureToken.generate(), "curta", newIp())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
    }

    @Test
    @DisplayName("token vencido: 404 e nenhum usuário criado")
    void expiredTokenIsRejected() throws Exception {
        String email = newEmail();
        inviteOk(email);
        String token = lastToken();
        advance(Duration.ofDays(7));

        mockMvc.perform(preview(token, newIp())).andExpect(status().isNotFound());
        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
    }

    @Test
    @DisplayName("e-mail virou usuário antes do aceite: 404, nenhum usuário novo e o convite fica REVOKED mesmo com a resposta 404")
    void emailBecameUserBeforeAcceptance() throws Exception {
        String email = newEmail();
        UUID id = inviteOk(email);
        String token = lastToken();
        UUID existing = existingUser(email, "ACTIVE").id();

        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));

        assertThat(dbStatus(id)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select count(*) from users where lower(email) = ?", Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select id from users where lower(email) = ?", UUID.class, email)).isEqualTo(existing);
        mockMvc.perform(preview(token, newIp())).andExpect(status().isNotFound());
    }

    private record Observed(int status, String body, Map<String, List<String>> headers, long nanos) {
    }

    private Observed observe(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        long start = System.nanoTime();
        MvcResult result = mockMvc.perform(request).andReturn();
        long elapsed = System.nanoTime() - start;
        Map<String, List<String>> headers = new TreeMap<>();
        for (String name : result.getResponse().getHeaderNames()) {
            if (!name.equalsIgnoreCase("Date") && !name.equalsIgnoreCase("X-Trace-Id")) {
                headers.put(name, result.getResponse().getHeaders(name));
            }
        }
        String content = result.getResponse().getContentAsString().replaceAll("\"traceId\":\"[0-9a-f]+\"", "");
        return new Observed(result.getResponse().getStatus(), content, headers, elapsed);
    }

    private static long median(List<Observed> samples) {
        return samples.stream().mapToLong(Observed::nanos).sorted().skip(samples.size() / 2).findFirst().orElseThrow();
    }

    private Map<String, String> invalidTokens() throws Exception {
        Map<String, String> tokens = new TreeMap<>();
        inviteOk(newEmail());
        tokens.put("expired", lastToken());
        advance(Duration.ofDays(8));
        inviteOk(newEmail());
        tokens.put("used", lastToken());
        mockMvc.perform(accept(tokens.get("used"), NEW_PASSWORD, newIp())).andExpect(status().isOk());
        UUID revokedId = inviteOk(newEmail());
        tokens.put("revoked", lastToken());
        mockMvc.perform(ownerDelete("/v1/invitations/" + revokedId)).andExpect(status().isOk());
        return tokens;
    }

    @Test
    @DisplayName("expirado, usado, revogado e inexistente: preview e aceite respondem 404 idêntico em corpo e cabeçalhos, com latência equivalente")
    void invalidTokensAreIndistinguishable() throws Exception {
        for (boolean previewing : List.of(true, false)) {
            clock.set(MutableClockConfig.INITIAL);
            Map<String, String> tokens = invalidTokens();
            List<Observed> references = new ArrayList<>();
            Map<String, List<Observed>> samples = new TreeMap<>();
            for (int i = 0; i < 4; i++) {
                tokens.put("missing", SecureToken.generate());
                for (Map.Entry<String, String> entry : tokens.entrySet()) {
                    Observed observed = observe(previewing ? preview(entry.getValue(), newIp()) : accept(entry.getValue(), NEW_PASSWORD, newIp()));
                    samples.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(observed);
                    references.add(observed);
                }
            }
            Observed reference = references.get(0);
            assertThat(reference.status()).isEqualTo(404);
            assertThat(references).allSatisfy(observed -> {
                assertThat(observed.status()).isEqualTo(404);
                assertThat(observed.body()).isEqualTo(reference.body());
                assertThat(observed.headers()).isEqualTo(reference.headers());
            });
            long base = median(samples.get("missing"));
            for (String kind : List.of("expired", "used", "revoked")) {
                double ratio = (double) median(samples.get(kind)) / base;
                assertThat(ratio).as("latência de %s vs inexistente", kind).isBetween(0.2, 5.0);
            }
        }
    }

    @Test
    @DisplayName("mais de 5 tentativas inválidas pelo mesmo IP: 429 com Retry-After, idêntico exista ou não o token")
    void rateLimitByIp() throws Exception {
        inviteOk(newEmail());
        String valid = lastToken();
        String ip = newIp();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(preview(SecureToken.generate(), ip)).andExpect(status().isNotFound());
        }

        Observed missing = observe(preview(SecureToken.generate(), ip));
        Observed existing = observe(preview(valid, ip));
        Observed accepting = observe(accept(valid, NEW_PASSWORD, ip));

        assertThat(missing.status()).isEqualTo(429);
        assertThat(existing.status()).isEqualTo(429);
        assertThat(accepting.status()).isEqualTo(429);
        assertThat(existing.body()).isEqualTo(missing.body());
        assertThat(existing.headers()).isEqualTo(missing.headers());
        assertThat(missing.headers()).containsKey("Retry-After");
        mockMvc.perform(preview(valid, newIp())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("o mesmo token inválido repetido de IPs diferentes soma no contador do token e leva a 429")
    void rateLimitByPresentedToken() throws Exception {
        String token = SecureToken.generate();
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(preview(token, newIp())).andExpect(status().isNotFound());
        }

        MvcResult blocked = mockMvc.perform(preview(token, newIp())).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS")).andReturn();
        assertThat(blocked.getResponse().getHeader("Retry-After")).isNotBlank();
        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("o corpo é validado: token em branco ou campo desconhecido dá 400; o token não aceito em caminho nem query")
    void validatesBodies() throws Exception {
        mockMvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invitations/preview"), "{\"token\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/invitations/accept"),
                "{\"token\":\"x\",\"password\":\"p\",\"role\":\"OWNER\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/invitations/accept/" + SecureToken.generate())).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("dois aceites simultâneos do mesmo token: exatamente um cria o usuário e os outros recebem 404")
    void concurrentAcceptsCreateOneUser() throws Exception {
        String email = newEmail();
        UUID id = inviteOk(email);
        String token = lastToken();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String ip = newIp();
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(accept(token, NEW_PASSWORD, ip)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses).as("statuses").containsExactlyInAnyOrder(200, 404, 404, 404);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isEqualTo(1);
        assertThat(dbStatus(id)).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("select accepted_user_id from user_invitations where id = ?", UUID.class, id))
                .isEqualTo(jdbc.queryForObject("select id from users where email = ?", UUID.class, email));
    }
}
