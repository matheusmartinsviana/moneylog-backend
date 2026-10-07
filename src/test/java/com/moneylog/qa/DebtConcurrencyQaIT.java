package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
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
 * T82 (QA), critério 3: concorrência da Etapa 4. Quitações simultâneas, quitação x estorno da origem, estorno concorrente de
 * despesa dividida, realização concorrente (e realize x skip, realize x estorno), recorrência x cartão, criação concorrente de
 * dívida para a mesma origem (V4.01 tornou o índice não único) e uma tempestade de escritas misturadas. Toda falha de trava
 * tem de ser 409 CONFLICT, nunca 500, e o razão tem de fechar em zero no fim.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T82: concorrência de dívidas, divisão e recorrências")
class DebtConcurrencyQaIT {

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
    private String joao;
    private String maria;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("e4cc");
        joao = DebtQa.person(a, "João");
        maria = DebtQa.person(a, "Maria");
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

    private static Map<Integer, Integer> histogram(List<Res> results) {
        Map<Integer, Integer> h = new TreeMap<>();
        results.forEach(r -> h.merge(r.status(), 1, Integer::sum));
        return h;
    }

    private static void assertNoServerError(String label, List<Res> results) {
        assertThat(results.stream().filter(r -> r.status() >= 500).map(Res::toString).toList()).as(label).isEmpty();
    }

    /** O estado final de qualquer corrida: razão em zero, dívidas coerentes com os livros, nenhuma ocorrência realizada duas vezes. */
    private void assertHealthy() throws Exception {
        assertThat(a.ledgerSum()).as("razão").isZero();
        assertThat(a.unbalancedGroups()).as("grupos desbalanceados").isZero();
        assertThat(CardInvariants.check(a, a.card)).isEmpty();
        long recv = 0;
        long pay = 0;
        for (JsonNode d : DebtQa.debts(a)) {
            long outstanding = CardQaWorld.cents(d.get("outstanding"));
            long principal = CardQaWorld.cents(d.get("principal"));
            long settled = CardQaWorld.cents(d.get("settled"));
            assertThat(outstanding).as("outstanding de " + d.get("id")).isGreaterThanOrEqualTo(0);
            assertThat(settled).as("settled de " + d.get("id")).isLessThanOrEqualTo(principal);
            String status = d.get("status").asString();
            if ("OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status)) {
                if ("RECEIVABLE".equals(d.get("direction").asString())) {
                    recv += outstanding;
                } else {
                    pay += outstanding;
                }
            }
        }
        assertThat(book("RECEIVABLE")).as("livro RECEIVABLE").isEqualTo(recv);
        assertThat(book("PAYABLE")).as("livro PAYABLE").isEqualTo(-pay);
        Long dup = jdbc.queryForObject("select count(*) from (select recurrence_id, occurrence_date from transactions where user_id = ? and status = 'ACTIVE' "
                + "and recurrence_id is not null group by 1, 2 having count(*) > 1) t", Long.class, a.id);
        assertThat(dup).as("ocorrência realizada duas vezes").isZero();
        Long skipAndReal = jdbc.queryForObject("select count(*) from recurrence_skips s join transactions t on t.recurrence_id = s.recurrence_id "
                + "and t.occurrence_date = s.occurrence_date and t.status = 'ACTIVE' where s.user_id = ?", Long.class, a.id);
        assertThat(skipAndReal).as("pulada e realizada").isZero();
        // BUG-QA-25 (contrato): POST /v1/transactions/{id}/void não declara 409, que a S-220 manda devolver em falha de trava
        assertThat(world.contractViolations.stream().filter(v -> !(v.contains("/void -> 409"))).toList()).isEmpty();
    }

    private long book(String type) {
        Long v = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = ?", Long.class, a.id, type);
        return v == null ? 0 : v;
    }

    private String internet(String start) throws Exception {
        return CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "119.00", "Internet", a.food,
                DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", start, null), null)).node().get("id").asString();
    }

