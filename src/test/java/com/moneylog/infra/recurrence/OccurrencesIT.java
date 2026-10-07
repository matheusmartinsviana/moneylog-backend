package com.moneylog.infra.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

/**
 * T80: realizar e pular ocorrências (conta e cartão), com idempotência e a regra S-49 (estornar a movimentação reabre a ocorrência).
 * Cenário: Internet de 119,00 todo dia 10 (Nubank, PIX) com início 10/10; assinatura de 49,90 no Roxinho com início 15/09; relógio 10/10.
 */
@DisplayName("Realizar e pular ocorrências de recorrência (T80)")
class OccurrencesIT extends E4ItBase {

    private String recurrence(String type, String amount, String description, String category, String target, String frequency, String start)
            throws Exception {
        return createdBody(post("/v1/recurrences", "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"description\":\"" + description
                + "\",\"categoryId\":\"" + category + "\"," + target + ",\"frequency\":\"" + frequency + "\",\"startDate\":\"" + start + "\"}"))
                .get("id").asString();
    }

    private String internet() throws Exception {
        return recurrence("EXPENSE", "119.00", "Internet", bills, "\"accountId\":\"" + nubank + "\",\"method\":\"PIX\"", "MONTHLY", "2026-10-10");
    }

    private String streaming() throws Exception {
        return recurrence("CARD_PURCHASE", "49.90", "Streaming", subscriptions, "\"cardId\":\"" + card + "\"", "MONTHLY", "2026-09-15");
    }

