package com.moneylog.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.auth.app.OwnerCreated;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.EnsureOwnerCommand;
import com.moneylog.infra.security.OwnerBootstrap;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.infra.web.WebFoundationSamples;

@IntegrationTest
@Import({MutableClockConfig.class, WebFoundationSamples.class})
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Auth de usuário único (ADR-003)")
class AuthIT {

    private static final String ORIGIN = "https://app.moneylog.test";
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String PASSWORD = "senha-forte-123";
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ItAuth auth;
    @Autowired
    private EnsureOwnerUseCase ensureOwner;
    @Autowired
    private ApplicationEvents events;
    @Autowired
    private org.springframework.context.ApplicationEventPublisher publisher;

    @Autowired
    private com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCase prepareFirstAccess;

    private OwnerBootstrap bootstrap(String email) {
        var props = new com.moneylog.infra.security.MoneylogOwnerProperties(email, null, null, false, null, null,
                Duration.ofHours(24));
        return new OwnerBootstrap(ensureOwner, prepareFirstAccess, props, publisher, false);
    }

    @AfterEach
    void restaurar() {
        clock.set(MutableClockConfig.INITIAL);
        ensureOwner.handle(new EnsureOwnerCommand(OWNER_EMAIL));
    }

    private static String novoIp() {
        return "198.51.100." + IPS.incrementAndGet();
    }

