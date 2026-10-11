package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T98: volume de 10.000 e 50.000 movimentações")
class GoalVolumeQaIT {

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

    private CardQaWorld world;

    @BeforeEach
    void cenario() {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
    }

    private void seed(Actor a, int count) {
        UUID nubank = UUID.fromString(a.nubank);
        UUID food = UUID.fromString(a.food);
        Timestamp now = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));
        LocalDate first = LocalDate.of(2018, 1, 1);
        for (int offset = 0; offset < count; offset += 1000) {
            List<Object[]> transactions = new ArrayList<>();
            List<Object[]> postings = new ArrayList<>();
            for (int i = offset; i < Math.min(count, offset + 1000); i++) {
                UUID id = UUID.randomUUID();
                LocalDate date = first.plusDays(i % 3200);
                Date sqlDate = Date.valueOf(date);
                long cents = 100 + (i % 9000);
                transactions.add(new Object[] {id, a.id, cents, sqlDate, "Despesa " + i + " =x ; \"aspas\" 日本語", nubank, food, now, now});
                String competence = date.toString().substring(0, 7);
                postings.add(new Object[] {UUID.randomUUID(), a.id, id, id, "EXPENSE", food, competence, sqlDate, cents, now, now});
                postings.add(new Object[] {UUID.randomUUID(), a.id, id, id, "ACCOUNT", nubank, competence, sqlDate, -cents, now, now});
            }
            new TransactionTemplate(tm).executeWithoutResult(s -> {
                jdbc.batchUpdate("""
                        INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, description, payment_method,
                            account_id, category_id, source, created_at, updated_at, version)
                        VALUES (?, ?, 'EXPENSE', 'ACTIVE', ?, 'BRL', ?, ?, 'PIX', ?, ?, 'MANUAL', ?, ?, 0)""", transactions);
                jdbc.batchUpdate("""
                        INSERT INTO ledger_postings (id, user_id, source_type, source_id, transaction_id, book_type, book_id, competence, effective_date,
                            amount_cents, currency, active, created_at, updated_at)
                        VALUES (?, ?, 'TRANSACTION', ?, ?, ?, ?, ?, ?, ?, 'BRL', true, ?, ?)""", postings);
            });
        }
    }

    private static long usedMb() {
        System.gc();
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) >> 20;
    }

    private long timed(String label, Actor a, String url) throws Exception {
        long start = System.nanoTime();
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("QA-E5-VOL %s: %d ms, status %d, %d kB%n", label, ms, r.getResponse().getStatus(), r.getResponse().getContentAsByteArray().length / 1024);
        assertThat(r.getResponse().getStatus()).as(label).isEqualTo(200);
        return ms;
    }

    private void cleanUp(Actor a) {
        new TransactionTemplate(tm).executeWithoutResult(s -> {
            jdbc.update("DELETE FROM goal_reservations WHERE user_id = ?", a.id);
            jdbc.update("DELETE FROM goals WHERE user_id = ?", a.id);
            jdbc.update("DELETE FROM ledger_postings WHERE user_id = ?", a.id);
            jdbc.update("DELETE FROM transactions WHERE user_id = ?", a.id);
        });
    }

    private void run(int count, long csvLimitMs, long backupLimitMs, long readLimitMs) throws Exception {
        Actor a = world.new Actor("e5vol" + count);
        try {
            measure(a, count, csvLimitMs, backupLimitMs, readLimitMs);
        } finally {
            cleanUp(a);
        }
    }

    private void measure(Actor a, int count, long csvLimitMs, long backupLimitMs, long readLimitMs) throws Exception {
        seed(a, count);
        for (int i = 0; i < 2; i++) {
            String goal = GoalQa.goal(a, "Meta " + i, "100000.00", "2027-12-31");
            for (int j = 0; j < 6; j++) {
                CardQaWorld.ok(GoalQa.reserve(a, goal, a.nubank, "10.00", LocalDate.of(2026, 10, 10)));
            }
        }
        System.out.println("QA-E5-VOL === " + count + " movimentações, 12 reservas; heap antes: " + usedMb() + " MB, máx " + (Runtime.getRuntime().maxMemory() >> 20) + " MB");
        long before = usedMb();
        long csv = timed("CSV " + count, a, "/v1/exports/transactions.csv");
        long afterCsv = usedMb();
        long backup = timed("backup " + count, a, "/v1/exports/backup");
        long afterBackup = usedMb();
        long dash = timed("dashboard " + count, a, "/v1/dashboard/summary");
        long sum = timed("resumo mensal " + count, a, "/v1/summaries/2026-10");
        long proj = timed("projeção M12 com tendência " + count, a, "/v1/projections?horizon=M12&includeTrend=true");
        long goals = timed("lista de metas " + count, a, "/v1/goals");
        long accounts = timed("lista de contas " + count, a, "/v1/accounts");
        long reserve = System.nanoTime();
        CardQaWorld.ok(GoalQa.reserve(a, GoalQa.goal(a, "Nova", "10.00", null), a.nubank, "1.00", LocalDate.of(2026, 10, 10)));
        System.out.printf("QA-E5-VOL reservar (criar meta + reservar) com %d movimentações: %d ms%n", count, (System.nanoTime() - reserve) / 1_000_000);
        System.out.printf("QA-E5-VOL heap %d MB -> após CSV %d MB -> após backup %d MB (limite %d MB)%n", before, afterCsv, afterBackup, Runtime.getRuntime().maxMemory() >> 20);
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/v1/exports/transactions.csv").header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        String text = new String(r.getResponse().getContentAsByteArray(), 3, r.getResponse().getContentAsByteArray().length - 3, StandardCharsets.UTF_8);
        assertThat(CsvLines.count(text)).isEqualTo(count + 1);
        assertThat(csv).as("CSV").isLessThan(csvLimitMs);
        assertThat(backup).as("backup").isLessThan(backupLimitMs);
        assertThat(Math.max(Math.max(dash, sum), Math.max(Math.max(proj, goals), accounts))).as("leituras").isLessThan(readLimitMs);
    }

    private static final class CsvLines {
        static int count(String text) {
            int n = 0;
            for (int i = text.indexOf("\r\n"); i >= 0; i = text.indexOf("\r\n", i + 2)) {
                n++;
            }
            return n;
        }
    }

    @Test
    @DisplayName("10.000 movimentações: CSV <= 15 s, backup <= 30 s, leituras <= 30 s")
    void dezMil() throws Exception {
        run(10_000, 15_000, 30_000, 30_000);
    }

    @Test
    @DisplayName("50.000 movimentações: sem OutOfMemory, CSV <= 60 s, backup <= 120 s, leituras <= 60 s (mede e documenta)")
    void cinquentaMil() throws Exception {
        run(50_000, 60_000, 120_000, 60_000);
    }
}
