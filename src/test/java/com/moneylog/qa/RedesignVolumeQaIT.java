package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.search.domain.SearchGroup;
import com.moneylog.core.search.domain.SearchResults;
import com.moneylog.infra.search.gateways.SearchGatewayImpl;
import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * T190 / R13 (QA): desempenho dos endpoints novos do redesign com 10.000 e 50.000 movimentações (despesas e receitas em 12 categorias, com
 * acento, datas de 2018 a 2026), no padrão do {@code GoalVolumeQaIT}. Mede e imprime ({@code QA-REDESIGN-VOL}); os limites são só uma rede
 * de segurança (30 s de CPU por leitura). Mede também a abertura do Início inteiro (as leituras do razão em sequência) e o estouro do
 * {@code statement_timeout} da busca num ledger grande.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T190/R13: volume de 10.000 e 50.000 movimentações nos endpoints do redesign")
class RedesignVolumeQaIT {

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

    /** Mais 2 que o {@code Actor} já cria (Alimentação e Tecnologia): 12 categorias de despesa no total. */
    private static final String[] CATEGORIES = {"Transporte", "Moradia", "Saúde", "Lazer", "Educação", "Café", "Mercado", "Contas", "Viagem", "Presentes"};

    private void seed(Actor a, int count) {
        UUID nubank = UUID.fromString(a.nubank);
        List<UUID> expenseCategories = new ArrayList<>(List.of(UUID.fromString(a.food), UUID.fromString(a.tech)));
        for (String name : CATEGORIES) {
            expenseCategories.add(UUID.fromString(a.category(name, com.moneylog.core.category.domain.CategoryKind.EXPENSE)));
        }
        UUID salary = UUID.fromString(a.income);
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
                boolean income = i % 10 == 0;
                UUID category = income ? salary : expenseCategories.get(i % expenseCategories.size());
                String type = income ? "INCOME" : "EXPENSE";
                String description = (income ? "Receita " : i % 7 == 0 ? "Café " : "Despesa ") + i + " =x ; \"aspas\" 日本語";
                transactions.add(new Object[] {id, a.id, type, cents, sqlDate, description, nubank, category, now, now});
                String competence = date.toString().substring(0, 7);
                long sign = income ? -1 : 1;
                postings.add(new Object[] {UUID.randomUUID(), a.id, id, id, income ? "INCOME" : "EXPENSE", category, competence, sqlDate, sign * cents, now, now});
                postings.add(new Object[] {UUID.randomUUID(), a.id, id, id, "ACCOUNT", nubank, competence, sqlDate, income ? cents : -cents, now, now});
            }
            new TransactionTemplate(tm).executeWithoutResult(s -> {
                jdbc.batchUpdate("""
                        INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, description, payment_method,
                            account_id, category_id, source, created_at, updated_at, version)
                        VALUES (?, ?, ?, 'ACTIVE', ?, 'BRL', ?, ?, 'PIX', ?, ?, 'MANUAL', ?, ?, 0)""", transactions);
                jdbc.batchUpdate("""
                        INSERT INTO ledger_postings (id, user_id, source_type, source_id, transaction_id, book_type, book_id, competence, effective_date,
                            amount_cents, currency, active, created_at, updated_at)
                        VALUES (?, ?, 'TRANSACTION', ?, ?, ?, ?, ?, ?, ?, 'BRL', true, ?, ?)""", postings);
            });
        }
        jdbc.execute("ANALYZE transactions");
        jdbc.execute("ANALYZE ledger_postings");
    }

    private static long usedMb() {
        System.gc();
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) >> 20;
    }

    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();

    /**
     * Mede o relógio de parede e o tempo de CPU da thread da requisição (o MockMvc roda na mesma thread). Numa máquina compartilhada e carregada o
     * relógio de parede mente (a mesma leitura variou de 9 a 61 s entre execuções); o CPU da thread é o que não depende da carga. Devolve o CPU.
     */
    private long timed(String label, Actor a, String url) throws Exception {
        long cpuStart = THREADS.getCurrentThreadCpuTime();
        long start = System.nanoTime();
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        long ms = (System.nanoTime() - start) / 1_000_000;
        long cpu = (THREADS.getCurrentThreadCpuTime() - cpuStart) / 1_000_000;
        System.out.printf("QA-REDESIGN-VOL %s: %d ms (CPU da thread %d ms), status %d, %d kB%n", label, ms, cpu, r.getResponse().getStatus(),
                r.getResponse().getContentAsByteArray().length / 1024);
        assertThat(r.getResponse().getStatus()).as(label).isEqualTo(200);
        return cpu;
    }

    /**
     * O banco do contexto é compartilhado com as outras suítes (CardPropertyQaIT, por exemplo, tem consultas que varrem a tabela inteira): 60.000
     * movimentações largadas aqui tornavam cada passo dela de milissegundos em ~4 s (a suíte inteira passou de 1 hora). Por isso o ator é apagado
     * ao fim e as tabelas são limpas com VACUUM.
     */
    private void cleanUp(Actor a) throws Exception {
        new TransactionTemplate(tm).executeWithoutResult(s -> {
            jdbc.update("DELETE FROM ledger_postings WHERE user_id = ?", a.id);
            jdbc.update("DELETE FROM transactions WHERE user_id = ?", a.id);
        });
        try (java.sql.Connection connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(true);
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("VACUUM (ANALYZE) ledger_postings");
                statement.execute("VACUUM (ANALYZE) transactions");
            }
        }
    }

    private void run(int count) throws Exception {
        Actor a = world.new Actor("redesignvol" + count);
        try {
            measure(a, count);
        } finally {
            cleanUp(a);
        }
    }

    private void measure(Actor a, int count) throws Exception {
        seed(a, count);
        System.out.println("QA-REDESIGN-VOL === " + count + " movimentações; heap antes: " + usedMb() + " MB, máx " + (Runtime.getRuntime().maxMemory() >> 20) + " MB");
        long before = usedMb();
        // baseline (já medido no ADR-008): o dashboard e o resumo carregam o razão inteiro
        long dash = timed("dashboard " + count, a, "/v1/dashboard/summary");
        long sum = timed("resumo mensal " + count, a, "/v1/summaries/2026-10");
        // endpoints novos
        long d7 = timed("income-expenses D7 " + count, a, "/v1/reports/income-expenses?period=D7");
        long d30 = timed("income-expenses D30 " + count, a, "/v1/reports/income-expenses?period=D30");
        long m3 = timed("income-expenses M3 " + count, a, "/v1/reports/income-expenses?period=M3");
        long m12 = timed("income-expenses M12 " + count, a, "/v1/reports/income-expenses?period=M12");
        long spending = timed("spending-by-category " + count, a, "/v1/reports/spending-by-category?limit=5");
        long afterReads = usedMb();
        // busca: o termo que casa tudo (LIMIT corta cedo), o que casa pouco e o que não casa (varredura completa)
        long searchAll = timed("busca 'despesa' (casa ~90%) " + count, a, "/v1/search?q=despesa");
        long searchFew = timed("busca 'despesa 4999' " + count, a, "/v1/search?q=" + "despesa%204999");
        long searchCafe = timed("busca 'cafe' sem acento (casa ~14%) " + count, a, "/v1/search?q=cafe");
        long searchNone = timed("busca 'zzznaoexiste' (varredura completa) " + count, a, "/v1/search?q=zzznaoexiste");
        long searchLiteral = timed("busca '100%' (curinga literal) " + count, a, "/v1/search?q=100%25");
        // a abertura do Início: o razão carregado em sequência (a ordem de prioridade do plano, T201), sem a busca
        long start = System.nanoTime();
        timed("início: dashboard", a, "/v1/dashboard/summary");
        timed("início: resumo do mês", a, "/v1/summaries/2026-10");
        timed("início: série M12", a, "/v1/reports/income-expenses?period=M12");
        timed("início: categorias", a, "/v1/reports/spending-by-category");
        System.out.printf("QA-REDESIGN-VOL início inteiro (4 leituras do razão em sequência) %d: %d ms%n", count, (System.nanoTime() - start) / 1_000_000);
        System.out.printf("QA-REDESIGN-VOL heap %d MB -> após as leituras %d MB (limite %d MB)%n", before, afterReads, Runtime.getRuntime().maxMemory() >> 20);

        // A/B da consulta de movimentações (menor de 5 execuções, ms): unaccent(?) avaliado por linha × uma vez só (subconsulta de uma linha)
        String perRow = "SELECT t.id FROM transactions t WHERE t.user_id = ? AND t.status = 'ACTIVE' AND t.description IS NOT NULL "
                + "AND unaccent(t.description) ILIKE unaccent(?) ORDER BY t.transaction_date DESC, t.created_at DESC, t.id DESC LIMIT 5";
        String once = "SELECT t.id FROM transactions t CROSS JOIN (SELECT unaccent(?) AS pat) p WHERE t.user_id = ? AND t.status = 'ACTIVE' "
                + "AND t.description IS NOT NULL AND unaccent(t.description) ILIKE p.pat ORDER BY t.transaction_date DESC, t.created_at DESC, t.id DESC LIMIT 5";
        for (String term : new String[] {"%despesa%", "%zzznaoexiste%"}) {
            long best1 = Long.MAX_VALUE;
            long best2 = Long.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                jdbc.queryForList(perRow, a.id, term);
                best1 = Math.min(best1, (System.nanoTime() - t0) / 1_000_000);
                long t1 = System.nanoTime();
                jdbc.queryForList(once, term, a.id);
                best2 = Math.min(best2, (System.nanoTime() - t1) / 1_000_000);
            }
            System.out.printf("QA-REDESIGN-VOL A/B busca %s com %d: unaccent(?) por linha %d ms × uma vez só %d ms%n", term, count, best1, best2);
        }

        // estouro do statement_timeout: 1 ms num ledger grande trunca os grupos em varredura e a transação segue
        SearchGatewayImpl impatient = new SearchGatewayImpl(jdbc, Duration.ofMillis(1));
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        definition.setReadOnly(true);
        Set<SearchGroup> truncated = new TransactionTemplate(tm, definition).execute(status -> impatient.search(a.id, "zzznaoexiste", 5).truncated());
        System.out.println("QA-REDESIGN-VOL busca com statement_timeout de 1 ms: grupos truncados = " + truncated);
        assertThat(truncated).as("a varredura de movimentações passa de 1 ms").contains(SearchGroup.TRANSACTIONS);

        assertThat(Math.max(Math.max(Math.max(d7, d30), Math.max(m3, m12)), Math.max(spending, Math.max(dash, sum)))).as("leituras (CPU da thread, ms)").isLessThan(30_000);
        assertThat(Math.max(Math.max(searchAll, searchFew), Math.max(Math.max(searchCafe, searchNone), searchLiteral))).as("busca (CPU da thread, ms)").isLessThan(30_000);
        SearchResults check = new TransactionTemplate(tm, definition).execute(status -> new SearchGatewayImpl(jdbc, Duration.ofSeconds(30)).search(a.id, "despesa 4999", 5));
        assertThat(check.transactions()).isNotEmpty();
        assertThat(check.truncated()).isEmpty();
    }

    @Test
    @DisplayName("10.000 movimentações")
    void dezMil() throws Exception {
        run(10_000);
    }

    @Test
    @DisplayName("50.000 movimentações: sem OutOfMemory; mede e documenta (R13)")
    void cinquentaMil() throws Exception {
        run(50_000);
    }
}
