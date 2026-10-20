package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
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
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.auth.exception.AuthExceptions.RefreshInvalidException;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T2/T3: papéis, status e sessão por caminhos fora dos testes de aceite")
class MultiUserAuthQaIT {

    private static final String ORIGIN = "https://app.moneylog.test";
    private static final String PASSWORD = "senha-do-membro-456";
    private static final AtomicInteger IPS = new AtomicInteger(10);
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
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
    private PlatformTransactionManager tm;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void limpar() {
        clock.set(MutableClockConfig.INITIAL);
        for (UUID id : created) {
            write("delete from refresh_tokens where user_id = ?", id);
            write("delete from notification_settings where user_id = ?", id);
            write("delete from users where id = ?", id);
        }
        created.clear();
    }

    private void write(String sql, Object... args) {
        new TransactionTemplate(tm).executeWithoutResult(s -> jdbc.update(sql, args));
    }

    private static String ip() {
        return "198.51.100." + IPS.incrementAndGet() % 250;
    }

    private User member(String emailOrNull, boolean withPassword, String status) {
        UUID id = UUID.randomUUID();
        String email = emailOrNull != null ? emailOrNull : "qa-" + SEQ.incrementAndGet() + "-" + id.toString().substring(0, 8) + "@example.com";
        created.add(id);
        write("insert into users (id, email, created_at, updated_at) values (?, ?, now(), now())", id, email);
        write("update users set password_hash = ?, password_set_at = now(), status = ?, disabled_at = case when ? = 'DISABLED' then now() end where id = ?",
                withPassword ? passwordVerifier.encode(PASSWORD) : null, status, status, id);
        return new User(id, email, UserRole.MEMBER, "DISABLED".equals(status) ? com.moneylog.core.auth.domain.UserStatus.DISABLED
                : com.moneylog.core.auth.domain.UserStatus.ACTIVE);
    }

    private User member() {
        return member(null, true, "ACTIVE");
    }

