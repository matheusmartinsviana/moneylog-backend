package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.note.util.NoteTestKit.Zip;
import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/** T113 (QA): limite de taxa de exportar/importar o vault com o limite REAL de produção (6 por minuto; o perfil de IT sobe para 100.000). */
@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "moneylog.export.rate-limit.max-requests=6")
@DisplayName("QA T113: limite de 6 exportações/importações por minuto do vault")
class NoteRateLimitQaIT {

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

    private CardQaWorld w;

    @BeforeEach
    void cenario() {
        clock.set(MutableClockConfig.INITIAL);
        w = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
    }

    private MvcResult export(Actor a) throws Exception {
        return mvc.perform(a.req(HttpMethod.GET, "/v1/vault/export", null)).andReturn();
    }

    @Test
    @DisplayName("a 7ª exportação/importação do mesmo usuário na janela é 429 TOO_MANY_ATTEMPTS com Retry-After (export e import dividem o limite); outro usuário não é afetado; importação limitada não grava nada; a janela anda")
    void limiteDeTaxa() throws Exception {
        Actor a = w.new Actor("e6rateA");
        Actor b = w.new Actor("e6rateB");
        for (int i = 0; i < 3; i++) {
            assertThat(export(a).getResponse().getStatus()).isEqualTo(200);
        }
        for (int i = 0; i < 3; i++) {
            assertThat(NoteQa.upload(w, a, new Zip().add("n" + i + ".md", "x").bytes(), null).status()).isEqualTo(200);
        }
        MvcResult limited = export(a);
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();
        assertThat(om.readTree(limited.getResponse().getContentAsString()).get("code").asString()).isEqualTo("TOO_MANY_ATTEMPTS");
        Res up = NoteQa.upload(w, a, new Zip().add("z.md", "x").bytes(), null);
        assertThat(up.status()).isEqualTo(429);
        assertThat(export(b).getResponse().getStatus()).isEqualTo(200);
        assertThat(a.count("notes")).isEqualTo(3);
        clock.set(MutableClockConfig.INITIAL.plusSeconds(61));
        a.bearer = auth.bearer(a.id);
        assertThat(export(a).getResponse().getStatus()).as("a janela anda").isEqualTo(200);
        assertThat(w.contractViolations).isEmpty();
    }
}