    private String streaming() throws Exception {
        return CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("CARD_PURCHASE", "49.90", "Streaming", a.tech,
                "\"cardId\":\"" + a.card + "\"", "MONTHLY", "2026-09-15", null), null)).node().get("id").asString();
    }

    private String loanDebt(String amount, String personId) throws Exception {
        Res r = CardQaWorld.ok(DebtQa.loan(a, amount, "2026-10-10", personId, "2026-11-05"));
        return r.node().get("debt").get("id").asString();
    }

    // ------------------------------------------------------------------------------------------------ quitações

    @Test
    @DisplayName("8 quitações simultâneas de 100,00 numa dívida de 300,00: exatamente 3 vencem, as outras 422; saldo 0; sem 5xx")
    void quitacoesConcorrentesDaMesmaDivida() throws Exception {
        for (int round = 0; round < 3; round++) {
            String debt = loanDebt("300.00", joao);
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> DebtQa.received(a, "100.00", "2026-10-10", debt));
            }
            List<Res> results = race(tasks);
            assertNoServerError("quitações", results);
            Map<Integer, Integer> h = histogram(results);
            assertThat(h.getOrDefault(201, 0)).as("histograma " + h).isEqualTo(3);
            assertThat(h.getOrDefault(422, 0) + h.getOrDefault(409, 0)).as("histograma " + h).isEqualTo(5);
            JsonNode d = a.getOk("/v1/debts/" + debt);
            assertThat(CardQaWorld.cents(d.get("outstanding"))).isZero();
            assertThat(d.get("status").asString()).isEqualTo("SETTLED");
            assertHealthy();
        }
    }

    @Test
    @DisplayName("quitações a maior simultâneas (cada uma cabe sozinha, juntas não): nunca passa do principal")
    void quitacoesQueJuntasPassam() throws Exception {
        String debt = loanDebt("100.00", joao);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> DebtQa.received(a, "60.00", "2026-10-10", debt));
        }
        List<Res> results = race(tasks);
        assertNoServerError("quitações a maior", results);
        assertThat(histogram(results).getOrDefault(201, 0)).isEqualTo(1);
        assertThat(CardQaWorld.cents(a.getOk("/v1/debts/" + debt).get("outstanding"))).isEqualTo(4000L);
        assertHealthy();
    }

    @Test
    @DisplayName("quitação x estorno da origem (empréstimo): nunca os dois dão certo; sem 5xx; estado coerente (12 rodadas)")
    void quitacaoContraEstornoDaOrigem() throws Exception {
        List<String> failures = new ArrayList<>();
        for (int round = 0; round < 12; round++) {
            Res loan = CardQaWorld.ok(DebtQa.loan(a, "300.00", "2026-10-10", joao, "2026-11-05"));
            String origin = loan.node().get("id").asString();
            String debt = loan.node().get("debt").get("id").asString();
            List<Res> r = race(List.of(() -> DebtQa.received(a, "100.00", "2026-10-10", debt), () -> a.voidTx(origin)));
            assertNoServerError("rodada " + round, r);
            boolean paid = r.get(0).status() == 201;
            boolean voided = r.get(1).status() == 200;
            if (paid && voided) {
                failures.add("rodada " + round + ": quitação e estorno da origem ambos deram certo: " + r);
            }
            JsonNode d = a.getOk("/v1/debts/" + debt);
            if (voided && !"VOIDED".equals(d.get("status").asString())) {
                failures.add("rodada " + round + ": origem estornada mas dívida " + d.get("status"));
            }
            if (paid && !voided && CardQaWorld.cents(d.get("settled")) != 10_000L) {
                failures.add("rodada " + round + ": quitação aceita mas settled " + d.get("settled"));
            }
        }
        assertThat(failures).isEmpty();
        assertHealthy();
    }

    @Test
    @DisplayName("quitação x perdão e quitação x estorno do registro (dívida sem movimentação): sem 5xx, coerente")
    void quitacaoContraPerdao() throws Exception {
        for (int round = 0; round < 8; round++) {
            String debt = CardQaWorld.ok(DebtQa.plainDebt(a, joao, "RECEIVABLE", "200.00", "2026-10-10", "2026-11-05", null)).node().get("id").asString();
            boolean forgive = round % 2 == 0;
            List<Res> r = race(List.of(() -> DebtQa.received(a, "50.00", "2026-10-10", debt),
                    () -> forgive ? DebtQa.cancelDebt(a, debt) : DebtQa.voidDebt(a, debt)));
            assertNoServerError("rodada " + round, r);
            JsonNode d = a.getOk("/v1/debts/" + debt);
            if (r.get(0).status() == 201 && r.get(1).status() == 200 && !forgive) {
                throw new AssertionError("estorno do registro aceito com quitação: " + d);
            }
            assertThat(CardQaWorld.cents(d.get("outstanding"))).isGreaterThanOrEqualTo(0);
        }
        assertHealthy();
    }

    @Test
    @DisplayName("despesa dividida: 6 estornos simultâneos (1 vence, 5 recusados), e estorno x quitação de uma parte (nunca os dois)")
    void estornoConcorrenteDeDespesaDividida() throws Exception {
        for (int round = 0; round < 6; round++) {
            Res tx = CardQaWorld.ok(DebtQa.sharedExpense(a, "200.00", "2026-10-10", List.of(DebtQa.shareJson("50.00", joao), DebtQa.shareJson("30.00", maria))));
            String id = tx.node().get("id").asString();
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                tasks.add(() -> a.voidTx(id));
            }
            List<Res> r = race(tasks);
            assertNoServerError("estornos", r);
            assertThat(histogram(r).getOrDefault(200, 0)).as(histogram(r).toString()).isEqualTo(1);
        }
        assertHealthy();
        List<String> failures = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            Res tx = CardQaWorld.ok(DebtQa.sharedExpense(a, "200.00", "2026-10-10", List.of(DebtQa.shareJson("50.00", joao), DebtQa.shareJson("30.00", maria))));
            String id = tx.node().get("id").asString();
            String share = tx.node().get("shares").get(0).get("id").asString();
            List<Res> r = race(List.of(() -> a.voidTx(id), () -> DebtQa.received(a, "20.00", "2026-10-10", share)));
            assertNoServerError("rodada " + round, r);
            if (r.get(0).status() == 200 && r.get(1).status() == 201) {
                failures.add("rodada " + round + ": estorno da despesa dividida e quitação da parte ambos deram certo");
            }
            // estorno vencedor: as duas dívidas devem estar VOIDED; quitação vencedora: despesa continua ativa
            if (r.get(0).status() == 200) {
                for (JsonNode s : tx.node().get("shares")) {
                    String st = a.getOk("/v1/debts/" + s.get("id").asString()).get("status").asString();
                    if (!"VOIDED".equals(st)) {
                        failures.add("rodada " + round + ": despesa estornada mas dívida " + st);
                    }
                }
            }
        }
        assertThat(failures).isEmpty();
        assertHealthy();
    }

    @Test
    @DisplayName("reembolso da despesa dividida x estorno dela x quitação da parte: sem 5xx e consumo nunca negativo")
    void reembolsoDaDespesaDividida() throws Exception {
        for (int round = 0; round < 8; round++) {
            Res tx = CardQaWorld.ok(DebtQa.sharedExpense(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("33.33", joao), DebtQa.shareJson("33.33", maria))));
            String id = tx.node().get("id").asString();
            String share = tx.node().get("shares").get(0).get("id").asString();
            List<Res> r = race(List.of(() -> DebtQa.accountRefund(a, "20.00", "2026-10-10", a.food, id), () -> a.voidTx(id),
                    () -> DebtQa.received(a, "33.33", "2026-10-10", share), () -> DebtQa.accountRefund(a, "20.00", "2026-10-10", a.food, id)));
            assertNoServerError("rodada " + round, r);
            Long negative = jdbc.queryForObject("select count(*) from (select competence, sum(amount_cents) s from ledger_postings where user_id = ? and active "
                    + "and book_type = 'EXPENSE' group by competence having sum(amount_cents) < 0) t", Long.class, a.id);
            assertThat(negative).isZero();
        }
        assertHealthy();
    }

    // ------------------------------------------------------------------------------------------------ recorrências

    @Test
    @DisplayName("8 realizações simultâneas da mesma ocorrência (conta): 1 x 201 e 7 x 422; uma só movimentação")
    void realizacaoConcorrenteEmConta() throws Exception {
        String id = internet("2026-10-10");
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> DebtQa.realize(a, id, "2026-10-10", null));
        }
        List<Res> r = race(tasks);
        assertNoServerError("realize", r);
        assertThat(histogram(r).getOrDefault(201, 0)).as(histogram(r).toString()).isEqualTo(1);
        assertThat(r.stream().filter(x -> x.status() == 422).allMatch(x -> "RECURRENCE_OCCURRENCE_ALREADY_REALIZED".equals(x.code()))).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ? and recurrence_id is not null", Long.class, a.id)).isEqualTo(1L);
        assertHealthy();
    }

    @Test
    @DisplayName("8 realizações simultâneas da mesma ocorrência no cartão: 1 x 201, uma só compra, uma fatura coerente")
    void realizacaoConcorrenteNoCartao() throws Exception {
        String id = streaming();
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> DebtQa.realize(a, id, "2026-10-15", null));
        }
        List<Res> r = race(tasks);
        assertNoServerError("realize cartão", r);
        assertThat(histogram(r).getOrDefault(201, 0)).as(histogram(r).toString()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ? and recurrence_id is not null", Long.class, a.id)).isEqualTo(1L);
        assertHealthy();
    }

    @Test
    @DisplayName("realize x skip da mesma ocorrência (12 rodadas) e skip x skip: nunca os dois; sem 5xx")
    void realizeContraSkip() throws Exception {
        List<String> failures = new ArrayList<>();
        String id = internet("2026-10-10");
        for (int round = 0; round < 12; round++) {
            String date = LocalDate.of(2026, 10, 10).plusMonths(round).toString();
            List<Res> r = race(List.of(() -> DebtQa.realize(a, id, date, null), () -> DebtQa.skip(a, id, date), () -> DebtQa.skip(a, id, date)));
            assertNoServerError("rodada " + round, r);
            boolean realized = r.get(0).status() == 201;
            long skips = r.subList(1, 3).stream().filter(x -> x.status() == 200).count();
            if (realized && skips > 0) {
                failures.add("rodada " + round + ": realize e skip ambos deram certo " + r);
            }
            if (!realized && skips != 1 && skips != 2) {
                failures.add("rodada " + round + ": nenhum dos dois: " + r);
            }
        }
        assertThat(failures).isEmpty();
        assertHealthy();
    }

    @Test
    @DisplayName("realize x estorno da movimentação já realizada (reabrir, S-49) x novo realize: ocorrência acaba com no máximo 1 movimentação ativa")
    void realizeContraEstornoDaRealizada() throws Exception {
        String id = internet("2026-10-10");
        for (int round = 0; round < 10; round++) {
            String date = LocalDate.of(2026, 10, 10).plusMonths(round).toString();
            Res first = CardQaWorld.ok(DebtQa.realize(a, id, date, null));
            String tx = first.node().get("id").asString();
            List<Res> r = race(List.of(() -> a.voidTx(tx), () -> DebtQa.realize(a, id, date, null), () -> DebtQa.realize(a, id, date, "130.00")));
            assertNoServerError("rodada " + round, r);
            Long active = jdbc.queryForObject("select count(*) from transactions where user_id = ? and recurrence_id = ?::uuid and occurrence_date = ?::date and status = 'ACTIVE'",
                    Long.class, a.id, id, date);
            assertThat(active).as("ativas na rodada " + round + " " + r).isLessThanOrEqualTo(1L);
        }
        assertHealthy();
    }

    @Test
    @DisplayName("recorrência no cartão x compra x pagamento da fatura x mudança de dias, simultâneos (8 rodadas): sem 5xx, invariante do cartão fecha")
    void recorrenciaContraCartao() throws Exception {
        String rec = streaming();
        CardQaWorld.ok(a.purchase("500.00", 3, "2026-10-01", "Base"));
        for (int round = 0; round < 8; round++) {
            String date = LocalDate.of(2026, 10, 15).plusMonths(round).toString();
            int closing = round % 2 == 0 ? 10 : 25;
            UUID invoice = a.invoiceId(LocalDate.of(2026, 10, 25));
            List<Res> r = race(List.of(() -> DebtQa.realize(a, rec, date, null), () -> a.purchase("80.00", 2, "2026-10-12", "Concorrente"),
                    () -> a.pay(invoice, "100.00", "2026-10-10", null), () -> a.changeDays(a.card, closing, 5),
                    () -> DebtQa.realize(a, rec, date, "55.00")));
            assertNoServerError("rodada " + round, r);
            for (Res x : r) {
                assertThat(x.status()).as(x.toString()).isIn(200, 201, 409, 422);
            }
        }
        assertHealthy();
    }

    // ------------------------------------------------------------------------------------------------ mesma origem

    @Test
    @DisplayName("criação de dívida para a mesma origem: 8 empréstimos com a mesma Idempotency-Key = 1 movimentação e 1 dívida; 8 despesas divididas idem; 6 LOAN_GIVEN com debtId existente = 422 DEBT_ALREADY_HAS_ORIGIN")
    void mesmaOrigem() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = DebtQa.loanBody(a, "150.00", "2026-10-10", joao, null);
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> a.call(HttpMethod.POST, "/v1/transactions", body, key));
        }
        List<Res> r = race(tasks);
        assertNoServerError("empréstimo idempotente", r);
        for (Res x : r) {
            assertThat(x.status()).as(x.toString()).isIn(201, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from debts where user_id = ?", Long.class, a.id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ? and type = 'LOAN_GIVEN'", Long.class, a.id)).isEqualTo(1L);

        String key2 = UUID.randomUUID().toString();
        String shared = DebtQa.sharedExpenseBody(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("20.00", joao), DebtQa.shareJson("20.00", maria), DebtQa.shareJson("10.00", joao)));
        tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> a.call(HttpMethod.POST, "/v1/transactions", shared, key2));
        }
        r = race(tasks);
        assertNoServerError("despesa dividida idempotente", r);
        assertThat(jdbc.queryForObject("select count(*) from debts where user_id = ?", Long.class, a.id)).as("1 do empréstimo + 3 partes").isEqualTo(4L);
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id = ? and type = 'EXPENSE'", Long.class, a.id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select max(c) from (select count(*) c from debts where user_id = ? and origin_transaction_id is not null group by origin_transaction_id) t",
                Long.class, a.id)).isEqualTo(3L);

        String existing = jdbc.queryForObject("select id::text from debts where user_id = ? limit 1", String.class, a.id);
        tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> createLoanToExistingDebt(existing));
        }
        r = race(tasks);
        assertNoServerError("origem repetida", r);
        for (Res x : r) {
            assertThat(x.status()).as(x.toString()).isEqualTo(422);
            assertThat(x.code()).isEqualTo("DEBT_ALREADY_HAS_ORIGIN");
        }
        assertHealthy();
    }

    private Res createLoanToExistingDebt(String debtId) throws Exception {
        return DebtQa.createTx(a, "{\"type\":\"LOAN_GIVEN\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + a.nubank
                + "\",\"debtId\":\"" + debtId + "\"}");
    }

    @Test
    @DisplayName("arquivar a pessoa x criar dívida/empréstimo/divisão para ela (cruzado João/Maria, 10 rodadas): sem 5xx e nenhuma dívida nova em pessoa arquivada depois do arquivamento")
    void arquivarContraCriarDivida() throws Exception {
        for (int round = 0; round < 10; round++) {
            String p = DebtQa.person(a, "P" + round);
            String q = DebtQa.person(a, "Q" + round);
            List<Res> r = race(List.of(() -> a.call(HttpMethod.POST, "/v1/people/" + p + "/archive", "", null),
                    () -> DebtQa.loan(a, "10.00", "2026-10-10", p, null),
                    () -> DebtQa.sharedExpense(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("10.00", q), DebtQa.shareJson("10.00", p))),
                    () -> DebtQa.sharedExpense(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("10.00", p), DebtQa.shareJson("10.00", q))),
                    () -> a.call(HttpMethod.POST, "/v1/people/" + q + "/archive", "", null),
                    () -> DebtQa.plainDebt(a, q, "PAYABLE", "10.00", "2026-10-10", null, null)));
            assertNoServerError("rodada " + round, r);
            for (Res x : r) {
                assertThat(x.status()).as(x.toString()).isIn(200, 201, 409, 422);
            }
        }
        assertHealthy();
    }


    @Test
    @DisplayName("leituras (summary, upcoming, entries, lista) durante escritas de dívida/perdão/estorno: nunca 5xx")
    void leiturasContraEscritas() throws Exception {
        for (int i = 0; i < 6; i++) {
            CardQaWorld.ok(DebtQa.loan(a, "300.00", "2026-10-10", joao, "2026-11-05"));
        }
        List<Callable<List<Res>>> tasks = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int seed = t;
            tasks.add(() -> {
                List<Res> out = new ArrayList<>();
                Random rnd = new Random(seed);
                for (int i = 0; i < 25; i++) {
                    List<String> debtIds = jdbc.queryForList("select id::text from debts where user_id = ?", String.class, a.id);
                    int op = rnd.nextInt(4);
                    if (op == 0) {
                        out.add(DebtQa.loan(a, CardQaWorld.money(1000 + rnd.nextInt(5000)), "2026-10-10", joao, null));
                    } else if (op == 1) {
                        out.add(DebtQa.cancelDebt(a, debtIds.get(rnd.nextInt(debtIds.size()))));
                    } else if (op == 2) {
                        out.add(DebtQa.sharedExpense(a, "100.00", "2026-10-10", List.of(DebtQa.shareJson("10.00", joao))));
                    } else {
                        List<String> txs = jdbc.queryForList("select id::text from transactions where user_id = ?", String.class, a.id);
                        out.add(a.voidTx(txs.get(rnd.nextInt(txs.size()))));
                    }
                }
                return out;
            });
        }
        for (int t = 0; t < 4; t++) {
            tasks.add(() -> {
                List<Res> out = new ArrayList<>();
                for (int i = 0; i < 25; i++) {
                    out.add(a.get("/v1/dashboard/summary"));
                    out.add(a.get("/v1/dashboard/upcoming"));
                    out.add(a.get("/v1/entries?competence=2026-10"));
                    out.add(a.get("/v1/debts"));
                    out.add(a.get("/v1/people/" + joao));
                    out.add(a.get("/v1/transactions"));
                }
                return out;
            });
        }
        List<Res> all = new ArrayList<>();
        race(tasks).forEach(all::addAll);
        System.out.println("QA-E4-READS " + histogram(all) + " 5xx em: " + all.stream().filter(r -> r.status() >= 500).map(r -> r.text().replaceAll(".*\"instance\":\"([^\"]*)\".*", "$1")).distinct().toList());
        assertNoServerError("leituras x escritas", all);
    }


    @Test
    @DisplayName("pagar fatura / mudar dias do cartão (leem o razão inteiro) x perdão, empréstimo e estorno de dívida em paralelo: nunca 5xx (leitura inconsistente do razão)")
    void cartaoContraEscritasDeDivida() throws Exception {
        CardQaWorld.ok(a.purchase("500.00", 2, "2026-10-05", "Base"));
        for (int i = 0; i < 6; i++) {
            CardQaWorld.ok(DebtQa.loan(a, "300.00", "2026-10-10", joao, "2026-11-05"));
        }
        List<Callable<List<Res>>> tasks = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int seed = t;
            tasks.add(() -> {
                List<Res> out = new ArrayList<>();
                Random rnd = new Random(seed);
                for (int i = 0; i < 30; i++) {
                    List<String> debtIds = jdbc.queryForList("select id::text from debts where user_id = ?", String.class, a.id);
                    if (rnd.nextBoolean()) {
                        out.add(DebtQa.cancelDebt(a, debtIds.get(rnd.nextInt(debtIds.size()))));
                    } else {
                        out.add(DebtQa.loan(a, CardQaWorld.money(1000 + rnd.nextInt(5000)), "2026-10-10", joao, null));
                    }
                }
                return out;
            });
        }
        for (int t = 0; t < 4; t++) {
            final int seed = t;
            tasks.add(() -> {
                List<Res> out = new ArrayList<>();
                Random rnd = new Random(100 + seed);
                for (int i = 0; i < 30; i++) {
                    out.add(rnd.nextBoolean() ? a.pay(a.invoiceId(LocalDate.of(2026, 10, 25)), "10.00", "2026-10-10", null) : a.changeDays(a.card, 1 + rnd.nextInt(28), 1 + rnd.nextInt(28)));
                }
                return out;
            });
        }
        List<Res> all = new ArrayList<>();
        race(tasks).forEach(all::addAll);
        System.out.println("QA-E4-CARDREAD " + histogram(all));
        assertNoServerError("cartão x dívida", all);
    }

    // ------------------------------------------------------------------------------------------------ tempestade

    @Test
    @DisplayName("tempestade: 12 threads x 25 escritas aleatórias misturadas (empréstimo, quitação, perdão, divisão, estorno, realize, skip, compra, pagamento, mudança de dias): sem 5xx e estado final coerente")
    void tempestade() throws Exception {
        for (int i = 0; i < 6; i++) {
            Res loan = CardQaWorld.ok(DebtQa.loan(a, "300.00", "2026-10-10", i % 2 == 0 ? joao : maria, "2026-11-05"));
            CardQaWorld.ok(DebtQa.sharedExpense(a, "120.00", "2026-10-10", List.of(DebtQa.shareJson("40.00", joao), DebtQa.shareJson("30.00", maria))));
            assertThat(loan.node().get("debt")).isNotNull();
        }
        String internet = internet("2026-10-10");
        String streaming = streaming();
        CardQaWorld.ok(a.purchase("700.00", 3, "2026-10-05", "Base"));
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<Res>>> futures = new ArrayList<>();
            CyclicBarrier barrier = new CyclicBarrier(threads);
            for (int t = 0; t < threads; t++) {
                final long seed = 1000 + t;
                futures.add(pool.submit(() -> {
                    barrier.await();
                    Random rnd = new Random(seed);
                    List<Res> out = new ArrayList<>();
                    for (int i = 0; i < 25; i++) {
                        out.add(randomWrite(rnd, internet, streaming));
                    }
                    return out;
                }));
            }
            List<Res> all = new ArrayList<>();
            for (Future<List<Res>> f : futures) {
                all.addAll(f.get());
            }
            System.out.println("QA-E4-STORM " + histogram(all));
            assertNoServerError("tempestade", all);
            for (Res x : all) {
                assertThat(x.status()).as(x.toString()).isNotEqualTo(400);
            }
        } finally {
            pool.shutdownNow();
        }
        assertHealthy();
    }

    private Res randomWrite(Random rnd, String internet, String streaming) throws Exception {
        List<String> debtIds = jdbc.queryForList("select id::text from debts where user_id = ?", String.class, a.id);
        List<String> txIds = jdbc.queryForList("select id::text from transactions where user_id = ?", String.class, a.id);
        String date = "2026-10-1" + rnd.nextInt(5);
        String person = rnd.nextBoolean() ? joao : maria;
        return switch (rnd.nextInt(12)) {
            case 0 -> DebtQa.loan(a, CardQaWorld.money(1000 + rnd.nextInt(30000)), date, person, null);
            case 1, 2 -> debtIds.isEmpty() ? DebtQa.loan(a, "10.00", date, person, null)
                    : DebtQa.received(a, CardQaWorld.money(1 + rnd.nextInt(15000)), date, debtIds.get(rnd.nextInt(debtIds.size())));
            case 3 -> debtIds.isEmpty() ? DebtQa.loan(a, "10.00", date, person, null) : DebtQa.cancelDebt(a, debtIds.get(rnd.nextInt(debtIds.size())));
            case 4 -> DebtQa.sharedExpense(a, "100.00", date, List.of(DebtQa.shareJson("10.00", person), DebtQa.shareJson("10.00", person.equals(joao) ? maria : joao)));
            case 5, 6 -> txIds.isEmpty() ? DebtQa.loan(a, "10.00", date, person, null) : a.voidTx(txIds.get(rnd.nextInt(txIds.size())));
            case 7 -> DebtQa.realize(a, internet, LocalDate.of(2026, 10, 10).plusMonths(rnd.nextInt(4)).toString(), null);
            case 8 -> rnd.nextBoolean() ? DebtQa.skip(a, internet, LocalDate.of(2026, 10, 10).plusMonths(rnd.nextInt(4)).toString())
                    : DebtQa.realize(a, streaming, LocalDate.of(2026, 10, 15).plusMonths(rnd.nextInt(3)).toString(), null);
            case 9 -> a.purchase(CardQaWorld.money(1000 + rnd.nextInt(20000)), 1 + rnd.nextInt(3), date, "Tempestade");
            case 10 -> a.pay(a.invoiceId(LocalDate.of(2026, 10, 25)), "50.00", "2026-10-10", null);
            default -> a.changeDays(a.card, 1 + rnd.nextInt(28), 1 + rnd.nextInt(28));
        };
    }
}
