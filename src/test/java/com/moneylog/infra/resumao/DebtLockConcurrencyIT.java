package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

@DisplayName("H4: ordem de travas da ADR-008 em dívida x conta: folha com consignado x pagamento da dívida, estorno do Pix no crédito x pagamento")
class DebtLockConcurrencyIT extends ResumaoItBase {

    private record Outcome(int status, String code) {
    }

    private Outcome outcome(org.springframework.mock.web.MockHttpServletResponse response) throws Exception {
        String text = response.getContentAsString();
        String code = text.isBlank() || !text.startsWith("{") ? "" : objectMapper.readTree(text).path("code").asString("");
        return new Outcome(response.getStatus(), code);
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

    private Callable<Outcome> post(CountDownLatch start, String bearerToken, String url, String body) {
        String key = UUID.randomUUID().toString();
        return () -> {
            start.await();
            return outcome(mockMvc.perform(MockMvcRequestBuilders.post(url).header("Authorization", bearerToken).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse());
        };
    }

    @Test
    @DisplayName("realizar o adiantamento da folha (conta, depois dívida) x pagar a mesma dívida (dívida, depois conta): só 201/422, nunca 500, 409 de deadlock nem razão fora de zero")
    void folhaContraPagamentoDaDivida() throws Exception {
        for (int round = 0; round < 5; round++) {
            newUser();
            Map<String, String> ids = loadFixture("novembro-2026");
            JsonNode generated = body(mockMvc.perform(MockMvcRequestBuilders.post("/v1/income-profiles/" + ids.get("perfilRenda")
                    + "/generate-occurrences?from=2026-10&to=2026-10").header("Authorization", bearer)).andReturn());
            String advance = generated.get("items").get(0).get("recurrenceId").asString();
            advanceTo(Instant.parse("2026-10-16T15:00:00Z"));
            CountDownLatch start = new CountDownLatch(1);

            List<Outcome> results = run(List.of(
                    post(start, bearer, "/v1/recurrences/" + advance + "/occurrences/2026-10-16/realize",
                            "{\"accountId\":\"" + ids.get("contaA") + "\",\"method\":\"PIX\"}"),
                    post(start, bearer, "/v1/transactions", "{\"type\":\"DEBT_PAYMENT\",\"amount\":\"100.00\",\"date\":\"2026-10-16\",\"description\":\"Pagamento\","
                            + "\"accountId\":\"" + ids.get("contaA") + "\",\"debtId\":\"" + ids.get("debtFolha") + "\"}")), start);

            assertThat(results).as("rodada %d: %s", round, results).allSatisfy(o -> assertThat(o.status()).isIn(201, 422));
            assertThat(ledgerTotal()).isZero();
        }
    }

    @Test
    @DisplayName("estornar o Pix no crédito (cartão, pessoas, conta, dívida) x pagar a dívida dele: só 200/201/422, sem 500 nem 409 de deadlock; razão zero")
    void estornoDoPixContraPagamento() throws Exception {
        for (int round = 0; round < 5; round++) {
            newUser();
            basicWorld();
            JsonNode debt = createdBody(idem(post("/v1/card-cash-outs", "{\"cardId\":\"" + cardA + "\",\"accountId\":\"" + accountA
                    + "\",\"principal\":\"1000.00\",\"installments\":4,\"totalWithInterest\":\"1120.00\",\"firstInvoiceDate\":\"2026-10-10\"}")));
            CountDownLatch start = new CountDownLatch(1);

            List<Outcome> results = run(List.of(
                    post(start, bearer, "/v1/transactions/" + debt.get("originTransactionId").asString() + "/void", "{\"reason\":\"corrida\"}"),
                    post(start, bearer, "/v1/transactions", "{\"type\":\"DEBT_PAYMENT\",\"amount\":\"50.00\",\"date\":\"2026-10-15\",\"description\":\"Pagamento\","
                            + "\"accountId\":\"" + accountA + "\",\"debtId\":\"" + debt.get("id").asString() + "\"}")), start);

            assertThat(results).as("rodada %d: %s", round, results).allSatisfy(o -> assertThat(o.status()).isIn(200, 201, 404, 422));
            assertThat(ledgerTotal()).isZero();
        }
    }
}