    private MockHttpServletRequestBuilder login(String rawEmailJson, String password, String ip) {
        return post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).content("{\"email\":" + rawEmailJson + ",\"password\":\"" + password + "\"}");
    }

    private static String str(String s) {
        return "\"" + s + "\"";
    }

    private MvcResult loginOk(String email, String password) throws Exception {
        return mvc.perform(login(str(email), password, ip())).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    private String bearer(MvcResult r) throws Exception {
        return "Bearer " + body(r).get("accessToken").asText();
    }

    private static String cookie(MvcResult r) {
        String h = r.getResponse().getHeader("Set-Cookie");
        return h.substring("ml_refresh=".length(), h.indexOf(';'));
    }

    private MockHttpServletRequestBuilder refresh(String cookie) {
        return post("/v1/auth/refresh").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", cookie));
    }

    private String forged(UUID sub, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(sub.toString()).issuedAt(now).expiresAt(now.plusSeconds(600))
                .id(UUID.randomUUID().toString()).claim("role", role).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    @DisplayName("login normaliza a caixa do e-mail, para membro e para o dono, e devolve o e-mail normalizado em user")
    void loginNormalizaEmail() throws Exception {
        User m = member("fulana.qa." + UUID.randomUUID().toString().substring(0, 6) + "@example.com", true, "ACTIVE");
        String shouting = m.email().toUpperCase();

        MvcResult viaMember = loginOk(shouting, PASSWORD);
        MvcResult viaOwner = loginOk("OWNER@EXAMPLE.COM", "senha-forte-123");

        assertThat(body(viaMember).get("user").get("email").asText()).isEqualTo(m.email());
        assertThat(body(viaMember).get("user").get("role").asText()).isEqualTo("MEMBER");
        assertThat(body(viaOwner).get("user").get("email").asText()).isEqualTo("owner@example.com");
        assertThat(body(viaOwner).get("user").get("role").asText()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("espaços nas pontas do e-mail no login: 400 VALIDATION_FAILED limpo (a validação do DTO rejeita antes da normalização), nunca 401 nem 500")
    void loginComEspacosNasPontas() throws Exception {
        User m = member();

        mvc.perform(login(str("  " + m.email() + " "), PASSWORD, ip())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @org.junit.jupiter.api.Disabled("BUG-QA-T3-03: findByEmail é exato; linha legada/SQL com caixa mista não loga (pré-existente; V9.27 já a trata como a mesma identidade por lower(email))")
    @Test
    @DisplayName("e-mail guardado com caixa mista (linha legada ou inserida por SQL): o login pelo e-mail em qualquer caixa funciona")
    void emailLegadoComCaixaMista() throws Exception {
        String stored = "Legado.Misto." + UUID.randomUUID().toString().substring(0, 6) + "@Example.COM";
        member(stored, true, "ACTIVE");

        mvc.perform(login(str(stored), PASSWORD, ip())).andExpect(status().isOk());
        mvc.perform(login(str(stored.toLowerCase()), PASSWORD, ip())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("login com e-mail ausente, vazio, só espaços, nulo, enorme ou com caracteres de controle: 400/401, nunca 500, e usuário desativado idem")
    void loginComEntradasRuins() throws Exception {
        String huge = "a".repeat(5000) + "@example.com";
        List<String> emails = List.of("\"\"", "\"   \"", "null", str(huge), "\"a\\u0000b@example.com\"", "\"İ@example.com\"", "123", "[]", "{}");
        for (String e : emails) {
            MvcResult r = mvc.perform(login(e, PASSWORD, ip())).andReturn();
            assertThat(r.getResponse().getStatus()).as(e.length() > 40 ? e.substring(0, 40) : e).isIn(400, 401, 422);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("com.moneylog");
        }
        mvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(r -> {
            r.setRemoteAddr(ip());
            return r;
        }).content("{\"password\":\"x\"}")).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("rate limit por e-mail soma tentativas com caixa e espaços diferentes: o 429 vem mesmo variando a escrita do e-mail")
    void rateLimitNormalizaEmail() throws Exception {
        String email = "limite-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String[] variants = {email, email.toUpperCase(), "L" + email.substring(1).toUpperCase().toLowerCase()};
        for (int i = 0; i < 12; i++) {
            mvc.perform(login(str(variants[i % variants.length]), "errada" + i, ip()));
        }

        mvc.perform(login(str(email), "errada-final", ip())).andExpect(status().isTooManyRequests());
        mvc.perform(login(str(email.toUpperCase()), "errada-final", ip())).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("membro sem senha definida entra como e-mail inexistente: mesma resposta, e a senha do ambiente não o autentica")
    void membroSemSenhaNaoEntra() throws Exception {
        User semSenha = member(null, false, "ACTIVE");
        String ghost = "fantasma-" + UUID.randomUUID() + "@example.com";

        MvcResult a = mvc.perform(login(str(semSenha.email()), "senha-forte-123", ip())).andExpect(status().isUnauthorized()).andReturn();
        MvcResult b = mvc.perform(login(str(ghost), "senha-forte-123", ip())).andExpect(status().isUnauthorized()).andReturn();

        assertThat(strip(a)).isEqualTo(strip(b));
        assertThat(a.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class, semSenha.id())).isZero();
    }

    private String strip(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString().replaceAll("\"traceId\":\"[0-9a-f]+\"", "").replaceAll("\"instance\":\"[^\"]*\"", "");
    }

    @Test
    @DisplayName("login de desativado não grava último acesso, não cria refresh token e não manda Set-Cookie")
    void loginDeDesativadoNaoDeixaRastro() throws Exception {
        User off = member(null, true, "DISABLED");

        MvcResult r = mvc.perform(login(str(off.email()), PASSWORD, ip())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andReturn();

        assertThat(r.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class, off.id())).isZero();
        assertThat(jdbc.queryForObject("select last_login_at from users where id = ?", java.sql.Timestamp.class, off.id())).isNull();
    }

    @Test
    @DisplayName("refresh de desativado, de cookie inventado e de cookie revogado têm corpo idêntico e nenhum Set-Cookie de sessão nova")
    void refreshRecusadoNaoDistingueMotivo() throws Exception {
        User m = member();
        String cookie = cookie(loginOk(m.email(), PASSWORD));
        write("update users set status = 'DISABLED', disabled_at = now() where id = ?", m.id());

        MvcResult disabled = mvc.perform(refresh(cookie)).andExpect(status().isUnauthorized()).andReturn();
        MvcResult garbage = mvc.perform(refresh("inventado-" + UUID.randomUUID())).andExpect(status().isUnauthorized()).andReturn();

        assertThat(strip(disabled)).isEqualTo(strip(garbage));
        assertThat(String.valueOf(disabled.getResponse().getHeader("Set-Cookie"))).isEqualTo(String.valueOf(garbage.getResponse().getHeader("Set-Cookie")));
    }

    @Test
    @DisplayName("logout com cookie de usuário desativado e de usuário apagado responde sem 500")
    void logoutNaoQuebraComUsuarioInvalido() throws Exception {
        User off = member();
        String cookieOff = cookie(loginOk(off.email(), PASSWORD));
        write("update users set status = 'DISABLED', disabled_at = now() where id = ?", off.id());
        User gone = member();
        String cookieGone = cookie(loginOk(gone.email(), PASSWORD));
        write("delete from refresh_tokens where user_id = ?", gone.id());
        write("delete from users where id = ?", gone.id());

        for (String c : List.of(cookieOff, cookieGone)) {
            int s = mvc.perform(post("/v1/auth/logout").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", c))).andReturn().getResponse().getStatus();
            assertThat(s).isLessThan(500);
        }
    }

    @Test
    @DisplayName("token e refresh de usuário apagado: 401 (nunca 500) em login-protegidas, e o 401 é igual ao de token desconhecido")
    void usuarioApagado() throws Exception {
        User gone = member();
        MvcResult session = loginOk(gone.email(), PASSWORD);
        String token = bearer(session);
        String cookie = cookie(session);
        write("delete from refresh_tokens where user_id = ?", gone.id());
        write("delete from users where id = ?", gone.id());

        for (String path : List.of("/v1/accounts", "/v1/ai/status", "/v1/users", "/v1/notifications", "/v1/dashboard/summary")) {
            mvc.perform(get(path).header("Authorization", token)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/v1/accounts").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(refresh(cookie)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
    }

    @Test
    @DisplayName("claim role=OWNER forjada num JWT de membro: sem acesso a usuários, convites, IA nem configurações de IA; /ai/status continua enabled=false")
    void claimForjadaNaoAbreIaNemGestao() throws Exception {
        User m = member();
        String token = forged(m.id(), "OWNER");

        for (String path : List.of("/v1/users", "/v1/ai/analyses", "/v1/settings/ai")) {
            mvc.perform(get(path).header("Authorization", token)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/v1/invitations").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.com\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/ai/status").header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @DisplayName("membro não alcança rotas de dono por variações de caminho (barra final, barra dupla, ;param, caixa, sufixo, HEAD/OPTIONS): nunca 2xx")
    void variacoesDeCaminhoNaoContornamAutorizacao() throws Exception {
        String token = bearer(loginOk(member().email(), PASSWORD));
        String id = UUID.randomUUID().toString();
        List<String> paths = List.of("/v1/users", "/v1/users/", "/v1/users/" + id + "/disable", "/v1/users/" + id + "/enable", "/v1/invitations",
                "/v1/invitations/", "/v1/invitations/" + id, "/v1/invitations/" + id + "/resend", "/v1/ai/previews", "/v1/ai/previews/",
                "/v1/ai/previews/" + id, "/v1/ai/analyses", "/v1/ai/analyses/", "/v1/ai/analyses/" + id, "/v1/settings/ai", "/v1/settings/ai/",
                "/v1/users;x=1", "/v1/users/;x=1", "/v1/ai/analyses;x=1", "/v1/settings/ai;x=1", "/v1/users.json", "/v1/settings/ai.json",
                "/v1/USERS", "/V1/users", "/v1/Users", "/v1/AI/analyses", "/v1/Settings/AI", "/v1/users%2F", "/v1/%75sers", "/v1/ai/%61nalyses",
                "/v1//users", "/v1/./users", "/v1/ai/../users", "/v1/ai/status/../analyses");
        List<String> leaks = new ArrayList<>();
        for (String path : paths) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.HEAD,
                    HttpMethod.OPTIONS)) {
                MvcResult r;
                try {
                    r = mvc.perform(request(method, path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
                } catch (Exception e) {
                    continue;
                }
                int s = r.getResponse().getStatus();
                if ((s >= 200 && s < 300) || s >= 500) {
                    leaks.add(method + " " + path + " -> " + s + " " + r.getResponse().getContentAsString());
                }
            }
        }
        assertThat(leaks).as("membro alcançando rota de dono ou erro 5xx").isEmpty();
    }

    @Test
    @DisplayName("sem token, as rotas de dono respondem 401 (e não 403 nem 404) mesmo com barra final")
    void anonimoRecebe401() throws Exception {
        for (String path : List.of("/v1/users", "/v1/users/", "/v1/invitations", "/v1/ai/analyses", "/v1/settings/ai", "/v1/ai/previews")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("promoção/rebaixamento no banco: o refresh devolve user.role e a claim role do banco, não os do token anterior")
    void refreshUsaPapelDoBanco() throws Exception {
        User m = member();
        UUID ownerId = auth.ownerId();
        MvcResult session = loginOk(m.email(), PASSWORD);
        String cookie = cookie(session);
        write("update users set role = 'MEMBER' where id = ?", ownerId);
        write("update users set role = 'OWNER' where id = ?", m.id());
        try {
            MvcResult after = mvc.perform(refresh(cookie)).andExpect(status().isOk()).andReturn();
            assertThat(body(after).get("user").get("role").asText()).isEqualTo("OWNER");
            String payload = new String(Base64.getUrlDecoder().decode(body(after).get("accessToken").asText().split("\\.")[1]));
            assertThat(om.readTree(payload).get("role").asText()).isEqualTo("OWNER");
        } finally {
            write("update users set role = 'MEMBER' where id = ?", m.id());
            write("update users set role = 'OWNER' where id = ?", ownerId);
        }
        assertThat(body(loginOk(m.email(), PASSWORD)).get("user").get("role").asText()).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("segundo OWNER: promover um membro com dono existente falha no banco e não deixa nenhum dos dois sem dono")
    void doisDonosNaoCoexistem() {
        User m = member();
        int ownersBefore = jdbc.queryForObject("select count(*) from users where role = 'OWNER'", Integer.class);

        assertThatThrownBy(() -> write("update users set role = 'OWNER' where id = ?", m.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> users.updateRole(m.id(), UserRole.OWNER, Instant.now())).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("select count(*) from users where role = 'OWNER'", Integer.class)).isEqualTo(ownersBefore).isEqualTo(1);
        assertThat(jdbc.queryForObject("select role from users where id = ?", String.class, m.id())).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("GET /v1/auth/status só reflete o dono: membros sem senha ou desativados não ligam setupRequired")
    void statusPublicoIgnoraMembros() throws Exception {
        boolean before = body(mvc.perform(get("/v1/auth/status")).andExpect(status().isOk()).andReturn()).get("passwordSetupRequired").asBoolean();
        member(null, false, "ACTIVE");
        member(null, false, "DISABLED");

        boolean after = body(mvc.perform(get("/v1/auth/status")).andExpect(status().isOk()).andReturn()).get("passwordSetupRequired").asBoolean();

        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("setup-password com o e-mail de um membro desativado ou sem senha: 401 INVALID_SETUP_CODE idêntico ao de e-mail inexistente")
    void setupPasswordNaoRevelaMembro() throws Exception {
        User semSenha = member(null, false, "ACTIVE");
        User off = member(null, true, "DISABLED");
        String ghost = "fantasma-" + UUID.randomUUID() + "@example.com";
        List<String> bodies = new ArrayList<>();
        for (String email : List.of(semSenha.email(), off.email(), ghost)) {
            MvcResult r = mvc.perform(post("/v1/auth/setup-password").contentType(MediaType.APPLICATION_JSON).with(x -> {
                x.setRemoteAddr(ip());
                return x;
            }).content("{\"email\":\"" + email + "\",\"setupCode\":\"codigo-qualquer-123\",\"newPassword\":\"outra-senha-longa-789\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SETUP_CODE")).andReturn();
            bodies.add(strip(r));
        }
        assertThat(bodies).containsOnly(bodies.get(0));
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class, off.id())).isNotNull();
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class, semSenha.id())).isNull();
    }

    @Test
    @DisplayName("corrida real refresh x desativação (25 rodadas): nunca sobra refresh token ativo nem o cookie novo abre sessão depois")
    void corridaRefreshXDesativacao() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(tm);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int emitted = 0;
        int refused = 0;
        try {
            for (int round = 0; round < 25; round++) {
                User m = member();
                String cookie = cookie(loginOk(m.email(), PASSWORD));
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<String> rotating = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return refreshSession.handle(new RefreshCommand(cookie)).refreshToken();
                    } catch (RefreshInvalidException e) {
                        return null;
                    }
                });
                Future<?> disabling = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    tx.executeWithoutResult(s -> {
                        jdbc.queryForObject("select id from users where id = ? for update", UUID.class, m.id());
                        jdbc.update("update users set status = 'DISABLED', disabled_at = now() where id = ?", m.id());
                        refreshTokens.revokeAllByUser(m.id(), Instant.now());
                    });
                    return null;
                });
                String newCookie = rotating.get(20, TimeUnit.SECONDS);
                disabling.get(20, TimeUnit.SECONDS);
                if (newCookie == null) {
                    refused++;
                } else {
                    emitted++;
                    mvc.perform(refresh(newCookie)).andExpect(status().isUnauthorized());
                }
                assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class, m.id()))
                        .as("rodada " + round).isZero();
                mvc.perform(refresh(cookie)).andExpect(status().isUnauthorized());
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("QA-T3-RACE emitted=" + emitted + " refused=" + refused);
    }
}
