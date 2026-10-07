package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

/** T82 (QA): pontos levantados pela revisão de código da Etapa 4 (A1, A2, B1 a B6) e corridas que faltavam. */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T82: achados da revisão (A1, A2, B1-B6) e corridas restantes")
class DebtReviewQaIT {

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

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("e4rev");
        joao = DebtQa.person(a, "João");
    }

    private String monthly(String amount, String start) throws Exception {
        return CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", amount, "Rec", a.food,
                DebtQa.accountTarget(a, "EXPENSE"), "MONTHLY", start, null), null)).node().get("id").asString();
    }

    @Test
    @DisplayName("A1: recorrência ENCERRADA (endDate passada) não entra no custo fixo mensal do summary")
    void a1CustoFixoComEncerrada() throws Exception {
        String id = monthly("100.00", "2026-01-10");
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences/" + id + "/end", "{\"endDate\":\"2026-03-10\"}", null));
        JsonNode summary = a.getOk("/v1/recurrences/summary");
        JsonNode rec = a.getOk("/v1/recurrences/" + id);
        System.out.println("QA-E4-A1 fixedCosts=" + summary.get("fixedCosts") + " monthlyEstimate=" + rec.get("monthlyEstimate") + " nextOccurrence=" + rec.get("nextOccurrence")
                + " active=" + rec.get("active"));
        assertThat(summary.get("fixedCosts").get("amount").asString()).as("encerrada ainda soma no custo fixo").isEqualTo("0.00");
        // S-235: o item reflete o encerramento (estado derivado e estimativa 0,00) sem divergir do /upcoming: as vencidas seguem pendentes (S-20)
        assertThat(rec.get("ended").asBoolean()).isTrue();
        assertThat(rec.get("monthlyEstimate").get("amount").asString()).isEqualTo("0.00");
        assertThat(rec.get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("A2: realizar e pular ocorrência de recorrência DESATIVADA deve ser recusado")
    void a2DesativadaRealizaEPula() throws Exception {
        String id = monthly("100.00", "2026-10-10");
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences/" + id + "/deactivate", "", null));
        Res realize = DebtQa.realize(a, id, "2026-10-10", null);
        Res skip = DebtQa.skip(a, id, "2026-11-10");
        System.out.println("QA-E4-A2 realize=" + realize.status() + " skip=" + skip.status() + " transações=" + a.count("transactions"));
        assertThat(realize.status()).as("realize em desativada: " + realize).isEqualTo(422);
        assertThat(skip.status()).as("skip em desativada: " + skip).isEqualTo(422);
        assertThat(realize.code()).isEqualTo("RECURRENCE_INACTIVE");
        assertThat(skip.code()).isEqualTo("RECURRENCE_INACTIVE");
        assertThat(a.count("transactions")).as("nada foi gravado no razão").isZero();
        assertThat(a.ledgerSum()).isZero();
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("B1: duas dívidas na mesma origem de empréstimo (forçadas por SQL, pois V4.01 permite): estornar a origem não dá 5xx e deixa as duas VOIDED")
    void b1OrigemDuplicada() throws Exception {
        Res loan = CardQaWorld.ok(DebtQa.loan(a, "100.00", "2026-10-10", joao, null));
        String origin = loan.node().get("id").asString();
        UUID twin = UUID.randomUUID();
        jdbc.update("insert into debts (id,user_id,person_id,direction,origin,principal_cents,currency,description,debt_date,origin_transaction_id,created_at,updated_at) "
                + "select ?,user_id,person_id,direction,origin,principal_cents,currency,'gêmea',debt_date,origin_transaction_id,created_at,updated_at from debts where origin_transaction_id = ?::uuid",
                twin, origin);
        Res detail = a.get("/v1/transactions/" + origin);
        Res list = a.get("/v1/debts");
        Res voided = a.voidTx(origin);
        System.out.println("QA-E4-B1 get=" + detail.status() + " list=" + list.status() + " void=" + voided.status() + " " + voided.code());
        assertThat(detail.status()).isLessThan(500);
        assertThat(list.status()).isLessThan(500);
        assertThat(voided.status()).as(voided.toString()).isLessThan(500);
        if (voided.status() == 200) {
            assertThat(jdbc.queryForObject("select count(*) from debts where origin_transaction_id = ?::uuid and closure is null", Long.class, origin))
                    .as("dívida gêmea continua aberta depois do estorno da origem").isZero();
        }
    }

    @Test
    @DisplayName("B2: POST /v1/debts repetido (duplo clique/retry) com a mesma Idempotency-Key deveria criar uma dívida só")
    void b2DividaSemIdempotencia() throws Exception {
        String body = "{\"personId\":\"" + joao + "\",\"direction\":\"PAYABLE\",\"principal\":\"80.00\",\"date\":\"2026-10-10\",\"description\":\"Jantar\",\"categoryId\":\"" + a.food + "\"}";
        String key = UUID.randomUUID().toString();
        Res r1 = a.call(HttpMethod.POST, "/v1/debts", body, key);
        Res r2 = a.call(HttpMethod.POST, "/v1/debts", body, key);
        System.out.println("QA-E4-B2 " + r1.status() + " " + r2.status() + " dívidas=" + a.count("debts"));
        assertThat(a.count("debts")).as("a mesma chave duplicou a dívida (e o consumo de 80,00)").isEqualTo(1L);
    }

    @Test
    @DisplayName("B3: PUT de recorrência encerrada sem endDate não reabre em silêncio (422 RECURRENCE_ENDED); remover o fim de uma em curso segue válido")
    void b3PutReabreEncerrada() throws Exception {
        String id = monthly("100.00", "2026-01-10");
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences/" + id + "/end", "{\"endDate\":\"2026-03-10\"}", null));
        JsonNode rec = a.getOk("/v1/recurrences/" + id);
        Res put = a.call(HttpMethod.PUT, "/v1/recurrences/" + id, "{\"amount\":\"110.00\",\"description\":\"Rec\",\"categoryId\":\"" + a.food + "\",\"accountId\":\"" + a.nubank
                + "\",\"method\":\"PIX\",\"version\":" + rec.get("version").asInt() + "}", null);
        System.out.println("QA-E4-B3 put=" + put.status() + " " + put.code());
        // S-236: reabrir em silêncio é recusado (422 RECURRENCE_ENDED) e nada muda; o fim só sai de uma recorrência ainda em curso
        assertThat(put.status()).as(put.toString()).isEqualTo(422);
        assertThat(put.code()).isEqualTo("RECURRENCE_ENDED");
        JsonNode after = a.getOk("/v1/recurrences/" + id);
        assertThat(after.get("endDate").asString()).isEqualTo("2026-03-10");
        assertThat(after.get("version").asInt()).isEqualTo(rec.get("version").asInt());
        assertThat(after.get("ended").asBoolean()).isTrue();
        // recorrência em curso com fim futuro: remover o fim continua válido
        String running = monthly("100.00", "2026-10-10");
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences/" + running + "/end", "{\"endDate\":\"2026-12-10\"}", null));
        JsonNode runningRec = a.getOk("/v1/recurrences/" + running);
        Res open = a.call(HttpMethod.PUT, "/v1/recurrences/" + running, "{\"amount\":\"110.00\",\"description\":\"Rec\",\"categoryId\":\"" + a.food + "\",\"accountId\":\"" + a.nubank
                + "\",\"method\":\"PIX\",\"version\":" + runningRec.get("version").asInt() + "}", null);
        assertThat(open.status()).as(open.toString()).isEqualTo(200);
        assertThat(open.node().get("endDate").isNull()).isTrue();
        assertThat(open.node().get("ended").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("B4: /occurrences sem teto de janela: 5 diárias desde 2000 com to=9999-12-31 (latência e tamanho)")
    void b4SemTetoDeJanela() throws Exception {
        for (int i = 0; i < 5; i++) {
            CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody("EXPENSE", "1.00", "d", a.food, DebtQa.accountTarget(a, "EXPENSE"), "DAILY", "2000-01-01", null), null));
        }
        for (String to : List.of("2100-12-31", "9999-12-31")) {
            long t0 = System.nanoTime();
            Res r = a.raw(HttpMethod.GET, "/v1/recurrences/occurrences?to=" + to + "&pageSize=50", null, null);
            System.out.println("QA-E4-B4 to=" + to + " status=" + r.status() + " " + (System.nanoTime() - t0) / 1_000_000 + "ms");
            assertThat(r.status()).isLessThan(500);
        }
        long t0 = System.nanoTime();
        Res up = a.raw(HttpMethod.GET, "/v1/dashboard/upcoming", null, null);
        System.out.println("QA-E4-B4 upcoming status=" + up.status() + " " + (System.nanoTime() - t0) / 1_000_000 + "ms bytes=" + up.text().length());
    }

    @Test
    @DisplayName("B5: debtItems vem completos em toda página de /v1/entries: concatenar páginas duplica as linhas de dívida")
    void b5DebtItemsRepetidos() throws Exception {
        for (int i = 0; i < 3; i++) {
            CardQaWorld.ok(DebtQa.plainDebt(a, joao, "PAYABLE", "10.00", "2026-10-10", null, a.food));
            CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"5.00\",\"date\":\"2026-10-10\",\"description\":\"x" + i + "\",\"accountId\":\"" + a.nubank
                    + "\",\"categoryId\":\"" + a.food + "\",\"method\":\"PIX\"}", UUID.randomUUID().toString()));
        }
        List<String> seen = new ArrayList<>();
        long sum = 0;
        for (int page = 1; page <= 3; page++) {
            JsonNode s = a.getOk("/v1/entries?competence=2026-10&pageSize=1&page=" + page);
            for (JsonNode e : s.get("items")) {
                sum += CardQaWorld.cents(e.get("amount"));
            }
            if (s.has("debtItems")) {
                for (JsonNode d : s.get("debtItems")) {
                    seen.add(d.get("debtId").asString());
                    sum += CardQaWorld.cents(d.get("amount"));
                }
            }
        }
        long total = CardQaWorld.cents(a.getOk("/v1/entries?competence=2026-10").get("totals").get("total"));
        System.out.println("QA-E4-B5 debtItems vistos=" + seen.size() + " Σ concatenado=" + sum + " total=" + total);
        assertThat(sum).as("Σ de páginas concatenadas != totals.total").isEqualTo(total);
    }

    @Test
    @DisplayName("B6: PUT de despesa dividida repetindo as mesmas shares (documentado como aceito) responde 200")
    void b6SharesIdenticasNoPut() throws Exception {
        Res tx = CardQaWorld.ok(DebtQa.sharedExpense(a, "200.00", "2026-10-10", List.of(DebtQa.shareJson("100.00", joao))));
        String put = "{\"type\":\"EXPENSE\",\"amount\":\"200.00\",\"date\":\"2026-10-10\",\"description\":\"Novo\",\"accountId\":\"" + a.nubank + "\",\"categoryId\":\"" + a.food
                + "\",\"method\":\"PIX\",\"version\":" + tx.node().get("version").asInt() + ",\"shares\":[" + DebtQa.shareJson("100.00", joao) + "]}";
        Res r = a.raw(HttpMethod.PUT, "/v1/transactions/" + tx.node().get("id").asString(), put, null);
        System.out.println("QA-E4-B6 " + r.status() + " " + r.code());
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
    }

    @Test
    @DisplayName("realize x deactivate da mesma recorrência (10 rodadas) e perdão x quitação: sem 5xx, estado coerente")
    void realizeContraDeactivate() throws Exception {
        for (int round = 0; round < 10; round++) {
            String id = monthly("100.00", "2026-10-10");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Callable<Res>> tasks = List.of(() -> DebtQa.realize(a, id, "2026-10-10", null), () -> a.call(HttpMethod.POST, "/v1/recurrences/" + id + "/deactivate", "", null));
            List<Future<Res>> fs = new ArrayList<>();
            for (Callable<Res> t : tasks) {
                fs.add(pool.submit(() -> {
                    barrier.await();
                    return t.call();
                }));
            }
            for (Future<Res> f : fs) {
                assertThat(f.get().status()).as(f.get().toString()).isLessThan(500);
            }
            pool.shutdownNow();
        }
        assertThat(a.ledgerSum()).isZero();
        assertThat(world.contractViolations).isEmpty();
    }
}
