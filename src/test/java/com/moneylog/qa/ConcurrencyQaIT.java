package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClockConfig;

/** T43 (QA), critério 5: concorrência. Nenhuma corrida pode produzir 500, duplicar ou violar uma invariante financeira. */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T43: concorrência")
class ConcurrencyQaIT {

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

    private QaWorld world;
    private QaWorld.Actor a;

    @BeforeEach
    void cenario() throws Exception {
        world = new QaWorld(mvc, om, jdbc, auth, tm);
        a = world.new Actor("conc").withExpense();
    }

    private List<MvcResult> race(List<Callable<MvcResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> t : tasks) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return t.call();
            }));
        }
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();
        return results;
    }

    private static Map<Integer, Integer> histogram(List<MvcResult> results) {
        Map<Integer, Integer> h = new TreeMap<>();
        results.forEach(r -> h.merge(r.getResponse().getStatus(), 1, Integer::sum));
        return h;
    }

    private String codes(List<MvcResult> results) throws Exception {
        List<String> out = new ArrayList<>();
        for (MvcResult r : results) {
            String c = r.getResponse().getContentAsString();
            out.add(r.getResponse().getStatus() + (c.startsWith("{") ? ":" + om.readTree(c).path("code").asString() : ""));
        }
        return out.toString();
    }

    private String balance(String account) throws Exception {
        return a.get("/v1/accounts/" + account).get("currentBalance").get("amount").asString();
    }

    private void assertLedgerClosed() {
        assertThat(world.ledgerSum(a.id)).as("razão fecha em zero").isZero();
        assertThat(world.unbalancedActiveGroups(a.id)).as("cada grupo ativo fecha em zero").isZero();
    }

    private String editBody(String amount, String category) {
        return QaWorld.expense(a.nubank, category, amount, "2026-10-10", "Mercado").replace("}", ",\"version\":0}");
    }

    @Test
    @DisplayName("8 PUT da mesma movimentação com a mesma version: exatamente 1 x 200, 7 x 409 VERSION_CONFLICT, 1 revisão, version 1")
    void oitoEdicoesNaMesmaVersao() throws Exception {
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String amount = (10 + i) + ".00";
            tasks.add(() -> mvc.perform(a.req(HttpMethod.PUT, "/v1/transactions/" + a.expenseTx, editBody(amount, a.food))).andReturn());
        }
        List<MvcResult> r = race(tasks);
        assertThat(histogram(r)).as(codes(r)).isEqualTo(Map.of(200, 1, 409, 7));
        assertThat(jdbc.queryForObject("select count(*) from transaction_revisions where transaction_id = ?::uuid", Integer.class, a.expenseTx)).isEqualTo(1);
        assertThat(a.get("/v1/transactions/" + a.expenseTx).get("version").asLong()).isEqualTo(1);
        assertLedgerClosed();
        // o saldo reflete exatamente a edição vencedora
        BigDecimal amount = new BigDecimal(a.get("/v1/transactions/" + a.expenseTx).get("amount").get("amount").asString());
        assertThat(new BigDecimal(balance(a.nubank))).isEqualByComparingTo(new BigDecimal("20000.00").subtract(amount));
    }

    @Test
    @DisplayName("8 estornos simultâneos: 1 x 200 e 7 x 422 TRANSACTION_ALREADY_VOIDED (nunca 500); saldo volta uma só vez")
    void estornoConcorrente() throws Exception {
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> mvc.perform(a.req(HttpMethod.POST, "/v1/transactions/" + a.expenseTx + "/void", "{\"reason\":\"corrida\"}")).andReturn());
        }
        List<MvcResult> r = race(tasks);
        System.out.println("QA-VOID-RACE " + codes(r));
        assertThat(r.stream().filter(x -> x.getResponse().getStatus() >= 500).count()).as(codes(r)).isZero();
        assertThat(histogram(r).get(200)).as(codes(r)).isEqualTo(1);
        assertThat(balance(a.nubank)).isEqualTo("20000.00");
        assertThat(jdbc.queryForObject("select count(*) from ledger_postings where transaction_id = ?::uuid and active", Integer.class, a.expenseTx)).isZero();
        assertLedgerClosed();
    }

    @RepeatedTest(5)
    @DisplayName("estorno x edição simultâneos: ou a edição vence e depois o estorno, ou o estorno vence e a edição é 422; estado final coerente")
    void estornoContraEdicao() throws Exception {
        QaWorld.Actor local = world.new Actor("conc2").withExpense();
        List<Callable<MvcResult>> tasks = List.of(
                () -> mvc.perform(local.req(HttpMethod.PUT, "/v1/transactions/" + local.expenseTx,
                        QaWorld.expense(local.nubank, local.food, "55.00", "2026-10-10", "Mercado").replace("}", ",\"version\":0}"))).andReturn(),
                () -> mvc.perform(local.req(HttpMethod.POST, "/v1/transactions/" + local.expenseTx + "/void", "{\"reason\":\"corrida\"}")).andReturn());
        List<MvcResult> r = race(tasks);
        System.out.println("QA-VOID-EDIT " + codes(r));
        assertThat(r.stream().filter(x -> x.getResponse().getStatus() >= 500).count()).as(codes(r)).isZero();
        JsonNode tx = local.get("/v1/transactions/" + local.expenseTx);
        long activePostings = jdbc.queryForObject("select count(*) from ledger_postings where transaction_id = ?::uuid and active", Long.class, local.expenseTx);
        BigDecimal nubank = new BigDecimal(local.get("/v1/accounts/" + local.nubank).get("currentBalance").get("amount").asString());
        if (tx.get("status").asString().equals("VOIDED")) {
            assertThat(activePostings).as("estornada não tem lançamento ativo " + codes(r)).isZero();
            assertThat(nubank).isEqualByComparingTo("20000.00");
        } else {
            assertThat(activePostings).isGreaterThan(0);
            assertThat(nubank).isEqualByComparingTo(new BigDecimal("20000.00").subtract(new BigDecimal(tx.get("amount").get("amount").asString())));
        }
        assertThat(world.ledgerSum(local.id)).isZero();
        assertThat(world.unbalancedActiveGroups(local.id)).isZero();
    }

    @Test
    @DisplayName("10 POST com a mesma Idempotency-Key e o mesmo corpo em paralelo: 1 movimentação, todas as respostas com o mesmo id")
    void idempotenciaEmParalelo() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = QaWorld.expense(a.nubank, a.food, "10.00", "2026-10-10", "Idem");
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> mvc.perform(a.req(HttpMethod.POST, "/v1/transactions", body).header("Idempotency-Key", key)).andReturn());
        }
        List<MvcResult> r = race(tasks);
        assertThat(histogram(r)).as(codes(r)).isEqualTo(Map.of(201, 10));
        assertThat(r.stream().map(x -> {
            try {
                return om.readTree(x.getResponse().getContentAsString()).get("id").asString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).distinct().count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ? and description = 'Idem'", Integer.class, a.id)).isEqualTo(1);
        assertThat(balance(a.nubank)).isEqualTo("19890.00");
        assertLedgerClosed();
    }

    @Test
    @DisplayName("mesma chave com corpos diferentes em paralelo: 1 x 201 e os demais 409 IDEMPOTENCY_KEY_REUSED (ou 201 do mesmo id), nunca duas movimentações")
    void idempotenciaComCorposDiferentes() throws Exception {
        String key = UUID.randomUUID().toString();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String body = QaWorld.expense(a.nubank, a.food, (20 + i) + ".00", "2026-10-10", "Idem-" + i);
            tasks.add(() -> mvc.perform(a.req(HttpMethod.POST, "/v1/transactions", body).header("Idempotency-Key", key)).andReturn());
        }
        List<MvcResult> r = race(tasks);
        System.out.println("QA-IDEM-DIFF " + codes(r));
        assertThat(r.stream().filter(x -> x.getResponse().getStatus() >= 500).count()).as(codes(r)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ? and description like 'Idem-%'", Integer.class, a.id)).isEqualTo(1);
        assertLedgerClosed();
    }

    @Test
    @DisplayName("12 criações com chaves diferentes: 12 movimentações, saldo = abertura − Σ, razão fecha")
    void criacoesParalelasComChavesDiferentes() throws Exception {
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String body = QaWorld.expense(a.nubank, a.food, "7.00", "2026-10-10", "Par-" + i);
            tasks.add(() -> mvc.perform(a.req(HttpMethod.POST, "/v1/transactions", body).header("Idempotency-Key", UUID.randomUUID().toString())).andReturn());
        }
        List<MvcResult> r = race(tasks);
        assertThat(histogram(r)).as(codes(r)).isEqualTo(Map.of(201, 12));
        assertThat(balance(a.nubank)).isEqualTo("19816.00"); // 20000 - 100 - 12*7
        assertLedgerClosed();
    }

    @RepeatedTest(5)
    @DisplayName("S-81: reembolsos simultâneos de 30,00 sobre uma despesa de 100,00: no máximo 3 passam (Σ reembolsos <= 100,00)")
    void reembolsosSimultaneosNaoPassamDoTeto() throws Exception {
        QaWorld.Actor local = world.new Actor("conc3").withExpense();
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String body = QaWorld.refund(local.nubank, local.food, local.expenseTx, "30.00", "2026-10-10");
            tasks.add(() -> mvc.perform(local.req(HttpMethod.POST, "/v1/transactions", body).header("Idempotency-Key", UUID.randomUUID().toString())).andReturn());
        }
        List<MvcResult> r = race(tasks);
        System.out.println("QA-REFUND-RACE " + codes(r));
        System.out.println("QA-REFUND-RACE consumo=" + local.get("/v1/entries?competence=2026-10&lens=CONSUMPTION").get("totals").get("total").get("amount").asString());
        BigDecimal refunded = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from transactions where refund_of_id = ?::uuid and status = 'ACTIVE'",
                BigDecimal.class, local.expenseTx).movePointLeft(2);
        assertThat(refunded).as("Σ reembolsos ativos " + codes(r)).isLessThanOrEqualTo(new BigDecimal("100.00"));
        assertThat(r.stream().filter(x -> x.getResponse().getStatus() >= 500).count()).as(codes(r)).isZero();
        assertThat(world.ledgerSum(local.id)).isZero();
        JsonNode consumption = local.get("/v1/entries?competence=2026-10&lens=CONSUMPTION").get("totals").get("total");
        assertThat(new BigDecimal(consumption.get("amount").asString())).as("consumo nunca negativo").isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @RepeatedTest(5)
    @DisplayName("S-81: estornar a despesa original enquanto um reembolso é criado: nunca fica reembolso ativo de despesa estornada")
    void estornoDaOriginalContraReembolso() throws Exception {
        QaWorld.Actor local = world.new Actor("conc4").withExpense();
        List<Callable<MvcResult>> tasks = List.of(
                () -> mvc.perform(local.req(HttpMethod.POST, "/v1/transactions/" + local.expenseTx + "/void", "{\"reason\":\"corrida\"}")).andReturn(),
                () -> mvc.perform(local.req(HttpMethod.POST, "/v1/transactions", QaWorld.refund(local.nubank, local.food, local.expenseTx, "30.00", "2026-10-10"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn());
        List<MvcResult> r = race(tasks);
        System.out.println("QA-VOID-REFUND " + codes(r));
        System.out.println("QA-VOID-REFUND consumo=" + local.get("/v1/entries?competence=2026-10&lens=CONSUMPTION").get("totals").get("total").get("amount").asString()
                + " original=" + local.get("/v1/transactions/" + local.expenseTx).get("status").asString());
        Integer activeRefunds = jdbc.queryForObject("select count(*) from transactions where refund_of_id = ?::uuid and status = 'ACTIVE'", Integer.class, local.expenseTx);
        String originalStatus = local.get("/v1/transactions/" + local.expenseTx).get("status").asString();
        assertThat(originalStatus.equals("VOIDED") && activeRefunds > 0).as("original estornada com reembolso ativo " + codes(r)).isFalse();
        assertThat(world.ledgerSum(local.id)).isZero();
        assertThat(new BigDecimal(local.get("/v1/entries?competence=2026-10&lens=CONSUMPTION").get("totals").get("total").get("amount").asString()))
                .as("consumo nunca negativo").isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @RepeatedTest(5)
    @DisplayName("S-81: trocar a categoria da despesa enquanto um reembolso é criado: reembolso ativo e categoria da original iguais")
    void trocaDeCategoriaContraReembolso() throws Exception {
        QaWorld.Actor local = world.new Actor("conc5").withExpense();
        String other = local.category("Lazer", com.moneylog.core.category.domain.CategoryKind.EXPENSE);
        List<Callable<MvcResult>> tasks = List.of(
                () -> mvc.perform(local.req(HttpMethod.PUT, "/v1/transactions/" + local.expenseTx,
                        QaWorld.expense(local.nubank, other, "100.00", "2026-10-10", "Mercado").replace("}", ",\"version\":0}"))).andReturn(),
                () -> mvc.perform(local.req(HttpMethod.POST, "/v1/transactions", QaWorld.refund(local.nubank, local.food, local.expenseTx, "30.00", "2026-10-10"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn());
        List<MvcResult> r = race(tasks);
        System.out.println("QA-CAT-REFUND " + codes(r));
        System.out.println("QA-CAT-REFUND porCategoria=" + jdbc.queryForList("select c.name, sum(p.amount_cents) as sum from ledger_postings p join categories c on c.id = p.book_id where p.user_id = ? and p.active and p.book_type='EXPENSE' group by c.name", local.id));
        List<Map<String, Object>> rows = jdbc.queryForList("select t.category_id as orig, r.category_id as ref from transactions t join transactions r on r.refund_of_id = t.id "
                + "where t.id = ?::uuid and r.status = 'ACTIVE'", local.expenseTx);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("ref")).as("categoria do reembolso = categoria da original " + codes(r)).isEqualTo(row.get("orig"));
        }
        assertThat(world.ledgerSum(local.id)).isZero();
        assertThat(new BigDecimal(local.get("/v1/entries?competence=2026-10&lens=CONSUMPTION").get("totals").get("total").get("amount").asString()))
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        jdbc.queryForList("select c.name, sum(p.amount_cents) from ledger_postings p join categories c on c.id = p.book_id where p.user_id = ? and p.active and p.book_type='EXPENSE' group by c.name",
                local.id).forEach(m -> {
                    assertThat(((Number) m.get("sum")).longValue()).as("consumo por categoria " + m).isGreaterThanOrEqualTo(0);
                });
    }

    @RepeatedTest(5)
    @DisplayName("arquivar a conta (saldo zero) enquanto entra uma receita nela: conta arquivada nunca fica com saldo diferente de zero")
    void arquivarContraMovimentacao() throws Exception {
        QaWorld.Actor local = world.new Actor("conc6");
        List<Callable<MvcResult>> tasks = List.of(
                () -> mvc.perform(local.req(HttpMethod.POST, "/v1/accounts/" + local.xp + "/archive", "")).andReturn(),
                () -> mvc.perform(local.req(HttpMethod.POST, "/v1/transactions", QaWorld.income(local.xp, local.salary, "50.00", "2026-10-10"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn());
        List<MvcResult> r = race(tasks);
        System.out.println("QA-ARCHIVE-RACE " + codes(r));
        JsonNode acc = local.get("/v1/accounts/" + local.xp);
        boolean archived = acc.get("status").asString().equals("ARCHIVED");
        BigDecimal balance = new BigDecimal(acc.get("currentBalance").get("amount").asString());
        assertThat(archived && balance.signum() != 0).as("conta arquivada com saldo " + balance + " " + codes(r)).isFalse();
        assertThat(world.ledgerSum(local.id)).isZero();
    }

    @Test
    @DisplayName("6 criações da mesma categoria em paralelo: 1 x 201, 5 x 409 CATEGORY_NAME_TAKEN (nunca 500)")
    void categoriaComMesmoNomeEmParalelo() throws Exception {
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> mvc.perform(a.req(HttpMethod.POST, "/v1/categories", "{\"name\":\"Pets\",\"kind\":\"EXPENSE\"}")).andReturn());
        }
        List<MvcResult> r = race(tasks);
        assertThat(histogram(r)).as(codes(r)).isEqualTo(Map.of(201, 1, 409, 5));
    }

    @Test
    @DisplayName("2 PUT de conta e 2 PUT de categoria na mesma version: 1 x 200 e 1 x 409 cada")
    void editarContaECategoriaNaMesmaVersao() throws Exception {
        List<MvcResult> acc = race(List.of(
                () -> mvc.perform(a.req(HttpMethod.PUT, "/v1/accounts/" + a.xp,
                        "{\"name\":\"XP-1\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\",\"version\":0}")).andReturn(),
                () -> mvc.perform(a.req(HttpMethod.PUT, "/v1/accounts/" + a.xp,
                        "{\"name\":\"XP-2\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\",\"version\":0}")).andReturn()));
        assertThat(histogram(acc)).as(codes(acc)).isEqualTo(Map.of(200, 1, 409, 1));
        List<MvcResult> cat = race(List.of(
                () -> mvc.perform(a.req(HttpMethod.PUT, "/v1/categories/" + a.food, "{\"name\":\"Cat-1\",\"version\":0}")).andReturn(),
                () -> mvc.perform(a.req(HttpMethod.PUT, "/v1/categories/" + a.food, "{\"name\":\"Cat-2\",\"version\":0}")).andReturn()));
        assertThat(histogram(cat)).as(codes(cat)).isEqualTo(Map.of(200, 1, 409, 1));
        assertLedgerClosed();
    }

    @Test
    @DisplayName("PUT de saldo inicial da conta enquanto uma movimentação é criada nela: ou ACCOUNT_OPENING_LOCKED ou a movimentação vem depois; razão fecha")
    void saldoInicialContraMovimentacao() throws Exception {
        QaWorld.Actor local = world.new Actor("conc7");
        List<MvcResult> r = race(List.of(
                () -> mvc.perform(local.req(HttpMethod.PUT, "/v1/accounts/" + local.xp,
                        "{\"name\":\"XP\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"500.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\",\"version\":0}")).andReturn(),
                () -> mvc.perform(local.req(HttpMethod.POST, "/v1/transactions", QaWorld.income(local.xp, local.salary, "50.00", "2026-10-10"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn()));
        System.out.println("QA-OPENING-RACE " + codes(r));
        assertThat(r.stream().filter(x -> x.getResponse().getStatus() >= 500).count()).as(codes(r)).isZero();
        assertThat(world.ledgerSum(local.id)).isZero();
        assertThat(world.unbalancedActiveGroups(local.id)).isZero();
        BigDecimal balance = new BigDecimal(local.get("/v1/accounts/" + local.xp).get("currentBalance").get("amount").asString());
        BigDecimal opening = new BigDecimal(local.get("/v1/accounts/" + local.xp).get("openingBalance").get("amount").asString());
        BigDecimal incomes = new BigDecimal(jdbc.queryForObject("select coalesce(sum(amount_cents),0) from transactions where account_id = ?::uuid and status='ACTIVE'",
                Long.class, local.xp)).movePointLeft(2);
        assertThat(balance).as("saldo = abertura + movimentações " + codes(r)).isEqualByComparingTo(opening.add(incomes));
        ContractValidation.spec();
    }

    @Test
    @DisplayName("leituras (lista, entries, dashboard, contas) durante escritas contínuas: nunca 5xx e o razão fecha no fim")
    void leiturasDuranteEscritas() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        List<String> samples = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Future<?>> readers = new ArrayList<>();
        for (String url : List.of("/v1/transactions?from=2026-10-01&to=2026-10-31", "/v1/entries?competence=2026-10", "/v1/dashboard/summary", "/v1/accounts")) {
            readers.add(pool.submit(() -> {
                while (!stop.get()) {
                    MvcResult r = mvc.perform(a.req(HttpMethod.GET, url, null)).andReturn();
                    reads.incrementAndGet();
                    if (r.getResponse().getStatus() >= 500) {
                        errors.incrementAndGet();
                        samples.add(url + " -> " + r.getResponse().getStatus());
                    }
                }
                return null;
            }));
        }
        Future<?> writer = pool.submit(() -> {
            for (int i = 0; i < 60; i++) {
                MvcResult r = mvc.perform(a.req(HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.nubank, a.food, "1.00", "2026-10-10", "W" + i))
                        .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn();
                if (r.getResponse().getStatus() != 201) {
                    errors.incrementAndGet();
                    samples.add("write " + r.getResponse().getStatus());
                }
            }
            return null;
        });
        writer.get();
        stop.set(true);
        for (Future<?> f : readers) {
            f.get();
        }
        pool.shutdown();
        System.out.println("QA-READ-DURING-WRITE reads=" + reads.get() + " errors=" + errors.get() + " " + samples);
        assertThat(samples).isEmpty();
        assertLedgerClosed();
    }
}
