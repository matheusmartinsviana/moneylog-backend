package com.moneylog.infra.debt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

/**
 * T76: emprestar, receber e pagar por {@code POST /v1/transactions}, com a dívida criada/alterada na mesma transação, e o estorno
 * com as guardas (S-141). Casos 6 e 7 do PR 01 (emprestar 300, receber 300). Cenário padrão: relógio 10/10/2026, Nubank 20.000,00.
 */
@DisplayName("Movimentações de dívida: emprestar, receber, pagar (T76)")
class DebtTransactionsIT extends E4ItBase {

    @Test
    @DisplayName("CA1: empréstimo de 300,00 ao João (previsão 05/11): dívida RECEIVABLE criada, caixa −300, consumo vazio, patrimônio 0 e compromisso DEBT_RECEIVABLE em 05/11 (PR 01 caso 6)")
    void deveEmprestar() throws Exception {
        MvcResult result = createTx("{\"type\":\"LOAN_GIVEN\",\"amount\":\"300.00\",\"date\":\"2026-10-10\",\"description\":\"Empréstimo\","
                + "\"accountId\":\"" + nubank + "\",\"newDebt\":{\"personId\":\"" + joao + "\",\"expectedDate\":\"2026-11-05\"}}")
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.type").value("LOAN_GIVEN")).andExpect(jsonPath("$.debt.direction").value("RECEIVABLE"))
                .andExpect(jsonPath("$.debt.person.id").value(joao)).andExpect(jsonPath("$.debt.person.name").value("João"))
                .andExpect(jsonPath("$.debt.principal.amount").value("300.00")).andExpect(jsonPath("$.debt.outstanding.amount").value("300.00"))
                .andExpect(jsonPath("$.debt.settled.amount").value("0.00")).andExpect(jsonPath("$.debt.status").value("OPEN"))
                .andExpect(jsonPath("$.debt.expectedDate").value("2026-11-05")).andExpect(jsonPath("$.debt.overdue").value(false))
                .andExpect(jsonPath("$.impact.cash.amount").value("-300.00")).andExpect(jsonPath("$.impact.consumption.length()").value(0))
                .andExpect(jsonPath("$.impact.netWorth.amount").value("0.00")).andExpect(jsonPath("$.impact.commitment.amount").value("0.00"))
                .andExpect(jsonPath("$.account.id").value(nubank)).andExpect(jsonPath("$.shares.length()").value(0))
                .andExpect(jsonPath("$.editable").value(false)).andExpect(jsonPath("$.voidable").value(true)).andReturn();
        JsonNode tx = body(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/transactions/" + tx.get("id").asString());
        String debtId = tx.get("debt").get("id").asString();

        // a dívida lida pela rota própria: origem = a movimentação, status derivado, sem quitação
        JsonNode debt = debt(debtId);
        assertThat(debt.get("origin").asString()).isEqualTo("WITH_MOVEMENT");
        assertThat(debt.get("originTransactionId").asString()).isEqualTo(tx.get("id").asString());
        assertThat(debt.get("settlements")).isEmpty();
        assertThat(debt.get("voidable").asBoolean()).isFalse();
        assertThat(debt.get("voidBlockedBy").asString()).isEqualTo("HAS_ORIGIN_TRANSACTION");

        // PR 01 caso 6: caixa −300, nenhuma despesa, patrimônio igual (recebível +300 compensa o caixa) e compromisso a receber
        assertThat(accountBalance()).isEqualTo("19700.00");
        JsonNode summary = summary();
        assertThat(money(summary.get("month"), "consumption")).isEqualTo("0.00");
        assertThat(money(summary.get("month"), "lent")).isEqualTo("300.00");
        assertThat(money(summary.get("netWorth"), "total")).isEqualTo("20000.00");
        JsonNode upcoming = upcoming("from=2026-10-10&to=2026-11-10");
        assertThat(upcoming.get("items")).hasSize(1);
        assertThat(upcoming.get("items").get(0).get("kind").asString()).isEqualTo("DEBT_RECEIVABLE");
        assertThat(upcoming.get("items").get(0).get("dueDate").asString()).isEqualTo("2026-11-05");
        assertThat(upcoming.get("items").get(0).get("direction").asString()).isEqualTo("IN");
        assertThat(money(upcoming.get("items").get(0), "amount")).isEqualTo("300.00");
        assertThat(book("RECEIVABLE")).isEqualTo(30_000L);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("CA2: devolução de 300,00 quita a dívida sem virar receita (PR 01 caso 7); passar do saldo é 422 DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING; sem debtId e sem newDebt é 400")
    void deveReceberADevolucao() throws Exception {
        String debtId = loan("300.00", "2026-10-10", joao, "2026-11-05").get("debt").get("id").asString();

        mockMvc.perform(post("/v1/transactions", "{\"type\":\"MONEY_RECEIVED\",\"amount\":\"300.00\",\"date\":\"2026-10-10\","
                        + "\"description\":\"Sem dívida\",\"accountId\":\"" + nubank + "\"}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        JsonNode first = created(received("200.00", "2026-10-10", debtId));
        assertThat(money(first.get("impact"), "cash")).isEqualTo("200.00");
        assertThat(first.get("impact").get("consumption")).isEmpty();
        assertThat(first.get("debt").get("status").asString()).isEqualTo("PARTIALLY_SETTLED");
        assertThat(money(first.get("debt"), "outstanding")).isEqualTo("100.00");
        assertThat(money(first.get("debt"), "settled")).isEqualTo("200.00");

        createTx(received("300.00", "2026-10-10", debtId)).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING"));
        // a recusa não gravou nada: o saldo devedor continua 100,00
        assertThat(money(debt(debtId), "outstanding")).isEqualTo("100.00");

        JsonNode last = created(received("100.00", "2026-10-10", debtId));
        assertThat(last.get("debt").get("status").asString()).isEqualTo("SETTLED");
        assertThat(money(last.get("debt"), "outstanding")).isEqualTo("0.00");

        // nada disso é renda: caixa de volta, receita 0, "recebido" só como informação, e o compromisso saiu do /upcoming
        assertThat(accountBalance()).isEqualTo("20000.00");
        JsonNode month = summary().get("month");
        assertThat(money(month, "income")).isEqualTo("0.00");
        assertThat(money(month, "received")).isEqualTo("300.00");
        assertThat(money(month, "consumption")).isEqualTo("0.00");
        assertThat(upcoming("from=2026-10-10&to=2026-11-10").get("items")).isEmpty();
        assertThat(book("INCOME")).isZero();
        assertThat(book("RECEIVABLE")).isZero();
        assertThat(ledgerTotal()).isZero();
        JsonNode full = debt(debtId);
        assertThat(full.get("settlements")).hasSize(2);
        assertThat(full.get("settlements").get(0).get("date").asString()).isEqualTo("2026-10-10");
        assertThat(full.get("settlements").get(0).get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("CA3: MONEY_RECEIVED com newDebt é captação: nasce uma PAYABLE de 500,00 da Maria e o caixa sobe 500,00 sem receita (S-6)")
    void deveCaptar() throws Exception {
        JsonNode tx = created("{\"type\":\"MONEY_RECEIVED\",\"amount\":\"500.00\",\"date\":\"2026-10-10\",\"description\":\"Empréstimo da Maria\","
                + "\"accountId\":\"" + nubank + "\",\"newDebt\":{\"personId\":\"" + maria + "\",\"expectedDate\":\"2026-12-10\"}}");
        assertThat(tx.get("debt").get("direction").asString()).isEqualTo("PAYABLE");
        assertThat(money(tx.get("debt"), "principal")).isEqualTo("500.00");
        assertThat(money(tx.get("impact"), "cash")).isEqualTo("500.00");
        assertThat(tx.get("impact").get("consumption")).isEmpty();
        assertThat(money(tx.get("impact"), "netWorth")).isEqualTo("0.00");
        assertThat(accountBalance()).isEqualTo("20500.00");
        assertThat(money(summary().get("month"), "income")).isEqualTo("0.00");
        assertThat(book("PAYABLE")).isEqualTo(-50_000L);
        // a captação é um compromisso de saída na previsão (DEBT_PAYABLE)
        JsonNode upcoming = upcoming("from=2026-10-10&to=2026-12-31");
        assertThat(upcoming.get("items").get(0).get("kind").asString()).isEqualTo("DEBT_PAYABLE");
        assertThat(upcoming.get("items").get(0).get("direction").asString()).isEqualTo("OUT");
    }

    @Test
    @DisplayName("CA4: pagar 200,00 de uma PAYABLE de 500,00: caixa −200, consumo 0, saldo 300,00 PARTIALLY_SETTLED; pagar RECEIVABLE é 422 DEBT_DIRECTION_MISMATCH")
    void devePagarUmaDivida() throws Exception {
        String payable = created("{\"type\":\"MONEY_RECEIVED\",\"amount\":\"500.00\",\"date\":\"2026-10-10\",\"description\":\"Captação\","
                + "\"accountId\":\"" + nubank + "\",\"newDebt\":{\"personId\":\"" + maria + "\"}}").get("debt").get("id").asString();
        JsonNode paid = created(payment("200.00", "2026-10-10", payable));
        assertThat(paid.get("type").asString()).isEqualTo("DEBT_PAYMENT");
        assertThat(money(paid.get("impact"), "cash")).isEqualTo("-200.00");
        assertThat(paid.get("impact").get("consumption")).isEmpty();
        assertThat(paid.get("debt").get("status").asString()).isEqualTo("PARTIALLY_SETTLED");
        assertThat(money(paid.get("debt"), "outstanding")).isEqualTo("300.00");
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("0.00");

        String receivable = loan("100.00", "2026-10-10", joao, null).get("debt").get("id").asString();
        createTx(payment("10.00", "2026-10-10", receivable)).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_DIRECTION_MISMATCH"));
        // receber de uma PAYABLE também é direção errada
        createTx(received("10.00", "2026-10-10", payable)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBT_DIRECTION_MISMATCH"));
        // pagamento sem debtId não passa do contrato
        createTx("{\"type\":\"DEBT_PAYMENT\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"x\",\"accountId\":\"" + nubank + "\"}")
                .andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("CA5: origem repetida (DEBT_ALREADY_HAS_ORIGIN), pessoa arquivada (PERSON_ARCHIVED), conta que não é CASH e pessoa de outro usuário")
    void deveRecusarAsRegrasDeOrigem() throws Exception {
        JsonNode tx = loan("300.00", "2026-10-10", joao, null);
        String debtId = tx.get("debt").get("id").asString();
        createTx("{\"type\":\"LOAN_GIVEN\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"description\":\"De novo\",\"accountId\":\"" + nubank
                + "\",\"debtId\":\"" + debtId + "\"}").andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("DEBT_ALREADY_HAS_ORIGIN"));
        // newDebt e debtId juntos não passam do contrato
        createTx("{\"type\":\"LOAN_GIVEN\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"description\":\"Os dois\",\"accountId\":\"" + nubank
                + "\",\"debtId\":\"" + debtId + "\",\"newDebt\":{\"personId\":\"" + joao + "\"}}").andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());

        mockMvc.perform(post("/v1/people/" + maria + "/archive", "")).andExpect(status().isOk());
        createTx("{\"type\":\"LOAN_GIVEN\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"description\":\"Maria\",\"accountId\":\"" + nubank
                + "\",\"newDebt\":{\"personId\":\"" + maria + "\"}}").andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("PERSON_ARCHIVED"));

        String invest = createdBody(post("/v1/accounts", "{\"name\":\"Renda fixa\",\"type\":\"INVESTMENT\","
                + "\"openingBalance\":{\"amount\":\"1000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        createTx("{\"type\":\"LOAN_GIVEN\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"description\":\"Invest\",\"accountId\":\"" + invest
                + "\",\"newDebt\":{\"personId\":\"" + joao + "\"}}").andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("TRANSACTION_ACCOUNT_NOT_CASH"));

        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        String otherPerson = body(mockMvc.perform(postAs(auth.bearer(other), "/v1/people", "{\"name\":\"Alheio\"}")).andExpect(status().isCreated())
                .andReturn()).get("id").asString();
        createTx("{\"type\":\"LOAN_GIVEN\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"description\":\"Alheio\",\"accountId\":\"" + nubank
                + "\",\"newDebt\":{\"personId\":\"" + otherPerson + "\"}}").andExpect(status().isNotFound())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("PERSON_NOT_FOUND"));
        createTx(received("1.00", "2026-10-10", UUID.randomUUID().toString())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEBT_NOT_FOUND"));
        // nada foi gravado pelas recusas: continua uma dívida só
        assertThat(count("debts")).isEqualTo(1);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("CA6: estornar a quitação reabre a dívida e devolve o caixa; estornar a origem com quitação ativa é 422 DEBT_HAS_SETTLEMENTS (voidable false); sem quitações, a origem estornada deixa a dívida VOIDED")
    void deveEstornarComGuardas() throws Exception {
        JsonNode origin = loan("300.00", "2026-10-10", joao, "2026-11-05");
        String debtId = origin.get("debt").get("id").asString();
        String originId = origin.get("id").asString();
        JsonNode settlement = created(received("100.00", "2026-10-10", debtId));
        String settlementId = settlement.get("id").asString();

        // a origem tem quitação ativa: a guarda recusa, e a flag antecipa a recusa
        mockMvc.perform(post("/v1/transactions/" + originId + "/void", voidBody("engano"))).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_HAS_SETTLEMENTS"));
        mockMvc.perform(get("/v1/transactions/" + originId)).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.voidable").value(false)).andExpect(jsonPath("$.voidBlockedBy").value("DEBT_HAS_SETTLEMENTS"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(accountBalance()).isEqualTo("19800.00");

        // estornar a quitação: o status volta sozinho (derivado) e o caixa também
        mockMvc.perform(post("/v1/transactions/" + settlementId + "/void", voidBody("duplicado"))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.debt.status").value("OPEN")).andExpect(jsonPath("$.debt.outstanding.amount").value("300.00"));
        assertThat(accountBalance()).isEqualTo("19700.00");
        JsonNode reopened = debt(debtId);
        assertThat(reopened.get("status").asString()).isEqualTo("OPEN");
        assertThat(reopened.get("settlements").get(0).get("active").asBoolean()).isFalse();

        // sem quitações ativas, a origem se estorna: despesa/dívida somem das lentes e a dívida vira VOIDED
        mockMvc.perform(post("/v1/transactions/" + originId + "/void", voidBody("não emprestei"))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.voidable").value(false)).andExpect(jsonPath("$.debt.status").value("VOIDED"));
        assertThat(debt(debtId).get("status").asString()).isEqualTo("VOIDED");
        assertThat(accountBalance()).isEqualTo("20000.00");
        assertThat(book("RECEIVABLE")).isZero();
        assertThat(upcoming("from=2026-10-10&to=2026-11-10").get("items")).isEmpty();
        assertThat(ledgerTotal()).isZero();
        // a quitação não pode mais ser recriada nem a dívida estornada de novo
        createTx(received("10.00", "2026-10-10", debtId)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBT_CLOSED"));
        mockMvc.perform(post("/v1/transactions/" + originId + "/void", voidBody("de novo"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_VOIDED"));
    }

    @Test
    @DisplayName("CA6: depois do perdão a dívida está encerrada: estornar a origem ou uma quitação é 422 DEBT_CLOSED")
    void deveRecusarEstornoDepoisDoPerdao() throws Exception {
        JsonNode origin = loan("300.00", "2026-10-10", joao, "2026-11-05");
        String debtId = origin.get("debt").get("id").asString();
        String settlementId = created(received("100.00", "2026-10-10", debtId)).get("id").asString();
        mockMvc.perform(post("/v1/debts/" + debtId + "/cancel", voidBody("perdoei"))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("CANCELED"));

        mockMvc.perform(post("/v1/transactions/" + settlementId + "/void", voidBody("x"))).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_CLOSED"));
        mockMvc.perform(post("/v1/transactions/" + origin.get("id").asString() + "/void", voidBody("x"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBT_CLOSED"));
        mockMvc.perform(get("/v1/transactions/" + settlementId)).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.voidable").value(false)).andExpect(jsonPath("$.voidBlockedBy").value("DEBT_CLOSED"));
        createTx(received("10.00", "2026-10-10", debtId)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBT_CLOSED"));
    }

    @Test
    @DisplayName("CA7: a mesma Idempotency-Key devolve a mesma movimentação e cria uma única dívida; outro corpo com a chave é 409")
    void deveSerIdempotente() throws Exception {
        String key = UUID.randomUUID().toString();
        String json = "{\"type\":\"LOAN_GIVEN\",\"amount\":\"300.00\",\"date\":\"2026-10-10\",\"description\":\"Empréstimo\",\"accountId\":\""
                + nubank + "\",\"newDebt\":{\"personId\":\"" + joao + "\"}}";
        JsonNode first = body(mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        JsonNode second = body(mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(second.get("debt").get("id").asString()).isEqualTo(first.get("debt").get("id").asString());
        assertThat(count("debts")).isEqualTo(1);
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(accountBalance()).isEqualTo("19700.00");
        mockMvc.perform(post("/v1/transactions", json.replace("300.00", "301.00")).header("Idempotency-Key", key))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("as movimentações de dívida não são editáveis (PUT é 422 TRANSACTION_NOT_EDITABLE) e a lista as devolve com a dívida")
    void naoDeveEditarMovimentacaoDeDivida() throws Exception {
        JsonNode origin = loan("300.00", "2026-10-10", joao, null);
        mockMvc.perform(put("/v1/transactions/" + origin.get("id").asString(), "{\"type\":\"LOAN_GIVEN\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE"));
        mockMvc.perform(put("/v1/transactions/" + origin.get("id").asString(), "{\"type\":\"LOAN_GIVEN\",\"amount\":\"1.00\",\"date\":\"2026-10-10\","
                        + "\"description\":\"x\",\"accountId\":\"" + nubank + "\",\"newDebt\":{\"personId\":\"" + joao + "\"},\"version\":0}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE"));
        JsonNode list = okBody(get("/v1/transactions?type=LOAN_GIVEN"));
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("debt").get("person").get("name").asString()).isEqualTo("João");
        assertThat(money(list.get("totals").get("cashFlow"), "out")).isEqualTo("300.00");
    }

    private int postStatus(String url, String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
                .content(json).header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().getResponse().getStatus();
    }

    private List<Integer> inParallel(List<java.util.concurrent.Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        List<Future<Integer>> futures = new ArrayList<>();
        for (java.util.concurrent.Callable<Integer> task : tasks) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return task.call();
            }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        pool.shutdown();
        return statuses;
    }

    @Test
    @DisplayName("concorrência: duas quitações simultâneas de 200,00 numa dívida de 300,00: uma vence (201), a outra recebe DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING (422); nunca 500 nem quitação acima do principal")
    void deveSerializarQuitacoesSimultaneas() throws Exception {
        String debtId = loan("300.00", "2026-10-10", joao, null).get("debt").get("id").asString();
        List<Integer> statuses = inParallel(List.of(() -> postStatus("/v1/transactions", received("200.00", "2026-10-10", debtId)),
                () -> postStatus("/v1/transactions", received("200.00", "2026-10-10", debtId))));
        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        JsonNode debt = debt(debtId);
        assertThat(money(debt, "settled")).isEqualTo("200.00");
        assertThat(money(debt, "outstanding")).isEqualTo("100.00");
        assertThat(debt.get("settlements")).hasSize(1);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("concorrência: estornar a origem × quitar a mesma dívida: ou a quitação vence e o estorno é DEBT_HAS_SETTLEMENTS, ou o estorno vence e a quitação é DEBT_CLOSED; o estado final é coerente e nunca 500")
    void deveSerializarEstornoEQuitacao() throws Exception {
        JsonNode origin = loan("300.00", "2026-10-10", joao, null);
        String debtId = origin.get("debt").get("id").asString();
        String originId = origin.get("id").asString();
        List<Integer> statuses = inParallel(List.of(() -> postStatus("/v1/transactions/" + originId + "/void", voidBody("engano")),
                () -> postStatus("/v1/transactions", received("100.00", "2026-10-10", debtId))));
        assertThat(statuses).doesNotContain(500);
        boolean voided = statuses.get(0) == 200;
        boolean settled = statuses.get(1) == 201;
        assertThat(voided ^ settled).as("exatamente um dos dois vence: %s", statuses).isTrue();
        JsonNode debt = debt(debtId);
        assertThat(debt.get("status").asString()).isEqualTo(voided ? "VOIDED" : "PARTIALLY_SETTLED");
        assertThat(ledgerTotal()).isZero();
        assertThat(accountBalance()).isEqualTo(voided ? "20000.00" : "19800.00");
    }
}
