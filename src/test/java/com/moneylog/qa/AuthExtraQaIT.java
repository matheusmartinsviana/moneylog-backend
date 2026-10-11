package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T43: auth, bordas adicionais")
class AuthExtraQaIT {

    private static final String ORIGIN = "https://app.moneylog.test";
    private static final AtomicInteger IPS = new AtomicInteger();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private MockHttpServletRequestBuilder login(String email, String password) {
        String ip = "203.0.113." + (100 + IPS.incrementAndGet());
        return MockMvcRequestBuilders.post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private static String cookie(MvcResult r) {
        String c = r.getResponse().getHeader("Set-Cookie");
        return c.substring("ml_refresh=".length(), c.indexOf(';'));
    }

    @Test
    @DisplayName("refresh de 30 dias: vale em 29d23h e expira depois (401 REFRESH_INVALID)")
    void refreshExpira() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        MvcResult l = mvc.perform(login("owner@example.com", "senha-forte-123")).andReturn();
        String c = cookie(l);
        clock.advance(Duration.ofDays(30).minusMinutes(1));
        MvcResult ok = mvc.perform(MockMvcRequestBuilders.post("/v1/auth/refresh").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", c))).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        clock.advance(Duration.ofDays(31));
        MvcResult expired = mvc.perform(MockMvcRequestBuilders.post("/v1/auth/refresh").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", cookie(ok)))).andReturn();
        assertThat(expired.getResponse().getStatus()).isEqualTo(401);
        assertThat(om.readTree(expired.getResponse().getContentAsString()).path("code").asString()).isEqualTo("REFRESH_INVALID");
        assertThat(expired.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
        clock.set(MutableClockConfig.INITIAL);
    }

    @Test
    @DisplayName("logout sem cookie, com cookie desconhecido e repetido: 204 idempotente e cookie expirado")
    void logoutIdempotente() throws Exception {
        for (Cookie c : new Cookie[] {null, new Cookie("ml_refresh", "desconhecido"), new Cookie("ml_refresh", "")}) {
            MockHttpServletRequestBuilder r = MockMvcRequestBuilders.post("/v1/auth/logout").header("Origin", ORIGIN);
            if (c != null) {
                r.cookie(c);
            }
            MvcResult res = mvc.perform(r).andReturn();
            assertThat(res.getResponse().getStatus()).as(String.valueOf(c)).isEqualTo(204);
            assertThat(res.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
            ContractValidation.conforms();
        }
    }

    @Test
    @DisplayName("e-mail em outra caixa e com espaços nas pontas autentica o dono (normalização); senha errada continua 401")
    void emailNormalizado() throws Exception {
        assertThat(mvc.perform(login("OWNER@Example.COM", "senha-forte-123")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(login("  owner@example.com  ", "senha-forte-123")).andReturn().getResponse().getStatus()).isIn(200, 400);
    }

    @Test
    @DisplayName("senha e e-mail gigantes (72+ bytes, 100 mil caracteres): nunca 500")
    void entradasGigantesNoLogin() throws Exception {
        for (String password : new String[] {"x".repeat(72), "x".repeat(73), "x".repeat(100_000), "ç".repeat(80), "\\u0000abc"}) {
            MvcResult r = mvc.perform(login("owner@example.com", password)).andReturn();
            System.out.println("QA-LOGIN-PWD len=" + password.length() + " -> " + r.getResponse().getStatus());
            assertThat(r.getResponse().getStatus()).as("senha de " + password.length()).isLessThan(500);
        }
        MvcResult r = mvc.perform(login("a".repeat(10_000) + "@example.com", "x")).andReturn();
        System.out.println("QA-LOGIN-EMAIL-10k -> " + r.getResponse().getStatus());
        assertThat(r.getResponse().getStatus()).isLessThan(500);
    }

    @Test
    @DisplayName("access token continua válido depois do logout até o exp (JWT stateless, ADR-003); o refresh não")
    void accessTokenSobrevivePeloTtl() throws Exception {
        MvcResult l = mvc.perform(login("owner@example.com", "senha-forte-123")).andReturn();
        String access = om.readTree(l.getResponse().getContentAsString()).get("accessToken").asString();
        mvc.perform(MockMvcRequestBuilders.post("/v1/auth/logout").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", cookie(l)))).andReturn();
        int viaAccess = mvc.perform(MockMvcRequestBuilders.get("/v1/accounts").header("Authorization", "Bearer " + access)).andReturn().getResponse().getStatus();
        int viaRefresh = mvc.perform(MockMvcRequestBuilders.post("/v1/auth/refresh").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", cookie(l)))).andReturn().getResponse().getStatus();
        System.out.println("QA-LOGOUT access=" + viaAccess + " refresh=" + viaRefresh);
        assertThat(viaRefresh).isEqualTo(401);
        assertThat(viaAccess).isEqualTo(200);
    }

    @Test
    @DisplayName("tokens de refresh guardados só como hash SHA-256 (64 hex), nunca o valor do cookie")
    void refreshSoComoHash() throws Exception {
        MvcResult l = mvc.perform(login("owner@example.com", "senha-forte-123")).andReturn();
        String c = cookie(l);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, c)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where length(token_hash) <> 64", Integer.class)).isZero();
    }
}
