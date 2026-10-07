package com.moneylog.infra.debt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.util.ContractValidation;

/**
 * T75: leitura de dívidas (status e saldo derivados das quitações, atraso pelo relógio), carregamento único dos registros
 * (o razão em memória fecha com o SQL dos livros) e o saldo por pessoa.
 */
@DisplayName("Dívidas: persistência, leitura e carregamento único (T75)")
class DebtReadsIT extends E4ItBase {

    @Test
    @DisplayName("CA1: dívida de 300,00 (origem LOAN_GIVEN) com quitação de 100,00: settled 100, outstanding 200, PARTIALLY_SETTLED, settlements[0], overdue pelo relógio")
    void deveDerivarStatusESaldo() throws Exception {
        JsonNode origin = loan("300.00", "2026-10-10", joao, "2026-11-05");
        String debtId = origin.get("debt").get("id").asString();
        created(received("100.00", "2026-10-10", debtId));

        JsonNode debt = debt(debtId);
        assertThat(money(debt, "settled")).isEqualTo("100.00");
        assertThat(money(debt, "outstanding")).isEqualTo("200.00");
        assertThat(debt.get("status").asString()).isEqualTo("PARTIALLY_SETTLED");
        assertThat(debt.get("settlements")).hasSize(1);
        assertThat(money(debt.get("settlements").get(0), "amount")).isEqualTo("100.00");
        assertThat(debt.get("overdue").asBoolean()).isFalse();
        assertThat(debt.get("cancelable").asBoolean()).isTrue();

        // o relógio vira: depois da previsão e com saldo, a dívida está atrasada (derivado, sem job)
        advanceTo(NOV_05.plusSeconds(86_400L * 2));
        assertThat(debt(debtId).get("overdue").asBoolean()).isTrue();
        assertThat(upcoming("from=2026-11-07&to=2026-11-30").get("items").get(0).get("bucket").asString()).isEqualTo("OVERDUE");
    }

    @Test
    @DisplayName("CA2 e CA6: o razão em memória (dashboard) e o SQL dos livros RECEIVABLE/PAYABLE/EQUITY coincidem e fecham em zero; saldos e ITs anteriores não mudam")
    void deveFecharORazaoComOSql() throws Exception {
        String receivable = loan("300.00", "2026-10-10", joao, "2026-11-05").get("debt").get("id").asString();
        created(received("100.00", "2026-10-10", receivable));
        String payable = createdBody(post("/v1/debts", "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"200.00\","
                + "\"date\":\"2026-10-10\",\"description\":\"Show\"}")).get("id").asString();
        created(payment("50.00", "2026-10-10", payable));

        JsonNode netWorth = summary().get("netWorth");
        assertThat(book("RECEIVABLE")).isEqualTo(20_000L);
        assertThat(money(netWorth, "receivables")).isEqualTo("200.00");
        assertThat(book("PAYABLE")).isEqualTo(-15_000L);
        assertThat(money(netWorth, "payables")).isEqualTo("-150.00");
        assertThat(book("ACCOUNT")).isEqualTo(2_000_000L - 30_000L + 10_000L - 5_000L);
        assertThat(money(netWorth, "accounts")).isEqualTo("19750.00");
        assertThat(ledgerTotal()).isZero();
        // Σ do patrimônio = contas + recebíveis + passivos = 19.750 + 200 − 150
        assertThat(money(netWorth, "total")).isEqualTo("19800.00");
        assertThat(accountBalance()).isEqualTo("19750.00");
    }

