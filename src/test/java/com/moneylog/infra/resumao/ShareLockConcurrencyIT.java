package com.moneylog.infra.resumao;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

@DisplayName("A2b/A4: concorrência nas travas da ADR-008 (compra, pessoa, conta) em recebimento, PUT shares e estorno")
class ShareLockConcurrencyIT extends ResumaoItBase {

    private static final int ROUNDS = 6;

    @BeforeEach
    void mundo() throws Exception {
        basicWorld();
    }

    private record Outcome(int status, String code) {
    }

    private String purchase() throws Exception {
        return created(sharedPurchase(cardA, "100.00", 1, "2026-10-05", "BPS", "[" + share(null, 5000) + "," + share(ana, 5000) + "]"))
                .get("id").asString();
    }

    private Outcome outcome(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        String text = response.getContentAsString();
        String code = text.isBlank() || !text.startsWith("{") ? "" : objectMapper.readTree(text).path("code").asString("");
        return new Outcome(response.getStatus(), code);
    }

    private Callable<Outcome> receipt(CountDownLatch start, String tx, String amount, String account) {
        String body = "{\"personId\":\"" + ana + "\",\"transactionId\":\"" + tx + "\",\"amount\":\"" + amount + "\","
                + (account == null ? "" : "\"accountId\":\"" + account + "\",") + "\"receivedOn\":\"2026-10-15\"}";
        String key = UUID.randomUUID().toString();
        return () -> {
            start.await();
            return outcome(mockMvc.perform(MockMvcRequestBuilders.post("/v1/third-party-receipts").header("Authorization", bearer)
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse());
        };
    }

    private Callable<Outcome> shares(CountDownLatch start, String tx, int anaBps, long version) {
        String body = "{\"splitMode\":\"BPS\",\"shares\":[" + share(null, 10000 - anaBps) + "," + share(ana, anaBps) + "],\"version\":" + version + "}";
        return () -> {
            start.await();
            return outcome(mockMvc.perform(MockMvcRequestBuilders.put("/v1/transactions/" + tx + "/shares").header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse());
        };
    }

    private Callable<Outcome> voidTx(CountDownLatch start, String tx) {
        return () -> {
            start.await();
            return outcome(mockMvc.perform(MockMvcRequestBuilders.post("/v1/transactions/" + tx + "/void").header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"corrida\"}")).andReturn().getResponse());
        };
    }

    private List<Outcome> run(List<Callable<Outcome>> calls, CountDownLatch start) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            calls.forEach(c -> futures.add(pool.submit(c)));
            start.countDown();
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("dois recebimentos de 30,00 sobre 50,00 devidos (conta x já no saldo): exatamente um vence e o outro é 422 DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING; nunca 500/409")
    void doisRecebimentosNuncaExcedem() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String tx = purchase();
            CountDownLatch start = new CountDownLatch(1);
            List<Outcome> results = run(List.of(receipt(start, tx, "30.00", accountA), receipt(start, tx, "30.00", null)), start);

            assertThat(results.stream().filter(o -> o.status() == 201)).as("rodada %d: %s", round, results).hasSize(1);
            assertThat(results.stream().filter(o -> o.status() == 422 && o.code().equals("DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING")))
                    .as("rodada %d: %s", round, results).hasSize(1);
            assertThat(book("RECEIVABLE", ana)).isGreaterThanOrEqualTo(0L);
            assertThat(ledgerTotal()).isZero();
        }
        assertThat(book("RECEIVABLE", ana)).isEqualTo(ROUNDS * 2000L);
    }

    @Test
    @DisplayName("seis recebimentos de 20,00 sobre 50,00 devidos, três de cada modo: só dois valem, a soma recebida nunca passa do devido")
    void tempestadeDeRecebimentos() throws Exception {
        for (int round = 0; round < 3; round++) {
            String tx = purchase();
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Outcome>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(receipt(start, tx, "20.00", i % 2 == 0 ? accountA : null));
            }
            List<Outcome> results = run(calls, start);
            assertThat(results).as("rodada %d", round).allSatisfy(o -> assertThat(o.status()).isIn(201, 422));
            assertThat(results.stream().filter(o -> o.status() == 201)).as("rodada %d: %s", round, results).hasSize(2);
            assertThat(ledgerTotal()).isZero();
        }
        assertThat(book("RECEIVABLE", ana)).isEqualTo(3 * 1000L);
    }

    @Test
    @DisplayName("recebimento de 50,00 × PUT shares da Ana para 30%: exatamente um vence (o outro 422 SHARES_BELOW_RECEIVED ou DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING); sem deadlock")
    void recebimentoContraPutShares() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String tx = purchase();
            long version = okBody(get("/v1/transactions/" + tx)).get("version").asLong();
            CountDownLatch start = new CountDownLatch(1);
            List<Outcome> results = run(List.of(receipt(start, tx, "50.00", accountA), shares(start, tx, 3000, version)), start);

            assertThat(results).as("rodada %d: %s", round, results).allSatisfy(o -> assertThat(o.status()).isIn(200, 201, 422));
            long wins = results.stream().filter(o -> o.status() == 200 || o.status() == 201).count();
            assertThat(wins).as("rodada %d: %s", round, results).isEqualTo(1);
            assertThat(results.stream().filter(o -> o.status() == 422).map(Outcome::code).findFirst().orElseThrow())
                    .isIn("SHARES_BELOW_RECEIVED", "DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING");
            assertThat(book("RECEIVABLE", ana)).as("nunca recebido acima do devido").isGreaterThanOrEqualTo(0L);
            assertThat(ledgerTotal()).isZero();
        }
    }

    @Test
    @DisplayName("estorno da compra × recebimento: sem 500/409; se os dois valem, o recebido vira PAYABLE e o RECEIVABLE da Ana zera; razão zero")
    void estornoContraRecebimento() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String tx = purchase();
            CountDownLatch start = new CountDownLatch(1);
            List<Outcome> results = run(List.of(receipt(start, tx, "30.00", accountA), voidTx(start, tx)), start);

            assertThat(results).as("rodada %d: %s", round, results).allSatisfy(o -> assertThat(o.status()).isIn(200, 201, 404, 422));
            assertThat(book("RECEIVABLE", ana)).as("rodada %d: %s", round, results).isZero();
            assertThat(ledgerTotal()).isZero();
            JsonNode state = okBody(get("/v1/transactions/" + tx));
            assertThat(state.get("status").asString()).isEqualTo("VOIDED");
            long payable = -book("PAYABLE", ana);
            long acceptedReceipts = jdbc.queryForObject("SELECT coalesce(sum(amount_cents),0) FROM third_party_receipts WHERE user_id = ? "
                    + "AND transaction_id = ?::uuid", Long.class, userId, tx);
            assertThat(payable).as("rodada %d: o PAYABLE acumulado cobre o recebido", round).isGreaterThanOrEqualTo(0L);
            assertThat(acceptedReceipts).isIn(0L, 3000L);
        }
    }

    @Test
    @DisplayName("recebimento × arquivar a conta recebedora: um vence; nunca recebimento gravado em conta arquivada")
    void recebimentoContraArquivarConta() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String tx = purchase();
            String account = createdBody(post("/v1/accounts", "{\"name\":\"Conta R" + round + "\",\"type\":\"CHECKING\",\"openingBalance\":"
                    + "{\"amount\":\"0.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
            CountDownLatch start = new CountDownLatch(1);
            Callable<Outcome> archive = () -> {
                start.await();
                return outcome(mockMvc.perform(MockMvcRequestBuilders.post("/v1/accounts/" + account + "/archive").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("")).andReturn().getResponse());
            };
            List<Outcome> results = run(List.of(receipt(start, tx, "10.00", account), archive), start);
            assertThat(results).as("rodada %d: %s", round, results).allSatisfy(o -> assertThat(o.status()).isIn(200, 201, 422));
            String status = okBody(get("/v1/accounts/" + account)).get("status").asString();
            long inAccount = book("ACCOUNT", account);
            assertThat(status.equals("ARCHIVED") && inAccount != 0).as("rodada %d: conta arquivada com saldo; %s", round, results).isFalse();
            assertThat(ledgerTotal()).isZero();
        }
    }
}
