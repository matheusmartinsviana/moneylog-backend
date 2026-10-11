package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = {"moneylog.search.timeout=PT0.001S", "moneylog.search.rate-limit.max-requests=1000000"})
@DisplayName("QA T190: estouro repetido do statement_timeout da busca e saúde do pool")
class RedesignFase0SearchTimeoutQaIT {

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
    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("100 buscas estouradas seguidas (em série e depois em paralelo): sempre 200 com truncated, sem 5xx; o pool volta saudável")
    void estouroRepetido() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        CardQaWorld world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        Actor a = world.new Actor("tmo");
        seed(a, 30_000);

        int truncatedTransactions = 0;
        for (int i = 0; i < 100; i++) {
            var r = world.res(mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", "estouro " + (i % 7))
                    .header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn());
            assertThat(r.status()).as("busca " + i + " " + r).isEqualTo(200);
            JsonNode truncated = r.node().get("truncated");
            if (truncated != null && truncated.toString().contains("transactions")) {
                truncatedTransactions++;
            }
        }
        System.out.println("QA-F0-TIMEOUT série: 100 buscas, truncated(transactions)=" + truncatedTransactions);
        assertThat(truncatedTransactions).as("o teto de 1 ms precisa estourar de verdade (senão o teste não prova nada)").isGreaterThan(80);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger bad = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 40; i++) {
                    try {
                        var r = world.res(mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", "estouro")
                                .header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn());
                        if (r.status() != 200) {
                            bad.incrementAndGet();
                            System.out.println("QA-F0-TIMEOUT paralelo " + r);
                        }
                    } catch (Exception e) {
                        bad.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(bad.get()).as("buscas paralelas que não deram 200").isZero();

        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                Connection c = dataSource.getConnection();
                held.add(c);
                try (Statement st = c.createStatement()) {
                    try (ResultSet rs = st.executeQuery("show statement_timeout")) {
                        rs.next();
                        assertThat(rs.getString(1)).as("statement_timeout da conexão " + i).isIn("0", "0ms");
                    }
                    st.execute("select pg_sleep(0.15)");
                    try (ResultSet rs = st.executeQuery("select txid_current_if_assigned() is null, current_setting('transaction_isolation')")) {
                        rs.next();
                        assertThat(rs.getString(2)).as("isolamento da conexão " + i).isEqualTo("read committed");
                    }
                }
                c.rollback();
            }
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
        Long idleInTx = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() and state like 'idle in transaction%'",
                Long.class);
        assertThat(idleInTx).as("conexões presas em 'idle in transaction'").isZero();

        var r = world.res(mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", "nubank").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn());
        assertThat(r.status()).isEqualTo(200);
    }

    private void seed(Actor a, int count) {
        UUID nubank = UUID.fromString(a.nubank);
        UUID food = UUID.fromString(a.food);
        Timestamp now = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));
        for (int offset = 0; offset < count; offset += 5000) {
            List<Object[]> rows = new ArrayList<>();
            for (int i = offset; i < Math.min(count, offset + 5000); i++) {
                rows.add(new Object[] {UUID.randomUUID(), a.id, 100L + i % 5000, Date.valueOf(LocalDate.of(2020, 1, 1).plusDays(i % 2000)),
                        "Descrição longa de número " + i + " com acentuação ção ã é estouro " + (i % 7) + " lorem ipsum dolor sit amet", nubank, food, now, now});
            }
            new TransactionTemplate(tm).executeWithoutResult(s -> jdbc.batchUpdate("""
                    INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, description, payment_method,
                        account_id, category_id, source, created_at, updated_at, version)
                    VALUES (?, ?, 'EXPENSE', 'ACTIVE', ?, 'BRL', ?, ?, 'PIX', ?, ?, 'MANUAL', ?, ?, 0)""", rows));
        }
        jdbc.execute("ANALYZE transactions");
    }
}