    @Test
    @DisplayName("CA3: estado gravado corrompido (quitações acima do principal) é DEBT_STATE_INCONSISTENT, sem retorno parcial: 500 sem vazar a mensagem")
    void deveRecusarEstadoCorrompido() throws Exception {
        String debtId = loan("300.00", "2026-10-10", joao, null).get("debt").get("id").asString();
        // duas quitações ativas gravadas por SQL, Σ = 500 > principal 300
        committed(() -> {
            for (int i = 0; i < 2; i++) {
                jdbc.update("""
                        INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, account_id, debt_id,
                            source, created_at, updated_at)
                        VALUES (?, ?, 'MONEY_RECEIVED', 'ACTIVE', 25000, 'BRL', ?, ?::uuid, ?::uuid, 'MANUAL', ?, ?)""", UUID.randomUUID(), userId,
                        Date.valueOf(LocalDate.of(2026, 10, 11)), nubank, debtId, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            }
        });
        mockMvc.perform(get("/v1/debts/" + debtId)).andExpect(status().isInternalServerError()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Σ"))));
    }

    @Test
    @DisplayName("CA4: GET /v1/debts filtra por direção, status, pessoa e atraso; ordem date desc; dívida de outro usuário não aparece")
    void deveFiltrarAListaDeDividas() throws Exception {
        String loaned = loan("300.00", "2026-10-10", joao, "2026-11-05").get("debt").get("id").asString();
        created(received("100.00", "2026-10-10", loaned));
        String late = createdBody(post("/v1/debts", "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"80.00\","
                + "\"date\":\"2026-09-01\",\"expectedDate\":\"2026-10-01\",\"description\":\"Atrasada\"}")).get("id").asString();
        String done = createdBody(post("/v1/debts", "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"40.00\","
                + "\"date\":\"2026-10-02\",\"description\":\"Quitada\"}")).get("id").asString();
        created(payment("40.00", "2026-10-09", done));
        String canceled = createdBody(post("/v1/debts", "{\"personId\":\"" + joao + "\",\"direction\":\"RECEIVABLE\",\"principal\":\"25.00\","
                + "\"date\":\"2026-10-03\",\"description\":\"Perdoada\"}")).get("id").asString();
        mockMvc.perform(post("/v1/debts/" + canceled + "/cancel", voidBody("perdão"))).andExpect(status().isOk());

        assertThat(ids(okBody(get("/v1/debts")))).containsExactly(loaned, canceled, done, late);
        assertThat(ids(okBody(get("/v1/debts?direction=RECEIVABLE")))).containsExactly(loaned, canceled);
        assertThat(ids(okBody(get("/v1/debts?direction=PAYABLE&status=OPEN")))).containsExactly(late);
        assertThat(ids(okBody(get("/v1/debts?status=PARTIALLY_SETTLED")))).containsExactly(loaned);
        assertThat(ids(okBody(get("/v1/debts?status=SETTLED")))).containsExactly(done);
        assertThat(ids(okBody(get("/v1/debts?status=CANCELED")))).containsExactly(canceled);
        assertThat(ids(okBody(get("/v1/debts?direction=RECEIVABLE&status=OPEN&overdue=true")))).isEmpty();
        assertThat(ids(okBody(get("/v1/debts?overdue=true")))).containsExactly(late);
        assertThat(ids(okBody(get("/v1/debts?personId=" + joao)))).containsExactly(loaned, canceled);
        JsonNode firstPage = okBody(get("/v1/debts?pageSize=2"));
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("hasNext").asBoolean()).isTrue();
        mockMvc.perform(get("/v1/debts?status=INVENTADO")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("CA5: João com recebível aberto de 150,00 e passivo de 40,00: net 110,00 (positivo = me devem); dívida CANCELED não entra; o detalhe traz o histórico e nextDueDate")
    void deveCalcularOSaldoDaPessoa() throws Exception {
        loan("150.00", "2026-10-10", joao, "2026-11-05");
        createdBody(post("/v1/debts", "{\"personId\":\"" + joao + "\",\"direction\":\"PAYABLE\",\"principal\":\"40.00\",\"date\":\"2026-10-10\","
                + "\"expectedDate\":\"2026-10-25\",\"description\":\"Devo\"}"));
        String canceled = createdBody(post("/v1/debts", "{\"personId\":\"" + joao + "\",\"direction\":\"RECEIVABLE\",\"principal\":\"999.00\","
                + "\"date\":\"2026-10-10\",\"description\":\"Perdoada\"}")).get("id").asString();
        mockMvc.perform(post("/v1/debts/" + canceled + "/cancel", voidBody("perdão"))).andExpect(status().isOk());

        JsonNode person = okBody(get("/v1/people/" + joao));
        assertThat(money(person, "net")).isEqualTo("110.00");
        assertThat(person.get("direction").asString()).isEqualTo("RECEIVABLE");
        assertThat(person.get("openDebts").asInt()).isEqualTo(2);
        assertThat(person.get("nextDueDate").asString()).isEqualTo("2026-10-25");
        assertThat(person.get("debts")).hasSize(3);
        assertThat(person.get("debts").get(0).get("person").get("name").asString()).isEqualTo("João");

        JsonNode list = okBody(get("/v1/people?q=jo"));
        assertThat(money(list.get("items").get(0), "net")).isEqualTo("110.00");
        // Maria deve ao usuário nada; o usuário deve 80 à Maria: net negativo e direção PAYABLE
        createdBody(post("/v1/debts", "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"80.00\",\"date\":\"2026-10-10\","
                + "\"description\":\"Devo\"}"));
        JsonNode mariaView = okBody(get("/v1/people/" + maria));
        assertThat(money(mariaView, "net")).isEqualTo("-80.00");
        assertThat(mariaView.get("direction").asString()).isEqualTo("PAYABLE");
        assertThat(mariaView.get("nextDueDate").isNull()).isTrue();
    }

    private static java.util.List<String> ids(JsonNode slice) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        slice.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }
}