    private MockHttpServletRequestBuilder login(String email, String password, String ip) {
        return post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }).content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private MvcResult doLogin() throws Exception {
        return mockMvc.perform(login(OWNER_EMAIL, PASSWORD, novoIp())).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn();
    }

    private static String cookieValue(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("ml_refresh=");
        return setCookie.substring("ml_refresh=".length(), setCookie.indexOf(';'));
    }

    private MockHttpServletRequestBuilder refresh(String cookie, String origin) {
        MockHttpServletRequestBuilder request = post("/v1/auth/refresh").header("Origin", origin);
        return cookie == null ? request : request.cookie(new Cookie("ml_refresh", cookie));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Nested
    @DisplayName("CA1 e CA2: o dono é a única linha de users")
    class Dono {

        @Test
        @DisplayName("sobe com exatamente um usuário com o e-mail do ambiente e não duplica ao subir de novo")
        void deveTerUmSoDono() {
            assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select email from users", String.class)).isEqualTo(OWNER_EMAIL);

            bootstrap(OWNER_EMAIL).run(null);

            assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
            assertThat(events.stream(OwnerCreated.class)).isEmpty();
        }

        @Test
        @DisplayName("trocar o e-mail no ambiente mantém o id, não publica OwnerCreated e mascara o e-mail no log")
        void deveTrocarOEmailMantendoOId(CapturedOutput output) throws Exception {
            UUID id = auth.ownerId();
            String bearer = auth.ownerBearer();

            bootstrap("novo@example.com").run(null);

            assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select id from users", UUID.class)).isEqualTo(id);
            assertThat(jdbc.queryForObject("select email from users", String.class)).isEqualTo("novo@example.com");
            assertThat(events.stream(OwnerCreated.class)).isEmpty();
            assertThat(output.getAll()).contains("o***@example.com").contains("n***@example.com")
                    .doesNotContain("novo@example.com").doesNotContain("owner@example.com");
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/test/me")
                            .header("Authorization", bearer))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(id.toString()));
            mockMvc.perform(login("novo@example.com", PASSWORD, novoIp())).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("CA3 e CA4: login")
    class Login {

        @Test
        @DisplayName("200 com accessToken, expiresAt = agora + 15 min e cookie HttpOnly, Secure, SameSite=Strict, Path=/v1/auth")
        void deveLogar() throws Exception {
            MvcResult result = doLogin();

            assertThat(json(result).get("accessToken").asString()).isNotBlank();
            assertThat(Instant.parse(json(result).get("expiresAt").asString()))
                    .isEqualTo(MutableClockConfig.INITIAL.plus(Duration.ofMinutes(15)));
            String cookie = result.getResponse().getHeader("Set-Cookie");
            assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/v1/auth")
                    .contains("Max-Age=2592000");
            assertThat(jdbc.queryForObject("select token_hash from refresh_tokens where token_hash = ?", String.class,
                    com.moneylog.core.auth.app.RefreshTokenHash.of(cookieValue(result)))).hasSize(64);
            assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class,
                    cookieValue(result))).isZero();
        }

        @Test
        @DisplayName("e-mail ou senha errados: 401 INVALID_CREDENTIALS com o mesmo corpo")
        void deveRecusarCredenciaisErradas() throws Exception {
            String ip = novoIp();
            MvcResult wrongPassword = mockMvc.perform(login(OWNER_EMAIL, "errada", ip)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andExpect(ContractValidation.conforms()).andReturn();
            MvcResult wrongEmail = mockMvc.perform(login("outro@example.com", PASSWORD, ip)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andExpect(ContractValidation.conforms()).andReturn();

            assertThat(withoutTrace(wrongPassword)).isEqualTo(withoutTrace(wrongEmail));
        }

        private String withoutTrace(MvcResult result) throws Exception {
            return objectMapper.readTree(result.getResponse().getContentAsString()).toString()
                    .replaceAll("\"traceId\":\"[0-9a-f]+\"", "");
        }

        @Test
        @DisplayName("5 falhas do mesmo IP: a 6ª tentativa, mesmo com a senha certa, recebe 429 com Retry-After")
        void deveLimitarTentativas() throws Exception {
            String ip = novoIp();
            for (int i = 0; i < 5; i++) {
                mockMvc.perform(login(OWNER_EMAIL, "errada" + i, ip)).andExpect(status().isUnauthorized());
            }

            mockMvc.perform(login(OWNER_EMAIL, PASSWORD, ip)).andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                    .andExpect(header().string("Retry-After", "900")).andExpect(ContractValidation.conforms());
            mockMvc.perform(login(OWNER_EMAIL, PASSWORD, novoIp())).andExpect(status().isOk());
            clock.advance(Duration.ofMinutes(16));
            mockMvc.perform(login(OWNER_EMAIL, PASSWORD, ip)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("S-01: falhas de IPs diferentes contra a mesma conta bloqueiam por conta, com o mesmo 429 exista ou não o e-mail")
        void deveLimitarPorConta() throws Exception {
            for (int i = 0; i < 10; i++) {
                mockMvc.perform(login("alvo-inexistente@example.com", "errada" + i, novoIp())).andExpect(status().isUnauthorized());
            }

            mockMvc.perform(login("alvo-inexistente@example.com", "errada", novoIp())).andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS")).andExpect(header().exists("Retry-After"))
                    .andExpect(ContractValidation.conforms());
            mockMvc.perform(login("ALVO-INEXISTENTE@example.com", "errada", novoIp())).andExpect(status().isTooManyRequests());
            mockMvc.perform(login("outro-alvo@example.com", "errada", novoIp())).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("S-02: rajada paralela do mesmo IP com senha errada passa de no máximo 5 tentativas até o bcrypt")
        void deveSerAtomicoNaRajada() throws Exception {
            String ip = novoIp();
            ExecutorService pool = Executors.newFixedThreadPool(20);
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int n = i;
                Callable<Integer> call = () -> mockMvc.perform(login("rajada-" + n + "@example.com", "errada", ip)).andReturn()
                        .getResponse().getStatus();
                results.add(pool.submit(call));
            }
            int unauthorized = 0;
            int tooMany = 0;
            for (Future<Integer> result : results) {
                int code = result.get();
                if (code == 401) {
                    unauthorized++;
                } else if (code == 429) {
                    tooMany++;
                }
            }
            pool.shutdown();

            assertThat(unauthorized).isEqualTo(5);
            assertThat(tooMany).isEqualTo(35);
        }

        @Test
        @DisplayName("S-08: falha e sucesso viram eventos com e-mail mascarado, sem a senha no log")
        void deveRegistrarEventosSemSenha(CapturedOutput output) throws Exception {
            String ip = novoIp();
            mockMvc.perform(login(OWNER_EMAIL, "senha-errada-que-nao-pode-vazar", ip)).andExpect(status().isUnauthorized());
            mockMvc.perform(login(OWNER_EMAIL, PASSWORD, ip)).andExpect(status().isOk());

            assertThat(output.getAll()).contains("event=login_failed", "event=login_succeeded", "ip=" + ip, "email=o***@example.com")
                    .doesNotContain("senha-errada-que-nao-pode-vazar").doesNotContain(PASSWORD).doesNotContain(OWNER_EMAIL);
        }

        @Test
        @DisplayName("com trusted-proxies vazio, X-Forwarded-For diferentes do mesmo IP remoto contam como o mesmo IP")
        void deveIgnorarForwardedForSemProxyConfiavel() throws Exception {
            String ip = novoIp();
            for (int i = 0; i < 5; i++) {
                int hop = i;
                mockMvc.perform(login(OWNER_EMAIL, "errada", ip).header("X-Forwarded-For", "203.0.113." + hop))
                        .andExpect(status().isUnauthorized());
            }

            mockMvc.perform(login(OWNER_EMAIL, PASSWORD, ip).header("X-Forwarded-For", "203.0.113.250"))
                    .andExpect(status().isTooManyRequests());
        }

        @Test
        @DisplayName("corpo sem e-mail ou com e-mail inválido: 400 VALIDATION_FAILED")
        void deveValidarOCorpo() throws Exception {
            mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nao-e-email\",\"password\":\"x\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("email")).andExpect(ContractValidation.responseOnly());
        }
    }

    @Nested
    @DisplayName("CA6: refresh rotativo")
    class Refresh {

        @Test
        @DisplayName("rotaciona: novo access token, cookie novo e o antigo fica substituído")
        void deveRotacionar() throws Exception {
            String first = cookieValue(doLogin());

            MvcResult result = mockMvc.perform(refresh(first, ORIGIN)).andExpect(status().isOk())
                    .andExpect(ContractValidation.conforms()).andReturn();

            String second = cookieValue(result);
            assertThat(second).isNotEqualTo(first);
            assertThat(json(result).get("accessToken").asString()).isNotBlank();
            assertThat(jdbc.queryForObject("select replaced_by is not null and replaced_at is not null from refresh_tokens where token_hash = ?",
                    Boolean.class, com.moneylog.core.auth.app.RefreshTokenHash.of(first))).isTrue();
        }

        @Test
        @DisplayName("antigo reapresentado 10 s depois: 200 com par novo da mesma família, e o sucessor anterior segue valendo")
        void deveTolerarDentroDaJanela() throws Exception {
            String first = cookieValue(doLogin());
            String second = cookieValue(mockMvc.perform(refresh(first, ORIGIN)).andExpect(status().isOk()).andReturn());
            clock.advance(Duration.ofSeconds(10));

            MvcResult again = mockMvc.perform(refresh(first, ORIGIN)).andExpect(status().isOk()).andReturn();

            assertThat(cookieValue(again)).isNotEqualTo(second);
            assertThat(jdbc.queryForObject("select count(distinct family_id) from refresh_tokens where token_hash in (?, ?, ?)", Integer.class,
                    com.moneylog.core.auth.app.RefreshTokenHash.of(first), com.moneylog.core.auth.app.RefreshTokenHash.of(second),
                    com.moneylog.core.auth.app.RefreshTokenHash.of(cookieValue(again)))).isEqualTo(1);
            mockMvc.perform(refresh(second, ORIGIN)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("antigo reapresentado 31 s depois: 401 REFRESH_INVALID e a família inteira é revogada")
        void deveRevogarAFamiliaNoReuso() throws Exception {
            String first = cookieValue(doLogin());
            String second = cookieValue(mockMvc.perform(refresh(first, ORIGIN)).andExpect(status().isOk()).andReturn());
            clock.advance(Duration.ofSeconds(31));

            mockMvc.perform(refresh(first, ORIGIN)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_INVALID")).andExpect(ContractValidation.conforms());

            mockMvc.perform(refresh(second, ORIGIN)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_INVALID"));
            assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where family_id = (select family_id from refresh_tokens where token_hash = ?) and revoked_at is null",
                    Integer.class, com.moneylog.core.auth.app.RefreshTokenHash.of(first))).isZero();
        }

        @Test
        @DisplayName("revogado por logout é recusado em qualquer momento, inclusive dentro da janela")
        void naoDeveTolerarRevogado() throws Exception {
            String first = cookieValue(doLogin());
            String second = cookieValue(mockMvc.perform(refresh(first, ORIGIN)).andExpect(status().isOk()).andReturn());
            mockMvc.perform(post("/v1/auth/logout").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", second)))
                    .andExpect(status().isNoContent());
            clock.advance(Duration.ofSeconds(2));

            mockMvc.perform(refresh(first, ORIGIN)).andExpect(status().isUnauthorized());
            mockMvc.perform(refresh(second, ORIGIN)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("duas requisições em paralelo com o mesmo cookie recebem 200 e a família continua válida")
        void deveSuportarRefreshEmParalelo() throws Exception {
            String first = cookieValue(doLogin());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<MvcResult> call = () -> mockMvc.perform(refresh(first, ORIGIN)).andReturn();
                List<Future<MvcResult>> results = pool.invokeAll(List.of(call, call));

                for (Future<MvcResult> future : results) {
                    assertThat(future.get().getResponse().getStatus()).isEqualTo(200);
                }
                assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where family_id = (select family_id from refresh_tokens where token_hash = ?) and revoked_at is null",
                        Integer.class, com.moneylog.core.auth.app.RefreshTokenHash.of(first))).isEqualTo(3);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("Origin fora da lista (ou ausente): 403 ORIGIN_NOT_ALLOWED e nada é rotacionado")
        void deveRecusarOrigemInvalida() throws Exception {
            String first = cookieValue(doLogin());

            mockMvc.perform(refresh(first, "https://evil.test")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED")).andExpect(ContractValidation.conforms());
            mockMvc.perform(post("/v1/auth/refresh").cookie(new Cookie("ml_refresh", first))).andExpect(status().isForbidden());

            assertThat(jdbc.queryForObject("select replaced_at is null from refresh_tokens where token_hash = ?", Boolean.class,
                    com.moneylog.core.auth.app.RefreshTokenHash.of(first))).isTrue();
        }

        @Test
        @DisplayName("sem cookie ou com cookie desconhecido: 401 REFRESH_INVALID e o cookie é expirado")
        void deveRecusarSemCookie() throws Exception {
            mockMvc.perform(refresh(null, ORIGIN)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_INVALID")).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
            mockMvc.perform(refresh("desconhecido", ORIGIN)).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("CA7: logout")
    class Logout {

        @Test
        @DisplayName("204, cookie expirado e família revogada no banco")
        void deveEncerrarASessao() throws Exception {
            String cookie = cookieValue(doLogin());

            mockMvc.perform(post("/v1/auth/logout").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", cookie)))
                    .andExpect(status().isNoContent()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")))
                    .andExpect(ContractValidation.conforms());

            assertThat(jdbc.queryForObject("select revoked_at is not null from refresh_tokens where token_hash = ?", Boolean.class,
                    com.moneylog.core.auth.app.RefreshTokenHash.of(cookie))).isTrue();
        }

        @Test
        @DisplayName("Origin fora da lista: 403 e nada é revogado")
        void naoDeveRevogarComOrigemInvalida() throws Exception {
            String cookie = cookieValue(doLogin());

            mockMvc.perform(post("/v1/auth/logout").header("Origin", "https://evil.test").cookie(new Cookie("ml_refresh", cookie)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));

            assertThat(jdbc.queryForObject("select revoked_at is null from refresh_tokens where token_hash = ?", Boolean.class,
                    com.moneylog.core.auth.app.RefreshTokenHash.of(cookie))).isTrue();
        }
    }

    @Nested
    @DisplayName("CA8: rotas protegidas")
    class RotasProtegidas {

        private MockHttpServletRequestBuilder me(String authorization) {
            MockHttpServletRequestBuilder request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/test/me");
            return authorization == null ? request : request.header("Authorization", authorization);
        }

        @Test
        @DisplayName("sem token, com lixo ou com assinatura inválida: 401 UNAUTHENTICATED em ProblemDetail")
        void deveRecusarSemTokenValido() throws Exception {
            String forged = auth.ownerBearer().substring(0, auth.ownerBearer().length() - 4) + "AAAA";
            for (String authorization : new String[] {null, "Bearer lixo", forged}) {
                mockMvc.perform(me(authorization)).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.traceId").isNotEmpty())
                        .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())))
                        .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/problem+json")));
            }
        }

        @Test
        @DisplayName("token expirado (relógio avançado) é recusado; token válido entrega o userId ao controller")
        void deveRecusarExpiradoEAceitarValido() throws Exception {
            String bearer = auth.ownerBearer();

            mockMvc.perform(me(bearer)).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(auth.ownerId().toString()));
            clock.advance(Duration.ofMinutes(15).plusSeconds(1));
            mockMvc.perform(me(bearer)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Nested
    @DisplayName("S-86: IP atrás de proxy confiável")
    @TestPropertySource(properties = "moneylog.security.trusted-proxies=10.0.0.0/8")
    class ProxyConfiavel {

        @Autowired
        private MockMvc mvc;

        private MockHttpServletRequestBuilder viaProxy(String clientIp, String password) {
            return post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).header("X-Forwarded-For", clientIp)
                    .with(request -> {
                        request.setRemoteAddr("10.1.1.1");
                        return request;
                    }).content("{\"email\":\"" + OWNER_EMAIL + "\",\"password\":\"" + password + "\"}");
        }

        @Test
        @DisplayName("dois clientes diferentes têm limites separados")
        void deveSepararClientesPeloForwardedFor() throws Exception {
            for (int i = 0; i < 5; i++) {
                mvc.perform(viaProxy("203.0.113.1", "errada")).andExpect(status().isUnauthorized());
            }

            mvc.perform(viaProxy("203.0.113.1", PASSWORD)).andExpect(status().isTooManyRequests());
            mvc.perform(viaProxy("203.0.113.2", PASSWORD)).andExpect(status().isOk());
        }
    }
}
