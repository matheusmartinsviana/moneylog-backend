package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "moneylog.export.rate-limit.max-requests=6")
@DisplayName("QA T190: limites de taxa da busca (120/min) e da exportação (6/min) independentes")
class RedesignFase0SearchLimitsQaIT {

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

    private int search(Actor a, String q) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", q).header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn().getResponse().getStatus();
    }

    private int export(Actor a) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/v1/exports/transactions.csv").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("120 buscas não gastam a cota da exportação (6) e 6 exportações não gastam a da busca; 401 não consome; q inválido consome; janela deslizante")
    void contadoresIndependentes() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        CardQaWorld world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        Actor a = world.new Actor("lim");
        Actor b = world.new Actor("limb");

        for (int i = 0; i < 200; i++) {
            assertThat(mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", "abc")).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        for (int i = 0; i < 60; i++) {
            assertThat(search(a, "abc")).isEqualTo(200);
            assertThat(search(a, "a")).as("q inválido responde 400 e consome").isEqualTo(400);
        }
        MvcResult blocked = mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", "abc").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        String retry = blocked.getResponse().getHeader("Retry-After");
        assertThat(retry).isNotNull();
        assertThat(Long.parseLong(retry)).isBetween(0L, 60L);
        assertThat(blocked.getResponse().getHeader("Cache-Control")).as("429 não deve ser cacheável").isNotNull();
        List<Integer> exports = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            exports.add(export(a));
        }
        assertThat(exports).containsExactly(200, 200, 200, 200, 200, 200, 429);
        assertThat(search(a, "abc")).isEqualTo(429);
        assertThat(search(b, "abc")).isEqualTo(200);
        for (int i = 0; i < 6; i++) {
            assertThat(export(b)).isEqualTo(200);
        }
        assertThat(export(b)).isEqualTo(429);
        for (int i = 0; i < 100; i++) {
            assertThat(search(b, "abc")).as("busca " + i + " de b").isEqualTo(200);
        }
        clock.advance(Duration.ofSeconds(30));
        a.bearer = world.auth.bearer(a.id);
        assertThat(search(a, "abc")).isEqualTo(429);
        clock.advance(Duration.ofSeconds(31));
        a.bearer = world.auth.bearer(a.id);
        assertThat(search(a, "abc")).isEqualTo(200);
        assertThat(export(a)).isEqualTo(200);
    }
}