    private org.springframework.test.web.servlet.ResultActions realize(String id, String date, String json) throws Exception {
        return mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/" + date + "/realize", json == null ? "" : json)
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    private JsonNode occurrencesOf(String query) throws Exception {
        return okBody(get("/v1/recurrences/occurrences?" + query));
    }

    @Test
    @DisplayName("CA1: realizar 10/10 da Internet sem amount: 201 com EXPENSE de 119,00 em 10/10, source RECURRENCE, recurrenceId e occurrenceDate; ocorrência REALIZED, sai dos compromissos e vira consumo uma vez; com amount 124,90 a movimentação tem 124,90")
    void deveRealizarAOcorrencia() throws Exception {
        String id = internet();
        assertThat(money(summary().get("committed"), "d30")).isEqualTo("119.00");

        MvcResult result = realize(id, "2026-10-10", null).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.type").value("EXPENSE")).andExpect(jsonPath("$.amount.amount").value("119.00"))
                .andExpect(jsonPath("$.date").value("2026-10-10")).andExpect(jsonPath("$.source").value("RECURRENCE"))
                .andExpect(jsonPath("$.recurrenceId").value(id)).andExpect(jsonPath("$.occurrenceDate").value("2026-10-10"))
                .andExpect(jsonPath("$.description").value("Internet")).andExpect(jsonPath("$.category.name").value("Contas"))
                .andExpect(jsonPath("$.account.name").value("Nubank")).andExpect(jsonPath("$.method").value("PIX"))
                .andExpect(jsonPath("$.impact.cash.amount").value("-119.00")).andExpect(jsonPath("$.impact.consumption[0].amount.amount").value("119.00"))
                .andReturn();
        JsonNode tx = body(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/transactions/" + tx.get("id").asString());

        JsonNode window = occurrencesOf("from=2026-10-01&to=2026-12-31");
        assertThat(window.get("items").get(0).get("status").asString()).isEqualTo("REALIZED");
        assertThat(window.get("items").get(0).get("transactionId").asString()).isEqualTo(tx.get("id").asString());
        assertThat(window.get("items").get(1).get("status").asString()).isEqualTo("PENDING");
        // a ocorrência saiu dos compromissos e virou consumo e caixa, uma vez só: o disponível não muda
        assertThat(money(summary().get("committed"), "d30")).isEqualTo("0.00");
        JsonNode summary = summary();
        assertThat(money(summary.get("month"), "consumption")).isEqualTo("119.00");
        assertThat(money(summary.get("balances"), "inAccounts")).isEqualTo("19881.00");
        assertThat(money(summary.get("balances"), "available")).isEqualTo("19881.00");
        assertThat(upcoming("from=2026-10-10&to=2026-10-31").get("items")).isEmpty();

        JsonNode actual = body(realize(id, "2026-11-10", "{\"amount\":\"124.90\"}").andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.amount.amount").value("124.90")).andReturn());
        assertThat(actual.get("date").asString()).isEqualTo("2026-11-10");
        assertThat(money(occurrencesOf("from=2026-11-01&to=2026-11-30&status=REALIZED").get("items").get(0), "amount")).isEqualTo("124.90");
        assertThat(count("transactions")).isEqualTo(2);
    }

    @Test
    @DisplayName("CA2: realizar de novo é 422 RECURRENCE_OCCURRENCE_ALREADY_REALIZED; a mesma Idempotency-Key devolve a mesma movimentação (outro corpo é 409); sem a chave é 400")
    void deveSerIdempotenteEBarrarADuplicata() throws Exception {
        String id = internet();
        String key = UUID.randomUUID().toString();
        String url = "/v1/recurrences/" + id + "/occurrences/2026-10-10/realize";
        JsonNode first = body(mockMvc.perform(post(url, "{}").header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        JsonNode replay = body(mockMvc.perform(post(url, "{}").header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(replay.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(count("transactions")).isEqualTo(1);
        mockMvc.perform(post(url, "{\"amount\":\"130.00\"}").header("Idempotency-Key", key)).andExpect(status().isConflict())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        realize(id, "2026-10-10", "{}").andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("RECURRENCE_OCCURRENCE_ALREADY_REALIZED"));
        mockMvc.perform(post(url, "{}")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("CA2: duas realizações simultâneas da mesma ocorrência: uma vence (201), a outra recebe o erro de regra, e existe uma única movimentação")
    void deveSerializarARealizacaoConcorrente() throws Exception {
        String id = internet();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                barrier.await();
                return mockMvc.perform(MockMvcRequestBuilders.post("/v1/recurrences/" + id + "/occurrences/2026-10-10/realize")
                        .header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().getResponse().getStatus();
            }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : results) {
            statuses.add(future.get());
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(ledgerTotal()).isZero();
        assertThat(accountBalance()).isEqualTo("19881.00");
    }

    @Test
    @DisplayName("CA3: assinatura de 49,90 no Roxinho, fatura OPEN: CARD_PURCHASE em 1 parcela na fatura que fecha 25/10; sai das previsões e passa a vir só da fatura (sem dupla contagem)")
    void deveRealizarNoCartao() throws Exception {
        String id = recurrence("CARD_PURCHASE", "49.90", "Streaming", subscriptions, "\"cardId\":\"" + card + "\"", "MONTHLY", "2026-10-15");
        JsonNode before = upcoming("from=2026-10-10&to=2026-10-31");
        assertThat(before.get("items")).hasSize(1);
        assertThat(before.get("items").get(0).get("kind").asString()).isEqualTo("RECURRENCE");
        assertThat(money(before.get("totals"), "out")).isEqualTo("49.90");

        MvcResult result = realize(id, "2026-10-15", null).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.type").value("CARD_PURCHASE")).andExpect(jsonPath("$.source").value("RECURRENCE"))
                .andExpect(jsonPath("$.recurrenceId").value(id)).andExpect(jsonPath("$.occurrenceDate").value("2026-10-15"))
                .andExpect(jsonPath("$.card.name").value("Roxinho")).andExpect(jsonPath("$.installments.length()").value(1))
                .andExpect(jsonPath("$.installments[0].amount.amount").value("49.90")).andExpect(jsonPath("$.installments[0].lateEntry").value(false))
                .andExpect(jsonPath("$.installments[0].invoiceReferenceMonth").value("2026-11"))
                .andExpect(jsonPath("$.installments[0].invoiceDueDate").value("2026-11-05")).andReturn();
        assertThat(body(result).get("impact").get("cash").get("amount").asString()).isEqualTo("0.00");

        // a ocorrência de 15/10 saiu das previsões; a fatura de 05/11 passou a carregar os 49,90 e a de 15/11 continua pendente
        assertThat(upcoming("from=2026-10-10&to=2026-10-31").get("items")).isEmpty();
        JsonNode window = upcoming("from=2026-10-10&to=2026-11-20");
        assertThat(window.get("items")).hasSize(2);
        assertThat(window.get("items").get(0).get("kind").asString()).isEqualTo("INVOICE_FORECAST");
        assertThat(money(window.get("items").get(0), "amount")).isEqualTo("49.90");
        assertThat(window.get("items").get(1).get("kind").asString()).isEqualTo("RECURRENCE");
        assertThat(window.get("items").get(1).get("dueDate").asString()).isEqualTo("2026-11-15");
        assertThat(money(window.get("totals"), "out")).isEqualTo("99.80");
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test
    @DisplayName("CA3: com a fatura do ciclo CLOSED e não paga, a realização entra como lançamento tardio nela; com a fatura PAID, vai para a corrente (nunca para a paga)")
    void deveRespeitarOLancamentoTardio() throws Exception {
        String id = streaming();
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"100.00\",\"date\":\"2026-10-10\",\"description\":\"Mercado\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + food + "\",\"installments\":1}");
        advanceTo(OCT_27);
        // fatura de 25/10 fechada e não paga: a ocorrência de 15/10 entra nela, como lançamento tardio
        realize(id, "2026-10-15", null).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.installments[0].lateEntry").value(true)).andExpect(jsonPath("$.installments[0].invoiceReferenceMonth").value("2026-11"));
        JsonNode closed = okBody(get("/v1/cards/" + card + "/invoices"));
        JsonNode invoice = null;
        for (JsonNode item : closed.get("items")) {
            if (item.get("closingDate").asString().equals("2026-10-25")) {
                invoice = item;
            }
        }
        assertThat(invoice).isNotNull();
        assertThat(invoice.get("status").asString()).isEqualTo("CLOSED");
        assertThat(money(invoice, "total")).isEqualTo("149.90");

        // paga a fatura (149,90): a ocorrência de 15/11 realizada depois vai para a corrente (fecha 25/11), nunca para a paga
        mockMvc.perform(post("/v1/invoices/" + invoice.get("id").asString() + "/payments", "{\"accountId\":\"" + nubank + "\",\"amount\":\"149.90\","
                        + "\"date\":\"2026-10-27\"}").header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("PAID"));
        advanceTo(NOV_05);
        realize(id, "2026-10-15", "{}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_OCCURRENCE_ALREADY_REALIZED"));
        realize(id, "2026-11-15", null).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.installments[0].invoiceReferenceMonth").value("2026-12"))
                .andExpect(jsonPath("$.installments[0].lateEntry").value(false));
    }

    @Test
    @DisplayName("CA4: pular 10/11: a ocorrência some dos compromissos, aparece SKIPPED e não vira movimentação; realizar depois é 422 RECURRENCE_OCCURRENCE_SKIPPED; pular de novo também")
    void devePularAOcorrencia() throws Exception {
        String id = internet();
        mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/2026-11-10/skip", "")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("SKIPPED")).andExpect(jsonPath("$.date").value("2026-11-10"))
                .andExpect(jsonPath("$.recurrenceId").value(id)).andExpect(jsonPath("$.transactionId").doesNotExist());
        assertThat(count("transactions")).isZero();
        assertThat(count("recurrence_skips")).isEqualTo(1);
        JsonNode upcoming = upcoming("from=2026-10-10&to=2026-12-31");
        assertThat(upcoming.get("items")).hasSize(2);
        assertThat(upcoming.get("items")).extracting(i -> i.get("dueDate").asString()).containsExactly("2026-10-10", "2026-12-10");
        JsonNode skipped = occurrencesOf("from=2026-10-01&to=2026-12-31&status=SKIPPED");
        assertThat(skipped.get("items")).hasSize(1);
        realize(id, "2026-11-10", null).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("RECURRENCE_OCCURRENCE_SKIPPED"));
        mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/2026-11-10/skip", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECURRENCE_OCCURRENCE_SKIPPED"));
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("CA5: estornar a movimentação realizada reabre a ocorrência (S-49): volta a PENDING, aos compromissos, e pode ser realizada de novo")
    void deveReabrirDepoisDoEstorno() throws Exception {
        String id = internet();
        String txId = body(realize(id, "2026-10-10", null).andExpect(status().isCreated()).andReturn()).get("id").asString();
        assertThat(upcoming("from=2026-10-10&to=2026-10-31").get("items")).isEmpty();

        mockMvc.perform(post("/v1/transactions/" + txId + "/void", voidBody("lancei errado"))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms());
        JsonNode reopened = occurrencesOf("from=2026-10-01&to=2026-10-31");
        assertThat(reopened.get("items")).hasSize(1);
        assertThat(reopened.get("items").get(0).get("status").asString()).isEqualTo("PENDING");
        assertThat(upcoming("from=2026-10-10&to=2026-10-31").get("items")).hasSize(1);
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("0.00");

        String again = body(realize(id, "2026-10-10", "{\"amount\":\"120.00\"}").andExpect(status().isCreated()).andReturn()).get("id").asString();
        assertThat(again).isNotEqualTo(txId);
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("120.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE recurrence_id = ?::uuid AND status = 'ACTIVE'", Long.class, id)).isEqualTo(1L);
    }

    @Test
    @DisplayName("CA6: data que não é ocorrência é 422 RECURRENCE_NOT_AN_OCCURRENCE; conta e cartão arquivados são 422 ACCOUNT_ARCHIVED/CARD_ARCHIVED; receita recorrente realiza como INCOME")
    void deveRecusarARealizacaoInvalida() throws Exception {
        String id = internet();
        realize(id, "2026-10-11", null).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("RECURRENCE_NOT_AN_OCCURRENCE"));
        mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/2026-10-11/skip", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECURRENCE_NOT_AN_OCCURRENCE"));
        realize(id, "2026-09-10", null).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_NOT_AN_OCCURRENCE"));
        realize(UUID.randomUUID().toString(), "2026-10-10", null).andExpect(status().isNotFound()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("RECURRENCE_NOT_FOUND"));
        mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/ontem/realize", "{}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());

        String xp = createdBody(post("/v1/accounts", "{\"name\":\"XP\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},"
                + "\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        String onXp = recurrence("EXPENSE", "10.00", "No XP", bills, "\"accountId\":\"" + xp + "\",\"method\":\"PIX\"", "MONTHLY", "2026-10-10");
        String spare = createdBody(post("/v1/cards", "{\"name\":\"Extra\",\"institution\":\"X\",\"limit\":{\"amount\":\"1000.00\",\"currency\":\"BRL\"},"
                + "\"closingDay\":25,\"dueDay\":5}")).get("id").asString();
        String onSpare = recurrence("CARD_PURCHASE", "20.00", "No extra", subscriptions, "\"cardId\":\"" + spare + "\"", "MONTHLY", "2026-10-10");
        mockMvc.perform(post("/v1/accounts/" + xp + "/archive", "")).andExpect(status().isOk());
        mockMvc.perform(post("/v1/cards/" + spare + "/archive", "")).andExpect(status().isOk());
        realize(onXp, "2026-10-10", null).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED"));
        realize(onSpare, "2026-10-10", null).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("CARD_ARCHIVED"));
        assertThat(count("transactions")).isZero();

        String salaryId = recurrence("INCOME", "5000.00", "Salário", salary, "\"accountId\":\"" + nubank + "\",\"method\":\"TRANSFER\"", "MONTHLY", "2026-10-05");
        JsonNode income = body(realize(salaryId, "2026-10-05", null).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.type").value("INCOME")).andReturn());
        assertThat(money(income.get("impact"), "cash")).isEqualTo("5000.00");
        assertThat(money(summary().get("month"), "income")).isEqualTo("5000.00");
    }

    @Test
    @DisplayName("recorrência de outro usuário não pode ser realizada nem pulada por quem não é o dono (404) e nada é gravado")
    void deveIsolarARealizacao() throws Exception {
        String id = internet();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        mockMvc.perform(postAs(auth.bearer(other), "/v1/recurrences/" + id + "/occurrences/2026-10-10/realize", "{}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        assertThat(count("transactions")).isZero();
        assertThat(LocalDate.of(2026, 10, 10)).isEqualTo(LocalDate.parse("2026-10-10"));
    }
}
