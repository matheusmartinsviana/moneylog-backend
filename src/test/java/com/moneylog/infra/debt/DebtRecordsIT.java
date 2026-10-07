package com.moneylog.infra.debt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

/**
 * T77: dívida sem movimentação, edição, perdão e estorno do registro. Casos 8 do PR 01 ("eu devo 200", sem caixa nem consumo).
 */
@DisplayName("Dívida sem movimentação, edição, perdão e estorno do registro (T77)")
class DebtRecordsIT extends E4ItBase {

    private String debtJson(String personId, String direction, String principal, String date, String expected, String description) {
        return "{\"personId\":\"" + personId + "\",\"direction\":\"" + direction + "\",\"principal\":\"" + principal + "\",\"date\":\"" + date + "\","
                + (expected == null ? "" : "\"expectedDate\":\"" + expected + "\",") + "\"description\":\"" + description + "\"}";
    }

    private JsonNode payable(String principal, String expected) throws Exception {
        return createdBody(post("/v1/debts", debtJson(maria, "PAYABLE", principal, "2026-10-10", expected, "Almoço pago por Maria")));
    }

    @Test
    @DisplayName("CA1: eu devo 200,00 à Maria sem movimentação: caixa 0, consumo 0, patrimônio −200 (PAYABLE −200 / EQUITY +200); sem previsão fica em undated e fora dos horizontes")
    void deveRegistrarDividaSemMovimentacao() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/debts", debtJson(maria, "PAYABLE", "200.00", "2026-10-10", null, "Almoço pago por Maria")))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.origin").value("WITHOUT_MOVEMENT"))
                .andExpect(jsonPath("$.originTransactionId").doesNotExist()).andExpect(jsonPath("$.direction").value("PAYABLE"))
                .andExpect(jsonPath("$.principal.amount").value("200.00")).andExpect(jsonPath("$.outstanding.amount").value("200.00"))
                .andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.person.name").value("Maria"))
                .andExpect(jsonPath("$.voidable").value(true)).andExpect(jsonPath("$.cancelable").value(true))
                .andExpect(jsonPath("$.category").doesNotExist()).andExpect(jsonPath("$.version").value(0)).andReturn();
        JsonNode debt = body(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/debts/" + debt.get("id").asString());

        assertThat(accountBalance()).isEqualTo("20000.00");
        JsonNode summary = summary();
        assertThat(money(summary.get("month"), "consumption")).isEqualTo("0.00");
        assertThat(money(summary.get("netWorth"), "total")).isEqualTo("19800.00");
        assertThat(money(summary.get("netWorth"), "payables")).isEqualTo("-200.00");
        assertThat(book("PAYABLE")).isEqualTo(-20_000L);
        assertThat(book("EQUITY")).isEqualTo(-2_000_000L + 20_000L);
        assertThat(book("EXPENSE")).isZero();
        assertThat(ledgerTotal()).isZero();

        // sem previsão: lista "sem data" e nada nos horizontes (S-145)
        JsonNode upcoming = upcoming(null);
        assertThat(upcoming.get("items")).isEmpty();
        assertThat(upcoming.get("undated")).hasSize(1);
        assertThat(upcoming.get("undated").get(0).get("kind").asString()).isEqualTo("DEBT_PAYABLE");
        assertThat(money(summary.get("committed"), "d30")).isEqualTo("0.00");

        // com previsão, vira compromisso datado de saída
        JsonNode dated = payable("80.00", "2026-11-05");
        JsonNode next = upcoming("from=2026-10-10&to=2026-11-10");
        assertThat(next.get("items")).hasSize(1);
        assertThat(next.get("items").get(0).get("dueDate").asString()).isEqualTo("2026-11-05");
        assertThat(money(next.get("items").get(0), "amount")).isEqualTo("80.00");
        assertThat(dated.get("expectedDate").asString()).isEqualTo("2026-11-05");
    }

    @Test
    @DisplayName("S-237: Idempotency-Key opcional em POST /v1/debts: o replay devolve a mesma dívida (201) sem novo lançamento; outro corpo com a mesma chave é 409 IDEMPOTENCY_KEY_REUSED; chave não UUID é 400; sem chave cada chamada cria uma")
    void deveSerIdempotenteComAChave() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"80.00\",\"date\":\"2026-10-10\",\"description\":\"Jantar\",\"categoryId\":\"" + food + "\"}";
        JsonNode first = body(mockMvc.perform(post("/v1/debts", body).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        JsonNode replay = body(mockMvc.perform(post("/v1/debts", body).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(replay.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(count("debts")).isEqualTo(1L);
        assertThat(book("EXPENSE")).as("o consumo de 80,00 entra uma vez só").isEqualTo(8_000L);
        assertThat(book("PAYABLE")).isEqualTo(-8_000L);
        assertThat(ledgerTotal()).isZero();

        mockMvc.perform(post("/v1/debts", body.replace("80.00", "81.00")).header("Idempotency-Key", key)).andExpect(status().isConflict())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("debts")).isEqualTo(1L);
        mockMvc.perform(post("/v1/debts", body).header("Idempotency-Key", "nao-e-uuid")).andExpect(status().isBadRequest());
        // sem chave: comportamento anterior (cada chamada cria uma dívida)
        mockMvc.perform(post("/v1/debts", body)).andExpect(status().isCreated());
        mockMvc.perform(post("/v1/debts", body)).andExpect(status().isCreated());
        assertThat(count("debts")).isEqualTo(3L);
        // outra chave com o mesmo corpo é outra dívida; a falha de validação não memoriza a chave
        String other = UUID.randomUUID().toString();
        mockMvc.perform(post("/v1/debts", body.replace("80.00", "0.00")).header("Idempotency-Key", other)).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/v1/debts", body).header("Idempotency-Key", other)).andExpect(status().isCreated());
        assertThat(count("debts")).isEqualTo(4L);
    }

    @Test
    @DisplayName("S-244: POST /v1/people e POST /v1/recurrences honram Idempotency-Key opcional: replay devolve o mesmo recurso (201) sem duplicar; outro corpo é 409; sem chave cria outro")
    void deveSerIdempotenteNasCriacoesDePessoaERecorrencia() throws Exception {
        String key = UUID.randomUUID().toString();
        String person = "{\"name\":\"Carlos\",\"notes\":\"x\"}";
        long before = count("persons");
        JsonNode p1 = body(mockMvc.perform(post("/v1/people", person).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        JsonNode p2 = body(mockMvc.perform(post("/v1/people", person).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(p2.get("id").asString()).isEqualTo(p1.get("id").asString());
        assertThat(count("persons")).isEqualTo(before + 1);
        mockMvc.perform(post("/v1/people", person.replace("Carlos", "Carla")).header("Idempotency-Key", key)).andExpect(status().isConflict())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mockMvc.perform(post("/v1/people", person)).andExpect(status().isCreated());
        mockMvc.perform(post("/v1/people", person)).andExpect(status().isCreated());
        assertThat(count("persons")).isEqualTo(before + 3);

        String rkey = UUID.randomUUID().toString();
        String rec = "{\"type\":\"EXPENSE\",\"amount\":\"119.00\",\"description\":\"Internet\",\"categoryId\":\"" + food + "\",\"accountId\":\"" + nubank
                + "\",\"method\":\"PIX\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-10-10\"}";
        JsonNode r1 = body(mockMvc.perform(post("/v1/recurrences", rec).header("Idempotency-Key", rkey)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        JsonNode r2 = body(mockMvc.perform(post("/v1/recurrences", rec).header("Idempotency-Key", rkey)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(r2.get("id").asString()).isEqualTo(r1.get("id").asString());
        assertThat(count("recurring_transactions")).isEqualTo(1L);
        mockMvc.perform(post("/v1/recurrences", rec.replace("119.00", "120.00")).header("Idempotency-Key", rkey)).andExpect(status().isConflict())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("recurring_transactions")).isEqualTo(1L);
        // escopos independentes: a mesma chave em outra rota não colide
        mockMvc.perform(post("/v1/people", person.replace("Carlos", "Dani")).header("Idempotency-Key", rkey)).andExpect(status().isCreated());
        mockMvc.perform(post("/v1/recurrences", rec)).andExpect(status().isCreated());
        assertThat(count("recurring_transactions")).isEqualTo(2L);
    }

    @Test
    @DisplayName("CA5: valor zero é 422 DEBT_INVALID_PRINCIPAL, acima do teto é 422 AMOUNT_ABOVE_LIMIT, negativo não passa do contrato; pessoa arquivada, de outro usuário e direção/categoria")
    void deveValidarOValor() throws Exception {
        mockMvc.perform(post("/v1/debts", debtJson(maria, "PAYABLE", "0.00", "2026-10-10", null, "Zero"))).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_INVALID_PRINCIPAL"));
        mockMvc.perform(post("/v1/debts", debtJson(maria, "PAYABLE", "1000000000.00", "2026-10-10", null, "Alto"))).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("AMOUNT_ABOVE_LIMIT"));
        mockMvc.perform(post("/v1/debts", debtJson(maria, "PAYABLE", "-5.00", "2026-10-10", null, "Negativo"))).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/debts", debtJson(maria, "PAYABLE", "10.00", "2026-10-10", null, "   "))).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/debts", debtJson(maria, "PAYABLE", "10.00", "1990-01-01", null, "Antiga"))).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());

        mockMvc.perform(post("/v1/people/" + maria + "/archive", "")).andExpect(status().isOk());
        mockMvc.perform(post("/v1/debts", debtJson(maria, "PAYABLE", "10.00", "2026-10-10", null, "Arquivada")))
                .andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("PERSON_ARCHIVED"));
        mockMvc.perform(post("/v1/debts", debtJson(UUID.randomUUID().toString(), "PAYABLE", "10.00", "2026-10-10", null, "Ninguém")))
                .andExpect(status().isNotFound()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("PERSON_NOT_FOUND"));
        // categoria só em PAYABLE (S-137)
        mockMvc.perform(post("/v1/debts", "{\"personId\":\"" + joao + "\",\"direction\":\"RECEIVABLE\",\"principal\":\"10.00\",\"date\":\"2026-10-10\","
                        + "\"description\":\"Com categoria\",\"categoryId\":\"" + food + "\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_CATEGORY_NOT_ALLOWED"));
        assertThat(count("debts")).isZero();
    }

    @Test
    @DisplayName("CA2: perdão com motivo cancela o saldo contra EQUITY (sem despesa/receita); sem motivo é 422 DEBT_REASON_REQUIRED; SETTLED é recusada; novo perdão é DEBT_CLOSED")
    void devePerdoar() throws Exception {
        JsonNode debt = payable("200.00", "2026-11-05");
        String id = debt.get("id").asString();
        long equityBefore = book("EQUITY");

        mockMvc.perform(post("/v1/debts/" + id + "/cancel", "{}")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_REASON_REQUIRED"));
        mockMvc.perform(post("/v1/debts/" + id + "/cancel", voidBody("   "))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBT_REASON_REQUIRED"));
        // paga 50 antes do perdão: o perdão zera só o que resta (150)
        created(payment("50.00", "2026-10-10", id));

        mockMvc.perform(post("/v1/debts/" + id + "/cancel", voidBody("a Maria me perdoou"))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.outstanding.amount").value("0.00")).andExpect(jsonPath("$.closureReason").value("a Maria me perdoou"))
                .andExpect(jsonPath("$.closureDate").value("2026-10-10")).andExpect(jsonPath("$.cancelable").value(false))
                .andExpect(jsonPath("$.voidable").value(false)).andExpect(jsonPath("$.voidBlockedBy").value("DEBT_CLOSED"));
        assertThat(book("PAYABLE")).isZero();
        assertThat(book("EQUITY")).isEqualTo(equityBefore - 15_000L + 0L);
        assertThat(book("EXPENSE")).isZero();
        assertThat(book("INCOME")).isZero();
        assertThat(ledgerTotal()).isZero();
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("0.00");
        assertThat(upcoming("from=2026-10-10&to=2026-11-10").get("items")).isEmpty();

        mockMvc.perform(post("/v1/debts/" + id + "/cancel", voidBody("de novo"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBT_CLOSED"));
        // quitada integralmente: o perdão não vale
        String settled = payable("100.00", null).get("id").asString();
        created(payment("100.00", "2026-10-10", settled));
        mockMvc.perform(post("/v1/debts/" + settled + "/cancel", voidBody("tarde"))).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_ALREADY_SETTLED"));
    }

    @Test
    @DisplayName("CA3: void do registro sem quitações deixa VOIDED e inativa os lançamentos; com quitações é 422 DEBT_HAS_SETTLEMENTS; dívida com movimentação é 422 DEBT_HAS_ORIGIN_TRANSACTION")
    void deveEstornarORegistro() throws Exception {
        String id = payable("200.00", "2026-11-05").get("id").asString();
        String withSettlement = payable("90.00", null).get("id").asString();
        String settlementTx = created(payment("30.00", "2026-10-10", withSettlement)).get("id").asString();

        mockMvc.perform(post("/v1/debts/" + withSettlement + "/void", voidBody("engano"))).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_HAS_SETTLEMENTS"));
        mockMvc.perform(get("/v1/debts/" + withSettlement)).andExpect(status().isOk()).andExpect(jsonPath("$.voidable").value(false))
                .andExpect(jsonPath("$.voidBlockedBy").value("DEBT_HAS_SETTLEMENTS"));

        mockMvc.perform(post("/v1/debts/" + id + "/void", voidBody("registrei errado"))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.closureReason").value("registrei errado")).andExpect(jsonPath("$.outstanding.amount").value("0.00"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE source_type = 'DEBT_RECOGNITION' AND source_id = ?::uuid AND active",
                Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE source_type = 'DEBT_RECOGNITION' AND source_id = ?::uuid AND NOT active",
                Long.class, id)).isEqualTo(2L);
        assertThat(upcoming("from=2026-10-10&to=2026-11-10").get("items")).isEmpty();
        mockMvc.perform(post("/v1/debts/" + id + "/void", voidBody("de novo"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEBT_CLOSED"));
        // estornada a quitação, o registro já pode ser estornado
        mockMvc.perform(post("/v1/transactions/" + settlementTx + "/void", voidBody("engano"))).andExpect(status().isOk());
        mockMvc.perform(post("/v1/debts/" + withSettlement + "/void", voidBody("agora sim"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));

        String loaned = loan("300.00", "2026-10-10", joao, null).get("debt").get("id").asString();
        mockMvc.perform(post("/v1/debts/" + loaned + "/void", voidBody("x"))).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_HAS_ORIGIN_TRANSACTION"));
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("CA4: PUT muda a previsão e a descrição (o compromisso muda de data), o principal não muda; version velha é 409; dívida encerrada é 422 DEBT_CLOSED")
    void deveEditarAPrevisao() throws Exception {
        JsonNode debt = payable("200.00", "2026-11-05");
        String id = debt.get("id").asString();
        mockMvc.perform(put("/v1/debts/" + id, "{\"expectedDate\":\"2026-11-20\",\"description\":\"Almoço de domingo\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.expectedDate").value("2026-11-20"))
                .andExpect(jsonPath("$.description").value("Almoço de domingo")).andExpect(jsonPath("$.principal.amount").value("200.00"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(upcoming("from=2026-10-10&to=2026-11-10").get("items")).isEmpty();
        JsonNode moved = upcoming("from=2026-11-15&to=2026-11-30");
        assertThat(moved.get("items")).hasSize(1);
        assertThat(moved.get("items").get(0).get("dueDate").asString()).isEqualTo("2026-11-20");

        mockMvc.perform(put("/v1/debts/" + id, "{\"description\":\"Velha\",\"version\":0}")).andExpect(status().isConflict())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        // limpar a previsão manda a dívida para "sem data"
        mockMvc.perform(put("/v1/debts/" + id, "{\"description\":\"Sem data\",\"version\":1}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedDate").doesNotExist());
        assertThat(upcoming("from=2026-10-10&to=2027-12-31").get("undated")).hasSize(1);

        mockMvc.perform(post("/v1/debts/" + id + "/cancel", voidBody("perdoada"))).andExpect(status().isOk());
        mockMvc.perform(put("/v1/debts/" + id, "{\"description\":\"Tarde\",\"version\":3}")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_CLOSED"));
    }

    @Test
    @DisplayName("dívida de outro usuário é 404 DEBT_NOT_FOUND em todas as rotas")
    void deveIsolarPorUsuario() throws Exception {
        String id = payable("200.00", null).get("id").asString();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        String otherBearer = auth.bearer(other);
        mockMvc.perform(getAs(otherBearer, "/v1/debts/" + id)).andExpect(status().isNotFound()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("DEBT_NOT_FOUND"));
        mockMvc.perform(putAs(otherBearer, "/v1/debts/" + id, "{\"description\":\"x\",\"version\":0}")).andExpect(status().isNotFound());
        mockMvc.perform(postAs(otherBearer, "/v1/debts/" + id + "/cancel", voidBody("x"))).andExpect(status().isNotFound());
        mockMvc.perform(postAs(otherBearer, "/v1/debts/" + id + "/void", voidBody("x"))).andExpect(status().isNotFound());
        assertThat(body(mockMvc.perform(getAs(otherBearer, "/v1/debts")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andReturn()).get("items")).isEmpty();
        assertThat(money(summary().get("netWorth"), "payables")).isEqualTo("-200.00");
    }
}
