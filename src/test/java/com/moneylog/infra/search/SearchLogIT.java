package com.moneylog.infra.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.moneylog.infra.debt.E4ItBase;

/**
 * S-287: o termo {@code q} (dado financeiro ou pessoal) nunca vai para o log de aplicação: nem na busca que funciona, nem na recusada com 400,
 * nem na limitada com 429, nem na que falha. O nível da raiz sobe a DEBUG durante o teste para pegar também o log do Spring MVC e do JDBC.
 */
@DisplayName("Busca global: o termo não aparece em nenhum log (S-287)")
class SearchLogIT extends E4ItBase {

    private static final String SECRET = "tokenSecretoQ7";

    private ListAppender<ILoggingEvent> appender;
    private Logger root;
    private Level previous;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        previous = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(appender);
        root.setLevel(previous);
    }

    private String everything() {
        StringBuilder text = new StringBuilder();
        List<ILoggingEvent> events = List.copyOf(appender.list);
        for (ILoggingEvent event : events) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
            if (event.getMDCPropertyMap() != null) {
                text.append(event.getMDCPropertyMap()).append('\n');
            }
        }
        return text.toString();
    }

    @Test
    @DisplayName("busca que funciona, termo com controle (400), termo curto e limite estourado (429): nenhum log contém o termo")
    void naoDeveLogarOTermo() throws Exception {
        created("{\"type\":\"EXPENSE\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"" + SECRET + " almoço\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}");
        appender.list.clear();

        assertThat(okBody(get("/v1/search").param("q", SECRET)).get("transactions")).hasSize(1);
        mockMvc.perform(get("/v1/search").param("q", SECRET + "\u0000")).andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.get(URI.create("/v1/search?q=" + SECRET + "%00")).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/search").param("q", SECRET.repeat(20))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/search").param("q", SECRET).param("limit", "99")).andExpect(status().isBadRequest());
        for (int i = 0; i < 125; i++) {
            mockMvc.perform(get("/v1/search").param("q", SECRET));
        }
        mockMvc.perform(get("/v1/search").param("q", SECRET)).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(1));

        assertThat(appender.list).as("o teste capturou logs").isNotEmpty();
        assertThat(everything()).doesNotContain(SECRET);
    }
}
