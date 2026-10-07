package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * T98 (QA), critério 4: concorrência de metas e reservas. Reservas e liberações simultâneas na mesma meta e conta (nunca saldo
 * negativo por data, meta e conta), reserva x arquivamento de conta, reserva x arquivamento da meta, reserva x estorno de movimentação,
 * {@code version}/409, criação de meta com a mesma {@code Idempotency-Key} em paralelo e uma tempestade que mistura tudo. Toda falha de
 * trava tem de ser {@code 409 CONFLICT} (ou o erro de domínio certo), nunca 500.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T98: concorrência de metas e reservas")
class GoalConcurrencyQaIT {

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
    private Actor a;
    private LocalDate today;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("e5cc");
        today = LocalDate.of(2026, 10, 10);
    }

    private <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(tasks.size());
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return t.call();
                }));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Integer> histogram(List<Res> results) {
        Map<String, Integer> h = new TreeMap<>();
        results.forEach(r -> h.merge(r.status() + (r.status() >= 400 ? " " + r.code() : ""), 1, Integer::sum));
        return h;
    }

    private static void assertNoServerError(String label, List<Res> results) {
        assertThat(results.stream().filter(r -> r.status() >= 500).map(Res::toString).toList()).as(label).isEmpty();
    }

    /** Estado final de qualquer corrida: razão fecha, nada de movimentação criada por reserva, nenhuma meta/conta com saldo de reserva negativo em alguma data. */
    private void assertHealthy(long transactionsBefore) throws Exception {
        assertThat(a.ledgerSum()).as("razão").isZero();
        assertThat(a.unbalancedGroups()).as("grupos desbalanceados").isZero();
        assertThat(a.count("transactions")).as("movimentações (a reserva nunca cria)").isEqualTo(transactionsBefore);
        List<Map<String, Object>> rows = jdbc.queryForList("select goal_id::text g, account_id::text a, reserved_on d, sum(amount_cents) c from goal_reservations "
                + "where user_id = ? group by 1,2,3 order by 1,2,3", a.id);
        Map<String, Long> running = new java.util.HashMap<>();
        for (Map<String, Object> r : rows) {
            String key = r.get("g") + "|" + r.get("a");
            long v = running.merge(key, ((Number) r.get("c")).longValue(), Long::sum);
            assertThat(v).as("saldo da reserva (meta|conta) até " + r.get("d")).isGreaterThanOrEqualTo(0);
        }
        assertThat(world.contractViolations.stream().filter(v -> !v.contains("-> 409")).toList()).as("contrato").isEmpty();
    }

    @Test
    @DisplayName("12 reservas simultâneas na mesma meta e conta: todas valem, current é a soma, a version sobe 12 e nada é 500")
    void reservasSimultaneas() throws Exception {
        for (int round = 0; round < 3; round++) {
            String goal = GoalQa.goal(a, "Reserva " + round, "50000.00", "2027-12-31");
            long tx = a.count("transactions");
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                tasks.add(() -> GoalQa.reserve(a, goal, a.nubank, "100.00", today));
            }
            List<Res> results = race(tasks);
            assertNoServerError("reservas", results);
            assertThat(histogram(results)).as("rodada " + round).isEqualTo(Map.of("200", 12));
            JsonNode g = GoalQa.getGoal(a, goal);
            assertThat(g.get("current").get("amount").asString()).isEqualTo("1200.00");
            assertThat(g.get("version").asLong()).isEqualTo(12);
            assertThat(g.get("reservations")).hasSize(12);
            assertHealthy(tx);
        }
    }

    @Test
    @DisplayName("8 liberações simultâneas de 100 sobre 500 reservados: exatamente 5 valem, 3 são GOAL_RELEASE_EXCEEDS_RESERVED, saldo nunca negativo")
    void liberacoesSimultaneas() throws Exception {
        for (int round = 0; round < 4; round++) {
            String goal = GoalQa.goal(a, "Liberar " + round, "50000.00", null);
            long tx = a.count("transactions");
            CardQaWorld.ok(GoalQa.reserve(a, goal, a.nubank, "500.00", today));
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> GoalQa.reserve(a, goal, a.nubank, "-100.00", today));
            }
            List<Res> results = race(tasks);
            assertNoServerError("liberações", results);
            assertThat(histogram(results)).as("rodada " + round).isEqualTo(Map.of("200", 5, "422 GOAL_RELEASE_EXCEEDS_RESERVED", 3));
            assertThat(GoalQa.net(jdbc, a, goal, a.nubank)).isZero();
            assertHealthy(tx);
        }
    }

    @Test
    @DisplayName("reserva e liberação cruzadas em duas contas da mesma meta (6 threads x 3 rodadas): só 200/422, nunca negativo por conta")
    void duasContas() throws Exception {
        String inv = GoalQa.account(a, "Invest", "INVESTMENT", "1000.00");
        for (int round = 0; round < 3; round++) {
            String goal = GoalQa.goal(a, "Duas contas " + round, "50000.00", null);
            long tx = a.count("transactions");
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                tasks.add(() -> GoalQa.reserve(a, goal, a.nubank, "70.00", today));
                tasks.add(() -> GoalQa.reserve(a, goal, inv, "-50.00", today));
                tasks.add(() -> GoalQa.reserve(a, goal, inv, "60.00", today));
                tasks.add(() -> GoalQa.reserve(a, goal, a.nubank, "-30.00", today));
            }
            List<Res> results = race(tasks);
            assertNoServerError("duas contas", results);
            assertThat(results.stream().filter(r -> r.status() != 200 && r.status() != 422).map(Res::toString).toList()).isEmpty();
            assertThat(results.stream().filter(r -> r.status() == 422).map(Res::code).distinct().toList()).isSubsetOf("GOAL_RELEASE_EXCEEDS_RESERVED");
            assertThat(GoalQa.net(jdbc, a, goal, a.nubank)).isGreaterThanOrEqualTo(0);
            assertThat(GoalQa.net(jdbc, a, goal, inv)).isGreaterThanOrEqualTo(0);
            assertHealthy(tx);
        }
    }

    @Test
    @DisplayName("mesma Idempotency-Key em 8 reservas paralelas: uma só linha; todas respondem 200")
    void reservaMesmaChave() throws Exception {
        String goal = GoalQa.goal(a, "Chave", "10000.00", null);
        long tx = a.count("transactions");
        for (int round = 0; round < 3; round++) {
            String key = GoalQa.key();
            long before = jdbc.queryForObject("select count(*) from goal_reservations where user_id = ?", Long.class, a.id);
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> GoalQa.reserveKey(a, goal, a.nubank, "10.00", today, key));
            }
            List<Res> results = race(tasks);
            assertNoServerError("mesma chave", results);
            assertThat(histogram(results)).as("rodada " + round).isEqualTo(Map.of("200", 8));
            assertThat(jdbc.queryForObject("select count(*) from goal_reservations where user_id = ?", Long.class, a.id)).isEqualTo(before + 1);
        }
        assertHealthy(tx);
    }

    @Test
    @DisplayName("criar meta com a mesma Idempotency-Key em 8 threads: uma meta só, todas devolvem a mesma (201)")
    void criacaoMesmaChave() throws Exception {
        for (int round = 0; round < 3; round++) {
            String key = GoalQa.key();
            String body = GoalQa.goalBody("Mesma chave " + round, "1000.00", "2027-01-31", "LOW");
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> a.call(HttpMethod.POST, "/v1/goals", body, key));
            }
            List<Res> results = race(tasks);
            assertNoServerError("criação", results);
            assertThat(histogram(results)).as("rodada " + round).isEqualTo(Map.of("201", 8));
            assertThat(results.stream().map(r -> r.node().get("id").asString()).distinct().toList()).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from goals where user_id = ? and name = ?", Long.class, a.id, "Mesma chave " + round)).isEqualTo(1L);
        }
        // corpo diferente com a mesma chave em paralelo: nunca duas metas e nunca 500
        String key = GoalQa.key();
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int n = i;
            tasks.add(() -> a.call(HttpMethod.POST, "/v1/goals", GoalQa.goalBody("Corpo " + n, "1000.00", null, "LOW"), key));
        }
        List<Res> results = race(tasks);
        assertNoServerError("criação com corpos diferentes", results);
        assertThat(histogram(results)).containsOnlyKeys("201", "409 IDEMPOTENCY_KEY_REUSED");
        assertThat(jdbc.queryForObject("select count(*) from goals where user_id = ? and name like 'Corpo %'", Long.class, a.id)).isEqualTo(1L);
    }

    @Test
    @DisplayName("PUT simultâneo com a mesma version: um 200 e o resto 409 VERSION_CONFLICT; reserva x PUT só 200/409")
    void versao() throws Exception {
        for (int round = 0; round < 3; round++) {
            String goal = GoalQa.goal(a, "Versão " + round, "9000.00", null);
            long version = GoalQa.getGoal(a, goal).get("version").asLong();
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int n = i;
                tasks.add(() -> a.call(HttpMethod.PUT, "/v1/goals/" + goal, "{\"name\":\"Editada " + n + "\",\"target\":\"9000.00\",\"priority\":\"LOW\",\"version\":" + version + "}", null));
            }
            List<Res> results = race(tasks);
            assertNoServerError("PUT", results);
            assertThat(histogram(results)).as("rodada " + round).isEqualTo(Map.of("200", 1, "409 VERSION_CONFLICT", 5));
            assertThat(GoalQa.getGoal(a, goal).get("version").asLong()).isEqualTo(version + 1);
        }
        String goal = GoalQa.goal(a, "Reserva x PUT", "9000.00", null);
        for (int round = 0; round < 6; round++) {
            long version = GoalQa.getGoal(a, goal).get("version").asLong();
            List<Callable<Res>> tasks = List.of(
                    () -> GoalQa.reserve(a, goal, a.nubank, "10.00", today),
                    () -> a.call(HttpMethod.PUT, "/v1/goals/" + goal, "{\"name\":\"Reserva x PUT\",\"target\":\"9000.00\",\"priority\":\"LOW\",\"version\":" + version + "}", null));
            List<Res> results = race(tasks);
            assertNoServerError("reserva x PUT", results);
            assertThat(results.get(0).status()).isEqualTo(200);
            assertThat(results.get(1).status()).isIn(200, 409);
        }
    }

    @Test
    @DisplayName("reserva x arquivamento da meta: ou a reserva vale antes de arquivar, ou é 422 GOAL_ARCHIVED; nunca 500 nem reserva depois de arquivada")
    void reservaXArquivarMeta() throws Exception {
        for (int round = 0; round < 8; round++) {
            String goal = GoalQa.goal(a, "Arquivar " + round, "9000.00", null);
            List<Callable<Res>> tasks = List.of(
                    () -> GoalQa.reserve(a, goal, a.nubank, "100.00", today),
                    () -> a.call(HttpMethod.POST, "/v1/goals/" + goal + "/archive", null, null),
                    () -> GoalQa.reserve(a, goal, a.nubank, "50.00", today));
            List<Res> results = race(tasks);
            assertNoServerError("reserva x arquivar", results);
            for (Res r : results) {
                assertThat(r.status() == 200 || (r.status() == 422 && "GOAL_ARCHIVED".equals(r.code()))).as(r.toString()).isTrue();
            }
            assertThat(results.get(1).status()).isEqualTo(200);
            long applied = jdbc.queryForObject("select count(*) from goal_reservations where user_id = ? and goal_id = ?::uuid", Long.class, a.id, goal);
            assertThat(applied).as("reservas gravadas = respostas 200 das reservas").isEqualTo(results.stream().filter(r -> r == results.get(0) || r == results.get(2))
                    .filter(r -> r.status() == 200).count());
            Res late = GoalQa.reserve(a, goal, a.nubank, "1.00", today);
            assertThat(late.status()).isEqualTo(422);
            assertThat(late.code()).isEqualTo("GOAL_ARCHIVED");
        }
    }

    @Test
    @DisplayName("reserva x arquivamento da conta: sem 500; depois de arquivada só a liberação passa")
    void reservaXArquivarConta() throws Exception {
        for (int round = 0; round < 8; round++) {
            String second = GoalQa.account(a, "Segunda " + round, "CHECKING", "0.00");
            String goal = GoalQa.goal(a, "Conta " + round, "9000.00", null);
            List<Callable<Res>> tasks = List.of(
                    () -> GoalQa.reserve(a, goal, second, "100.00", today),
                    () -> a.call(HttpMethod.POST, "/v1/accounts/" + second + "/archive", null, null),
                    () -> GoalQa.reserve(a, goal, second, "30.00", today));
            List<Res> results = race(tasks);
            assertNoServerError("reserva x arquivar conta", results);
            for (Res r : results) {
                assertThat(r.status()).as(r.toString()).isIn(200, 422, 409);
            }
            String status = a.getOk("/v1/accounts/" + second).get("status").asString();
            long net = GoalQa.net(jdbc, a, goal, second);
            // depois de arquivada: reservar é recusado, liberar é aceito
            if ("ARCHIVED".equals(status)) {
                Res again = GoalQa.reserve(a, goal, second, "1.00", today);
                assertThat(again.status()).as(again.toString()).isEqualTo(422);
                assertThat(again.code()).isEqualTo("ACCOUNT_ARCHIVED");
                if (net > 0) {
                    CardQaWorld.ok(GoalQa.reserve(a, goal, second, CardQaWorld.money(-net), today));
                    assertThat(GoalQa.net(jdbc, a, goal, second)).isZero();
                }
            }
        }
    }

    @Test
    @DisplayName("reserva x estorno de movimentação: a reserva nunca depende do razão; 4 rodadas sem 500 e o razão fecha")
    void reservaXEstorno() throws Exception {
        for (int round = 0; round < 4; round++) {
            String goal = GoalQa.goal(a, "Estorno " + round, "9000.00", null);
            Res expense = CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"19000.00\",\"date\":\"2026-10-10\",\"description\":\"Grande\","
                    + "\"accountId\":\"" + a.nubank + "\",\"categoryId\":\"" + a.food + "\",\"method\":\"PIX\"}", GoalQa.key()));
            String txId = expense.node().get("id").asString();
            long tx = a.count("transactions");
            List<Callable<Res>> tasks = List.of(
                    () -> GoalQa.reserve(a, goal, a.nubank, "5000.00", today),
                    () -> a.voidTx(txId),
                    () -> GoalQa.reserve(a, goal, a.nubank, "-1000.00", today),
                    () -> a.call(HttpMethod.GET, "/v1/dashboard/summary", null, null),
                    () -> a.call(HttpMethod.GET, "/v1/goals/" + goal, null, null));
            List<Res> results = race(tasks);
            assertNoServerError("reserva x estorno", results);
            for (Res r : results) {
                assertThat(r.status()).as(r.toString()).isIn(200, 409, 422);
            }
            assertThat(results.get(1).status()).isEqualTo(200);
            assertHealthy(tx);
            // com o saldo de volta a 20.000 a reserva não fica descoberta; coerência final
            JsonNode g = GoalQa.getGoal(a, goal);
            assertThat(g.get("current").get("amount").asString()).isEqualTo(CardQaWorld.money(GoalQa.net(jdbc, a, goal, a.nubank)));
        }
    }

    @Test
    @DisplayName("tempestade de 6 threads x 40 operações (reservar, liberar, arquivar, editar, estornar, fatura, mudança de dias, leituras): só 200/201/409/422, nunca 500; o estado fecha")
    void tempestade() throws Exception {
        String inv = GoalQa.account(a, "Invest", "INVESTMENT", "1000.00");
        List<String> goals = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            goals.add(GoalQa.goal(a, "Tempestade " + i, "30000.00", "2027-06-30"));
        }
        Res purchase = CardQaWorld.ok(a.purchase("600.00", 3, "2026-10-10", "Compra"));
        String purchaseId = purchase.node().get("id").asString();
        long txBefore = a.count("transactions");
        List<Res> all = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < 6; t++) {
            long seed = 5150 + t;
            tasks.add(() -> {
                Random rnd = new Random(seed);
                for (int i = 0; i < 40; i++) {
                    String goal = goals.get(rnd.nextInt(goals.size()));
                    int roll = rnd.nextInt(100);
                    Res r;
                    if (roll < 35) {
                        r = GoalQa.reserve(a, goal, rnd.nextBoolean() ? a.nubank : inv, CardQaWorld.money(100 + rnd.nextInt(200_000)), today.plusDays(rnd.nextInt(5) - 2));
                    } else if (roll < 60) {
                        r = GoalQa.reserve(a, goal, rnd.nextBoolean() ? a.nubank : inv, CardQaWorld.money(-(100 + rnd.nextInt(150_000))), today.plusDays(rnd.nextInt(5) - 2));
                    } else if (roll < 66) {
                        r = a.call(HttpMethod.POST, "/v1/goals/" + goal + (rnd.nextInt(4) == 0 ? "/archive" : "/unarchive"), null, null);
                    } else if (roll < 74) {
                        JsonNode g = a.getOk("/v1/goals/" + goal);
                        r = a.call(HttpMethod.PUT, "/v1/goals/" + goal, "{\"name\":\"T\",\"target\":\"" + CardQaWorld.money(100_000 + rnd.nextInt(3_000_000))
                                + "\",\"priority\":\"LOW\",\"version\":" + g.get("version").asLong() + "}", null);
                    } else if (roll < 80) {
                        r = a.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"" + CardQaWorld.money(100 + rnd.nextInt(5_000))
                                + "\",\"date\":\"2026-10-10\",\"description\":\"Gasto\",\"accountId\":\"" + a.nubank + "\",\"categoryId\":\"" + a.food
                                + "\",\"method\":\"PIX\"}", GoalQa.key());
                    } else if (roll < 84) {
                        r = a.voidTx(purchaseId);
                    } else if (roll < 88) {
                        r = a.changeDays(a.card, 1 + rnd.nextInt(28), 1 + rnd.nextInt(28));
                    } else if (roll < 92) {
                        List<JsonNode> invoices = a.invoices();
                        r = invoices.isEmpty() ? a.get("/v1/dashboard/summary") : a.pay(java.util.UUID.fromString(invoices.get(0).get("id").asString()), "1.00", "2026-10-10", null);
                    } else {
                        String url = new String[] {"/v1/dashboard/summary", "/v1/projections?horizon=M12&includeTrend=true", "/v1/summaries/2026-10", "/v1/goals",
                                "/v1/accounts", "/v1/exports/transactions.csv", "/v1/exports/backup"}[rnd.nextInt(7)];
                        r = a.get(url);
                    }
                    all.add(r);
                }
                return null;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> t : tasks) {
                futures.add(pool.submit(t));
            }
            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("QA-E5-STORM " + histogram(all));
        assertNoServerError("tempestade", all);
        // 404 INVOICE_NOT_FOUND só no pagamento de fatura: o id da fatura vem da data de fechamento, e a "mudança de dias" de outra thread entre a listagem
        // e o pagamento faz o id listado deixar de existir (corrida do próprio teste, não defeito); qualquer outro 404 continua sendo falha
        assertThat(all.stream().filter(r -> r.status() != 200 && r.status() != 201 && r.status() != 409 && r.status() != 422
                && !(r.status() == 404 && "INVOICE_NOT_FOUND".equals(r.code()))).map(Res::toString).toList()).isEmpty();
        assertThat(a.ledgerSum()).isZero();
        assertThat(a.unbalancedGroups()).isZero();
        assertThat(CardInvariants.check(a, a.card)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from goal_reservations where user_id = ?", Long.class, a.id)).isPositive();
        // nenhuma meta/conta com saldo de reserva negativo em alguma data
        List<Map<String, Object>> rows = jdbc.queryForList("select goal_id::text g, account_id::text a, reserved_on d, sum(amount_cents) c from goal_reservations "
                + "where user_id = ? group by 1,2,3 order by 1,2,3", a.id);
        Map<String, Long> running = new java.util.HashMap<>();
        for (Map<String, Object> r : rows) {
            long v = running.merge(r.get("g") + "|" + r.get("a"), ((Number) r.get("c")).longValue(), Long::sum);
            assertThat(v).as("saldo da reserva " + r.get("g") + " até " + r.get("d")).isGreaterThanOrEqualTo(0);
        }
        assertThat(world.contractViolations.stream().filter(v -> !v.contains("-> 409")).toList()).isEmpty();
        assertThat(txBefore).isPositive();
    }
}
