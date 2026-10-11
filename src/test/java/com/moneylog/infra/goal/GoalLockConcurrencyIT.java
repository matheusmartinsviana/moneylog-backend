package com.moneylog.infra.goal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;

@DisplayName("Metas: concorrência de reserva × arquivar conta, × liberação e entre metas na mesma conta (A2, S-252)")
class GoalLockConcurrencyIT extends E4ItBase {

    private static final int ROUNDS = 8;

    private String newGoal(String name) throws Exception {
        return createdBody(post("/v1/goals", "{\"name\":\"" + name + "\",\"target\":\"20000.00\",\"priority\":\"HIGH\"}")).get("id").asString();
    }

    private String account(String name) throws Exception {
        return createdBody(post("/v1/accounts", "{\"name\":\"" + name + "\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"0.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
    }

    private Callable<int[]> reserve(CountDownLatch start, String goal, String account, String amount) {
        String body = "{\"accountId\":\"" + account + "\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\"}";
        UUID key = UUID.randomUUID();
        return () -> {
            start.await();
            var response = mockMvc.perform(MockMvcRequestBuilders.post("/v1/goals/" + goal + "/reservations")
                    .header("Authorization", bearer).header("Idempotency-Key", key.toString()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse();
            return new int[] {response.getStatus(), code(response.getContentAsString())};
        };
    }

    private Callable<int[]> archiveAccount(CountDownLatch start, String account) {
        return () -> {
            start.await();
            var response = mockMvc.perform(MockMvcRequestBuilders.post("/v1/accounts/" + account + "/archive").header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content("")).andReturn().getResponse();
            return new int[] {response.getStatus(), code(response.getContentAsString())};
        };
    }

    private int code(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.has("code") ? node.get("code").asString().hashCode() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static final int ARCHIVED = "ACCOUNT_ARCHIVED".hashCode();
    private static final int HAS_RESERVATIONS = "ACCOUNT_HAS_RESERVATIONS".hashCode();
    private static final int EXCEEDS = "GOAL_RELEASE_EXCEEDS_RESERVED".hashCode();

    private List<int[]> run(List<Callable<int[]>> calls, CountDownLatch start) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<int[]>> futures = new ArrayList<>();
            calls.forEach(c -> futures.add(pool.submit(c)));
            start.countDown();
            List<int[]> results = new ArrayList<>();
            for (Future<int[]> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("reserva × arquivar a mesma conta: exatamente um vence (200), o outro é 422 coerente (ACCOUNT_ARCHIVED ou ACCOUNT_HAS_RESERVATIONS); nunca 500/409 e nunca conta arquivada com reserva")
    void reservaXArquivarConta() throws Exception {
        String goal = newGoal("Viagem");
        int reserveWins = 0;
        int archiveWins = 0;
        for (int round = 0; round < ROUNDS; round++) {
            String account = account("Conta " + round);
            CountDownLatch start = new CountDownLatch(1);
            List<int[]> r = run(List.of(reserve(start, goal, account, "100.00"), archiveAccount(start, account)), start);
            int[] reserved = r.get(0);
            int[] archived = r.get(1);
            if (reserved[0] == 200) {
                reserveWins++;
                assertThat(archived).as("rodada %d: a reserva venceu", round).containsExactly(422, HAS_RESERVATIONS);
            } else {
                archiveWins++;
                assertThat(reserved).as("rodada %d: o arquivamento venceu", round).containsExactly(422, ARCHIVED);
                assertThat(archived[0]).isEqualTo(200);
            }
            String status = okBody(get("/v1/accounts/" + account)).get("status").asString();
            long reservedCents = jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM goal_reservations WHERE account_id = ?::uuid", Long.class, account);
            assertThat(status.equals("ARCHIVED") && reservedCents > 0).as("rodada %d: conta arquivada com reserva ativa", round).isFalse();
            assertThat(reserveWins + archiveWins).isEqualTo(round + 1);
        }
        assertThat(count("transactions")).isZero();
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("reserva × liberação na mesma meta e conta: as duas valem (200) em qualquer ordem, current final = 300,00 e a versão sobe 3; nunca 500/409")
    void reservaXLiberacao() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String goal = newGoal("Meta " + round);
            okBody(post("/v1/goals/" + goal + "/reservations", "{\"accountId\":\"" + nubank + "\",\"amount\":\"500.00\",\"date\":\"2026-10-10\"}")
                    .header("Idempotency-Key", UUID.randomUUID().toString()));
            CountDownLatch start = new CountDownLatch(1);
            List<int[]> r = run(List.of(reserve(start, goal, nubank, "300.00"), reserve(start, goal, nubank, "-500.00")), start);

            assertThat(r).as("rodada %d", round).allSatisfy(res -> assertThat(res[0]).isEqualTo(200));
            JsonNode detail = okBody(get("/v1/goals/" + goal));
            assertThat(detail.get("current").get("amount").asString()).isEqualTo("300.00");
            assertThat(detail.get("version").asLong()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("duas liberações de 500,00 sobre 500,00 reservados: uma 200 e uma 422 GOAL_RELEASE_EXCEEDS_RESERVED, saldo da reserva nunca negativo")
    void liberacoesCruzadas() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String goal = newGoal("Meta L" + round);
            okBody(post("/v1/goals/" + goal + "/reservations", "{\"accountId\":\"" + nubank + "\",\"amount\":\"500.00\",\"date\":\"2026-10-10\"}")
                    .header("Idempotency-Key", UUID.randomUUID().toString()));
            CountDownLatch start = new CountDownLatch(1);
            List<int[]> r = run(List.of(reserve(start, goal, nubank, "-500.00"), reserve(start, goal, nubank, "-500.00")), start);

            assertThat(r.stream().filter(x -> x[0] == 200)).as("rodada %d", round).hasSize(1);
            assertThat(r.stream().filter(x -> x[0] == 422 && x[1] == EXCEEDS)).hasSize(1);
            assertThat(okBody(get("/v1/goals/" + goal)).get("current").get("amount").asString()).isEqualTo("0.00");
        }
    }

    @Test
    @DisplayName("metas diferentes na mesma conta, com reservas e liberações cruzadas: só 200/422 (nunca 500 nem 409 de deadlock) e a soma bate com o banco")
    void metasDiferentesNaMesmaConta() throws Exception {
        List<String> goals = List.of(newGoal("A"), newGoal("B"), newGoal("C"));
        for (int round = 0; round < ROUNDS; round++) {
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<int[]>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String goal = goals.get(i % 3);
                calls.add(reserve(start, goal, nubank, i % 2 == 0 ? "100.00" : "-50.00"));
            }
            List<int[]> results = run(calls, start);

            assertThat(results).allSatisfy(res -> {
                assertThat(res[0]).isIn(200, 422);
                if (res[0] == 422) {
                    assertThat(res[1]).isEqualTo(EXCEEDS);
                }
            });
        }
        long dbTotal = jdbc.queryForObject("SELECT coalesce(sum(amount_cents), 0) FROM goal_reservations WHERE user_id = ?", Long.class, userId);
        long apiTotal = 0;
        for (String goal : goals) {
            apiTotal += Math.round(Double.parseDouble(okBody(get("/v1/goals/" + goal)).get("current").get("amount").asString()) * 100);
        }
        assertThat(apiTotal).isEqualTo(dbTotal);
        assertThat(count("transactions")).isZero();
    }
}
