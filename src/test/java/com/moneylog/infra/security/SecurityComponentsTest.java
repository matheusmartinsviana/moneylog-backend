package com.moneylog.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("Componentes de segurança (T22b)")
class SecurityComponentsTest {

    private static final String HASH = "$2y$10$gNmLDyeHDZs73ARf5G3FUeDlMce4ALfLmVEFPpSrTbGERe5xcTIXS";

    @Nested
    @DisplayName("hash BCrypt do dono (CA3)")
    class HashDoDono {

        @Test
        @DisplayName("lê o hash com '$' direto (inclusive entre aspas) e de um arquivo")
        void deveLerOHashDosDoisJeitos(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("owner.hash");
            Files.writeString(file, HASH + "\n");

            assertThat(OwnerPasswordHash.resolve(HASH, "").value()).isEqualTo(HASH);
            assertThat(OwnerPasswordHash.resolve("'" + HASH + "'", null).value()).isEqualTo(HASH);
            assertThat(OwnerPasswordHash.resolve("", file.toString()).value()).isEqualTo(HASH);
            assertThat(OwnerPasswordHash.resolve("lixo", file.toString()).cost()).isEqualTo(10);
        }

        @Test
        @DisplayName("hash corrompido pela interpolação do '$' ou ausente não sobe, sem imprimir o valor")
        void deveRecusarHashInvalido() {
            assertThatThrownBy(() -> OwnerPasswordHash.resolve("a.KxyzCORROMPIDO", null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("BCrypt").hasMessageNotContaining("CORROMPIDO");
            assertThatThrownBy(() -> OwnerPasswordHash.resolve(null, null)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> OwnerPasswordHash.resolve("", "/nao/existe")).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("o verificador confere a senha do dono e gasta o custo com o hash fictício")
        void deveVerificarSenha() {
            BcryptPasswordVerifier verifier = new BcryptPasswordVerifier(OwnerPasswordHash.resolve(HASH, null));

            assertThat(verifier.verifyOwner("senha-forte-123")).isTrue();
            assertThat(verifier.verifyOwner("errada")).isFalse();
            assertThatCode(() -> verifier.verifyDummy("qualquer")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("JWT_SECRET (CA9)")
    class SegredoDoJwt {

        @Test
        @DisplayName("exige no mínimo 32 bytes e não imprime o segredo")
        void deveExigir32Bytes() {
            assertThat(JwtSecret.toKey("x".repeat(32)).getAlgorithm()).isEqualTo("HmacSHA256");
            assertThatThrownBy(() -> JwtSecret.toKey("curto-demais-segredo"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes")
                    .hasMessageNotContaining("curto-demais-segredo");
            assertThatThrownBy(() -> JwtSecret.toKey(null)).isInstanceOf(IllegalStateException.class);
            assertThatCode(() -> JwtSecret.toKey("ã".repeat(16))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("em prod recusa segredo de baixa entropia sem imprimi-lo e aceita o gerado por openssl")
        void deveRecusarBaixaEntropiaEmProd() {
            String[] weak = {"x".repeat(40), "abababababababababababababababab", "abcdefghijklmnopqrstuvwxyz0123456789",
                    "aaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbcccccccccccc", "umasenhaumasenhaumasenhaumasenhaumasenha"};
            for (String secret : weak) {
                assertThatThrownBy(() -> JwtSecret.toKey(secret, true)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("baixa entropia").hasMessageNotContaining(secret);
                assertThatCode(() -> JwtSecret.toKey(secret, false)).doesNotThrowAnyException();
            }
            String generated = "q7Vx2mKd9RtLw3ZpYb8HcNf5JgS1aEoUiT6yXz0BvDr4MkWn7QhPjCeA2sGlFu9O";
            assertThatCode(() -> JwtSecret.toKey(generated, true)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("cookie Secure (CA10, S-85)")
    class CookieSeguro {

        @Test
        @DisplayName("secure=true sempre passa; secure=false só com perfil dev e origens locais")
        void deveRestringirOPlanoB() {
            assertThatCode(() -> CookieSecurityGuard.validate(true, false, List.of("https://app.moneylog.com"))).doesNotThrowAnyException();
            assertThatCode(() -> CookieSecurityGuard.validate(false, true,
                    List.of("http://localhost:4200", "http://127.0.0.1:4200"))).doesNotThrowAnyException();

            assertThatThrownBy(() -> CookieSecurityGuard.validate(false, false, List.of("http://localhost:4200")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("dev");
            assertThatThrownBy(() -> CookieSecurityGuard.validate(false, true,
                    List.of("http://localhost:4200", "https://app.moneylog.com")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.moneylog.com");
            assertThatThrownBy(() -> CookieSecurityGuard.validate(false, true, List.of("não é uma url")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("o cookie sai com HttpOnly, Secure, SameSite=Strict e Path=/v1/auth; expirar zera o Max-Age")
        void deveMontarOCookie() {
            RefreshCookies cookies = new RefreshCookies(true);

            assertThat(cookies.issue("valor", Duration.ofDays(30)))
                    .contains("ml_refresh=valor").contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                    .contains("Path=/v1/auth").contains("Max-Age=2592000");
            assertThat(cookies.expire()).contains("ml_refresh=;").contains("Max-Age=0");
            assertThat(new RefreshCookies(false).issue("v", Duration.ofDays(1))).doesNotContain("Secure");
        }
    }

    @Nested
    @DisplayName("Origin (CSRF, CA6/CA7)")
    class Origem {

        private final OriginCheck check = new OriginCheck(List.of("https://App.moneylog.test/", "https://localhost:4200"));

        @Test
        @DisplayName("aceita origem da lista (sem diferenciar maiúsculas nem a barra final)")
        void deveAceitar() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Origin", "https://app.moneylog.test");
            assertThatCode(() -> check.ensureAllowed(request)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("recusa origem fora da lista e requisição sem Origin")
        void deveRecusar() {
            MockHttpServletRequest other = new MockHttpServletRequest();
            other.addHeader("Origin", "https://evil.test");
            assertThatThrownBy(() -> check.ensureAllowed(other)).isInstanceOf(OriginNotAllowedException.class);
            assertThatThrownBy(() -> check.ensureAllowed(new MockHttpServletRequest())).isInstanceOf(OriginNotAllowedException.class);
        }
    }

    @Nested
    @DisplayName("rate limit do login (CA4)")
    class RateLimit {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-10T12:00:00Z"));
        private final Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };

        private LoginRateLimiter limiter(int maxIp, int maxAccount, int maxEntries) {
            return new LoginRateLimiter(clock, new LoginRateLimiter.Settings(maxIp, Duration.ofMinutes(15), maxAccount,
                    Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofDays(30), maxEntries));
        }

        @Test
        @DisplayName("bloqueia na 6ª tentativa, informa o Retry-After e libera depois da janela")
        void deveBloquearEDesbloquear() {
            LoginRateLimiter limiter = new LoginRateLimiter(clock, 5, Duration.ofMinutes(15));
            for (int i = 0; i < 5; i++) {
                limiter.reserve("1.1.1.1", "a" + i);
                now.set(now.get().plusSeconds(10));
            }

            assertThatThrownBy(() -> limiter.reserve("1.1.1.1", "outra")).isInstanceOfSatisfying(TooManyAttemptsException.class,
                    e -> {
                        assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(900 - 50));
                        assertThat(e.scope()).isEqualTo(TooManyAttemptsException.Scope.IP);
                    });
            assertThatCode(() -> limiter.reserve("2.2.2.2", "x")).doesNotThrowAnyException();

            now.set(now.get().plus(Duration.ofMinutes(15)));
            assertThatCode(() -> limiter.reserve("1.1.1.1", "x")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("login bem-sucedido zera o contador do IP e da conta")
        void deveZerarNoSucesso() {
            LoginRateLimiter limiter = new LoginRateLimiter(clock, 2, Duration.ofMinutes(15));
            limiter.reserve("1.1.1.1", "a");
            LoginRateLimiter.Attempt attempt = limiter.reserve("1.1.1.1", "a");
            limiter.succeeded(attempt);

            assertThatCode(() -> limiter.reserve("1.1.1.1", "a")).doesNotThrowAnyException();
            assertThatCode(() -> limiter.reserve("1.1.1.1", "a")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("succeeded informa se o IP era novo: só o primeiro sucesso, e de novo após expirar a confiança")
        void deveInformarIpNovo() {
            LoginRateLimiter limiter = new LoginRateLimiter(clock, 5, Duration.ofMinutes(15));

            assertThat(limiter.succeeded(limiter.reserve("1.1.1.1", "a"))).isTrue();
            assertThat(limiter.succeeded(limiter.reserve("1.1.1.1", "a"))).isFalse();
            assertThat(limiter.succeeded(limiter.reserve("2.2.2.2", "a"))).isTrue();
            now.set(now.get().plus(Duration.ofDays(31)));
            assertThat(limiter.succeeded(limiter.reserve("1.1.1.1", "a"))).isTrue();
        }

        @Test
        @DisplayName("IPv6 é limitado pelo prefixo /64: trocar o final do endereço não zera o contador")
        void deveAgruparIpv6PorPrefixo64() {
            LoginRateLimiter limiter = limiter(3, 100, 100);
            limiter.reserve("2001:db8:1:2:aaaa::1", "a");
            limiter.reserve("2001:db8:1:2:bbbb::2", "a");
            limiter.reserve("2001:db8:1:2:cccc::3", "a");

            assertThatThrownBy(() -> limiter.reserve("2001:db8:1:2:dddd::4", "a")).isInstanceOf(TooManyAttemptsException.class);
            assertThatCode(() -> limiter.reserve("2001:db8:1:3::1", "a")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rajada paralela do mesmo IP não passa do limite: a reserva é atômica")
        void deveSerAtomicoSobConcorrencia() throws Exception {
            LoginRateLimiter limiter = limiter(5, 1000, 100);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(32);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger admitted = new java.util.concurrent.atomic.AtomicInteger();
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 200; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        limiter.reserve("9.9.9.9", "dono@example.com");
                        admitted.incrementAndGet();
                    } catch (TooManyAttemptsException blocked) {
                        return null;
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get();
            }
            pool.shutdown();

            assertThat(admitted.get()).isEqualTo(5);
        }

        @Test
        @DisplayName("limite por conta vale mesmo trocando de IP, com bloqueio progressivo e teto de 15 min")
        void deveBloquearPorContaComBackoff() {
            LoginRateLimiter limiter = limiter(100, 3, 100);
            for (int i = 0; i < 3; i++) {
                limiter.reserve("10.0.0." + i, "login:dono@example.com");
            }

            assertThatThrownBy(() -> limiter.reserve("10.0.0.50", "login:dono@example.com"))
                    .isInstanceOfSatisfying(TooManyAttemptsException.class, e -> {
                        assertThat(e.scope()).isEqualTo(TooManyAttemptsException.Scope.ACCOUNT);
                        assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(1));
                    });
            assertThatCode(() -> limiter.reserve("10.0.0.50", "login:outro@example.com")).doesNotThrowAnyException();

            now.set(now.get().plus(Duration.ofMinutes(1)));
            for (int i = 0; i < 3; i++) {
                limiter.reserve("10.0.1." + i, "login:dono@example.com");
            }
            assertThatThrownBy(() -> limiter.reserve("10.0.1.50", "login:dono@example.com"))
                    .isInstanceOfSatisfying(TooManyAttemptsException.class, e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(2)));

            Duration lock = Duration.ofMinutes(2);
            for (int round = 0; round < 6; round++) {
                now.set(now.get().plus(lock));
                limiter.reserve("10.0.2." + round, "login:dono@example.com");
                limiter.reserve("10.0.3." + round, "login:dono@example.com");
                limiter.reserve("10.0.4." + round, "login:dono@example.com");
                lock = lock.multipliedBy(2).compareTo(Duration.ofMinutes(15)) > 0 ? Duration.ofMinutes(15) : lock.multipliedBy(2);
            }
            assertThatThrownBy(() -> limiter.reserve("10.9.9.9", "login:dono@example.com"))
                    .isInstanceOfSatisfying(TooManyAttemptsException.class,
                            e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(15)));
        }

        @Test
        @DisplayName("IP que já autenticou não fica travado pelo bloqueio da conta, mas continua sujeito ao limite do IP")
        void deveDeixarIpConhecidoEntrarComContaBloqueada() {
            LoginRateLimiter limiter = limiter(100, 3, 100);
            limiter.succeeded(limiter.reserve("7.7.7.7", "login:dono@example.com"));
            for (int i = 0; i < 3; i++) {
                limiter.reserve("8.8.8." + i, "login:dono@example.com");
            }

            assertThatThrownBy(() -> limiter.reserve("8.8.8.99", "login:dono@example.com")).isInstanceOf(TooManyAttemptsException.class);
            assertThatCode(() -> limiter.reserve("7.7.7.7", "login:dono@example.com")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("o e-mail é normalizado: maiúsculas caem na mesma conta")
        void deveNormalizarOEmail() {
            LoginRateLimiter limiter = limiter(100, 2, 100);
            limiter.reserve("1.1.1.1", "login:Dono@Example.com");
            limiter.reserve("1.1.1.2", "login:dono@EXAMPLE.com");

            assertThatThrownBy(() -> limiter.reserve("1.1.1.3", "login:DONO@example.com")).isInstanceOf(TooManyAttemptsException.class);
        }

        @Test
        @DisplayName("o mapa tem teto de entradas e expurga as expiradas: milhares de IPs não crescem a memória")
        void deveLimitarEExpurgarOMapa() {
            LoginRateLimiter limiter = limiter(5, 10, 50);
            for (int i = 0; i < 5_000; i++) {
                limiter.reserve("10.1." + (i / 250) + "." + (i % 250), "login:u" + i + "@example.com");
            }
            assertThat(limiter.trackedEntries()).isLessThanOrEqualTo(50 + 50 + 1);

            now.set(now.get().plus(Duration.ofMinutes(20)));
            limiter.reserve("10.200.0.1", "login:novo@example.com");
            assertThat(limiter.trackedEntries()).isLessThanOrEqualTo(2);
        }

        @Test
        @DisplayName("chave de IP: IPv4 intacto, IPv4 mapeado em IPv6 vira IPv4 e lixo não quebra")
        void deveNormalizarChaves() {
            assertThat(LoginRateLimiter.ipKey("1.2.3.4")).isEqualTo("1.2.3.4");
            assertThat(LoginRateLimiter.ipKey("::ffff:1.2.3.4")).isEqualTo("1.2.3.4");
            assertThat(LoginRateLimiter.ipKey("2001:db8::1")).isEqualTo(LoginRateLimiter.ipKey("2001:db8::ffff"));
            assertThat(LoginRateLimiter.ipKey("nao-e-ip")).isEqualTo("nao-e-ip");
            assertThat(LoginRateLimiter.ipKey(null)).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("IP do cliente (CA5, S-86)")
    class IpDoCliente {

        private MockHttpServletRequest from(String remote, String forwarded) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr(remote);
            if (forwarded != null) {
                request.addHeader("X-Forwarded-For", forwarded);
            }
            return request;
        }

        @Test
        @DisplayName("sem proxies confiáveis o X-Forwarded-For é ignorado")
        void deveIgnorarOHeaderSemProxyConfiavel() {
            ClientIpResolver resolver = new ClientIpResolver(List.of());

            assertThat(resolver.resolve(from("10.0.0.5", "9.9.9.9"))).isEqualTo("10.0.0.5");
            assertThat(resolver.resolve(from("10.0.0.5", "8.8.8.8"))).isEqualTo("10.0.0.5");
        }

        @Test
        @DisplayName("com o remoto dentro do CIDR confiável, vale o cliente do X-Forwarded-For")
        void deveUsarOHeaderDeProxyConfiavel() {
            ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "2001:db8::/32"));

            assertThat(resolver.resolve(from("10.1.2.3", "9.9.9.9"))).isEqualTo("9.9.9.9");
            assertThat(resolver.resolve(from("10.1.2.3", "8.8.8.8"))).isEqualTo("8.8.8.8");
            assertThat(resolver.resolve(from("10.1.2.3", "forjado, 7.7.7.7, 10.9.9.9"))).isEqualTo("7.7.7.7");
            assertThat(resolver.resolve(from("10.1.2.3", null))).isEqualTo("10.1.2.3");
            assertThat(resolver.resolve(from("2001:db8::1", "6.6.6.6"))).isEqualTo("6.6.6.6");
        }

        @Test
        @DisplayName("remoto fora do CIDR confiável não pode forjar o header; nomes nunca são resolvidos")
        void naoDeveConfiarEmRemotoExterno() {
            ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "192.168.1.7"));

            assertThat(resolver.resolve(from("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
            assertThat(resolver.resolve(from("192.168.1.7", "1.2.3.4"))).isEqualTo("1.2.3.4");
            assertThat(resolver.resolve(from("192.168.1.8", "1.2.3.4"))).isEqualTo("192.168.1.8");
            assertThat(resolver.resolve(from("10.0.0.1", "evil.example"))).isEqualTo("evil.example");
        }

        @Test
        @DisplayName("CIDR inválido na configuração impede o boot")
        void deveRecusarCidrInvalido() {
            assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/99"))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/x"))).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("o e-mail do dono é mascarado no log")
    void deveMascararEmail() {
        assertThat(OwnerBootstrap.mask("dono@moneylog.test")).isEqualTo("d***@moneylog.test");
        assertThat(OwnerBootstrap.mask("semarroba")).isEqualTo("***");
    }
}
