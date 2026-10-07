package com.moneylog.infra.debt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.util.ContractValidation;

/**
 * T78: divisão de despesa (D1 §2.6.1, S-40, S-137) e dívida {@code PAYABLE} sem movimentação com categoria. É a task que mexe no razão:
 * os casos de D1 §2.6.1 (caixa × consumo × patrimônio) e a regra do PR 01 de que a devolução nunca é receita.
 */
@DisplayName("Divisão de despesa e dívida de consumo (T78)")
class ExpenseSharesIT extends E4ItBase {

    private String expense(String amount, String shares) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-10\",\"description\":\"Jantar\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\",\"shares\":" + shares + "}";
    }

    private String share(String amount, String personId) {
        return "{\"amount\":\"" + amount + "\",\"newDebt\":{\"personId\":\"" + personId + "\",\"expectedDate\":\"2026-11-05\"}}";
    }

    @Test
    @DisplayName("CA1: Pix de 200,00 com 100,00 do João: caixa −200 (a linha do extrato), consumo 100, patrimônio −100, recebível +100; a despesa é a origem da dívida")
    void deveDividirADespesa() throws Exception {
        JsonNode tx = created(expense("200.00", "[" + share("100.00", joao) + "]"));
        assertThat(tx.get("type").asString()).isEqualTo("EXPENSE");
        assertThat(money(tx.get("impact"), "cash")).isEqualTo("-200.00");
        assertThat(tx.get("impact").get("consumption")).hasSize(1);
        assertThat(money(tx.get("impact").get("consumption").get(0), "amount")).isEqualTo("100.00");
        assertThat(money(tx.get("impact"), "netWorth")).isEqualTo("-100.00");
        assertThat(tx.get("debt").isNull()).isTrue();
        assertThat(tx.get("shares")).hasSize(1);
        JsonNode share = tx.get("shares").get(0);
        assertThat(share.get("direction").asString()).isEqualTo("RECEIVABLE");
        assertThat(money(share, "principal")).isEqualTo("100.00");
        assertThat(share.get("person").get("name").asString()).isEqualTo("João");
        assertThat(tx.get("editable").asBoolean()).isTrue();
        assertThat(tx.get("voidable").asBoolean()).isTrue();

        JsonNode debt = debt(share.get("id").asString());
        assertThat(debt.get("originTransactionId").asString()).isEqualTo(tx.get("id").asString());
        assertThat(debt.get("origin").asString()).isEqualTo("WITH_MOVEMENT");

        // a mesma conta, por todas as lentes: dashboard, lançamentos por competência, lista e saldo
        assertThat(accountBalance()).isEqualTo("19800.00");
        JsonNode summary = summary();
        assertThat(money(summary.get("month"), "consumption")).isEqualTo("100.00");
        assertThat(money(summary.get("month").get("cashFlow"), "out")).isEqualTo("200.00");
        assertThat(money(summary.get("netWorth"), "total")).isEqualTo("19900.00");
        assertThat(money(summary.get("netWorth"), "receivables")).isEqualTo("100.00");
        JsonNode entries = okBody(get("/v1/entries?competence=2026-10"));
        assertThat(money(entries.get("totals"), "total")).isEqualTo("100.00");
        JsonNode list = okBody(get("/v1/transactions?type=EXPENSE"));
        assertThat(money(list.get("totals").get("cashFlow"), "out")).isEqualTo("200.00");
        assertThat(list.get("items").get(0).get("shares")).hasSize(1);
        assertThat(book("EXPENSE")).isEqualTo(10_000L);
        assertThat(book("RECEIVABLE")).isEqualTo(10_000L);
        assertThat(ledgerTotal()).isZero();
        // a cobrança do João entra nos compromissos (entrada), sem dupla contagem com o consumo
        JsonNode upcoming = upcoming("from=2026-10-10&to=2026-11-10");
        assertThat(upcoming.get("items")).hasSize(1);
        assertThat(upcoming.get("items").get(0).get("kind").asString()).isEqualTo("DEBT_RECEIVABLE");
        assertThat(money(upcoming.get("totals"), "in")).isEqualTo("100.00");
    }

    @Test
    @DisplayName("CA3: o João paga 100,00 (MONEY_RECEIVED com a dívida): caixa +100, consumo inalterado e nenhuma receita")
    void aQuitacaoNaoEReceita() throws Exception {
        JsonNode tx = created(expense("200.00", "[" + share("100.00", joao) + "]"));
        String debtId = tx.get("shares").get(0).get("id").asString();
        JsonNode payment = created(received("100.00", "2026-10-10", debtId));
        assertThat(money(payment.get("impact"), "cash")).isEqualTo("100.00");
        assertThat(payment.get("impact").get("consumption")).isEmpty();
        assertThat(payment.get("debt").get("status").asString()).isEqualTo("SETTLED");

        JsonNode month = summary().get("month");
        assertThat(money(month, "consumption")).isEqualTo("100.00");
        assertThat(money(month, "income")).isEqualTo("0.00");
        assertThat(money(month, "received")).isEqualTo("100.00");
        assertThat(accountBalance()).isEqualTo("19900.00");
        assertThat(book("INCOME")).isZero();
        assertThat(ledgerTotal()).isZero();
        // a despesa agora tem quitação ativa: não se estorna, e a flag antecipa
        JsonNode origin = okBody(get("/v1/transactions/" + tx.get("id").asString()));
        assertThat(origin.get("voidable").asBoolean()).isFalse();
        assertThat(origin.get("voidBlockedBy").asString()).isEqualTo("DEBT_HAS_SETTLEMENTS");
        mockMvc.perform(post("/v1/transactions/" + tx.get("id").asString() + "/void", voidBody("engano"))).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("DEBT_HAS_SETTLEMENTS"));
        assertThat(accountBalance()).isEqualTo("19900.00");
        assertThat(debt(debtId).get("status").asString()).isEqualTo("SETTLED");
    }

    @Test
    @DisplayName("CA2: Σ partes ≥ valor ou parte zero é 422 SHARES_EXCEED_AMOUNT; pessoa arquivada é 422 SHARE_PERSON_ARCHIVED; duas partes à mesma pessoa viram duas dívidas; nada é gravado nas recusas")
    void deveValidarAsPartes() throws Exception {
        createTx(expense("200.00", "[" + share("200.00", joao) + "]")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("SHARES_EXCEED_AMOUNT"));
        createTx(expense("200.00", "[" + share("120.00", joao) + "," + share("80.00", maria) + "]")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SHARES_EXCEED_AMOUNT"));
        createTx(expense("200.00", "[" + share("0.00", joao) + "]")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SHARES_EXCEED_AMOUNT"));
        createTx(expense("200.00", "[]")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/people/" + maria + "/archive", "")).andExpect(status().isOk());
        createTx(expense("200.00", "[" + share("50.00", maria) + "]")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("SHARE_PERSON_ARCHIVED"));
        createTx(expense("200.00", "[" + share("50.00", UUID.randomUUID().toString()) + "]")).andExpect(status().isNotFound())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("PERSON_NOT_FOUND"));
        assertThat(count("transactions")).isZero();
        assertThat(count("debts")).isZero();

        JsonNode tx = created(expense("200.00", "[" + share("30.00", joao) + "," + share("50.00", joao) + "]"));
        assertThat(tx.get("shares")).hasSize(2);
        assertThat(money(tx.get("impact").get("consumption").get(0), "amount")).isEqualTo("120.00");
        assertThat(count("debts")).isEqualTo(2);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("CA4: estornar a despesa dividida sem quitação estorna despesa e dívidas juntas (lançamentos inativos); a mesma Idempotency-Key não duplica nada")
    void deveEstornarADespesaDividida() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = expense("200.00", "[" + share("100.00", joao) + "," + share("40.00", maria) + "]");
        JsonNode first = body(mockMvc.perform(post("/v1/transactions", body).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        JsonNode again = body(mockMvc.perform(post("/v1/transactions", body).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andReturn());
        assertThat(again.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(count("debts")).isEqualTo(2);
        assertThat(count("transactions")).isEqualTo(1);

        mockMvc.perform(post("/v1/transactions/" + first.get("id").asString() + "/void", voidBody("engano"))).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.shares[0].status").value("VOIDED")).andExpect(jsonPath("$.shares[1].status").value("VOIDED"));
        assertThat(accountBalance()).isEqualTo("20000.00");
        assertThat(book("RECEIVABLE")).isZero();
        assertThat(book("EXPENSE")).isZero();
        assertThat(ledgerTotal()).isZero();
        assertThat(upcoming("from=2026-10-10&to=2026-11-10").get("items")).isEmpty();
        JsonNode debt = debt(first.get("shares").get(0).get("id").asString());
        assertThat(debt.get("status").asString()).isEqualTo("VOIDED");
        assertThat(debt.get("closureReason").asString()).isEqualTo("engano");
    }

    @Test
    @DisplayName("CA7: a despesa dividida só muda descrição, categoria e observação; valor, divisão ou nova divisão numa despesa comum são 422 TRANSACTION_NOT_EDITABLE")
    void deveLimitarAEdicao() throws Exception {
        JsonNode tx = created(expense("200.00", "[" + share("100.00", joao) + "]"));
        String id = tx.get("id").asString();
        String edit = "{\"type\":\"EXPENSE\",\"amount\":\"200.00\",\"date\":\"2026-10-10\",\"description\":\"Jantar de aniversário\",\"notes\":\"com a turma\","
                + "\"accountId\":\"" + nubank + "\",\"categoryId\":\"" + subscriptions + "\",\"method\":\"PIX\",\"version\":0}";
        mockMvc.perform(put("/v1/transactions/" + id, edit)).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.description").value("Jantar de aniversário")).andExpect(jsonPath("$.category.name").value("Assinaturas"))
                .andExpect(jsonPath("$.shares.length()").value(1)).andExpect(jsonPath("$.impact.consumption[0].amount.amount").value("100.00"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(ledgerTotal()).isZero();
        assertThat(money(okBody(get("/v1/entries?competence=2026-10&lens=CONSUMPTION&categoryId=" + subscriptions)).get("totals"), "total"))
                .isEqualTo("100.00");
        mockMvc.perform(put("/v1/transactions/" + id, edit.replace("\"200.00\"", "\"300.00\"").replace("\"version\":0", "\"version\":1")))
                .andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE"));
        mockMvc.perform(put("/v1/transactions/" + id, edit.replace("\"version\":0", "\"shares\":[" + share("10.00", maria) + "],\"version\":1")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE"));
        // S-241: repetir as mesmas partes (como o contrato diz) é aceito e não muda nada; outra pessoa ou outro valor segue 422
        String ledgerBefore = String.valueOf(book("RECEIVABLE"));
        mockMvc.perform(put("/v1/transactions/" + id, edit.replace("\"version\":0", "\"shares\":[" + share("100.00", joao) + "],\"version\":1")))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.shares.length()").value(1))
                .andExpect(jsonPath("$.shares[0].person.id").value(joao)).andExpect(jsonPath("$.version").value(2));
        assertThat(String.valueOf(book("RECEIVABLE"))).isEqualTo(ledgerBefore);
        assertThat(ledgerTotal()).isZero();
        mockMvc.perform(put("/v1/transactions/" + id, edit.replace("\"version\":0", "\"shares\":[" + share("90.00", joao) + "],\"version\":2")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE"));
        mockMvc.perform(put("/v1/transactions/" + id, edit.replace("\"version\":0", "\"shares\":[" + share("100.00", joao) + "," + share("5.00", maria) + "],\"version\":2")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE"));
        assertThat(String.valueOf(book("RECEIVABLE"))).isEqualTo(ledgerBefore);
        JsonNode plain = created("{\"type\":\"EXPENSE\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"description\":\"Comum\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}");
        mockMvc.perform(put("/v1/transactions/" + plain.get("id").asString(), "{\"type\":\"EXPENSE\",\"amount\":\"50.00\",\"date\":\"2026-10-10\","
                        + "\"description\":\"Comum\",\"accountId\":\"" + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\",\"shares\":["
                        + share("10.00", maria) + "],\"version\":0}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_EDITABLE"));
    }

    @Test
    @DisplayName("CA5: dívida PAYABLE sem movimentação com categoria (jantar pago pelo amigo, 80,00): consumo +80, caixa 0, patrimônio −80; sem categoria o consumo é 0")
    void deveLancarConsumoDaDividaComCategoria() throws Exception {
        JsonNode debt = createdBody(post("/v1/debts", "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"80.00\","
                + "\"date\":\"2026-10-10\",\"description\":\"jantar pago pelo amigo\",\"categoryId\":\"" + food + "\"}"));
        assertThat(debt.get("category").get("name").asString()).isEqualTo("Alimentação");
        assertThat(accountBalance()).isEqualTo("20000.00");
        JsonNode summary = summary();
        assertThat(money(summary.get("month"), "consumption")).isEqualTo("80.00");
        assertThat(money(summary.get("netWorth"), "total")).isEqualTo("19920.00");
        assertThat(money(summary.get("month").get("cashFlow"), "out")).isEqualTo("0.00");
        assertThat(book("EXPENSE")).isEqualTo(8_000L);
        assertThat(book("PAYABLE")).isEqualTo(-8_000L);
        assertThat(ledgerTotal()).isZero();
        JsonNode entries = okBody(get("/v1/entries?competence=2026-10&categoryId=" + food));
        assertThat(money(entries.get("totals"), "total")).isEqualTo("80.00");

        createdBody(post("/v1/debts", "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"200.00\","
                + "\"date\":\"2026-10-10\",\"description\":\"sem categoria\"}"));
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("80.00");
        // categoria de receita, de outro tipo ou inexistente
        mockMvc.perform(post("/v1/debts", "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"10.00\",\"date\":\"2026-10-10\","
                        + "\"description\":\"x\",\"categoryId\":\"" + salary + "\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("CATEGORY_KIND_MISMATCH"));
        mockMvc.perform(post("/v1/debts", "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"10.00\",\"date\":\"2026-10-10\","
                        + "\"description\":\"x\",\"categoryId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        // perdoada, a dívida mantém o consumo (já aconteceu); estornado o registro, o consumo some
        mockMvc.perform(post("/v1/debts/" + debt.get("id").asString() + "/cancel", voidBody("perdão"))).andExpect(status().isOk());
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("80.00");
        String other = createdBody(post("/v1/debts", "{\"personId\":\"" + joao + "\",\"direction\":\"PAYABLE\",\"principal\":\"30.00\","
                + "\"date\":\"2026-10-10\",\"description\":\"outro\",\"categoryId\":\"" + food + "\"}")).get("id").asString();
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("110.00");
        mockMvc.perform(post("/v1/debts/" + other + "/void", voidBody("errado"))).andExpect(status().isOk());
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("80.00");
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("CA6: combinações de divisão, quitação, perdão e estorno mantêm o razão em zero e o consumo da categoria é sempre a parte do usuário")
    void devePreservarOsInvariantes() throws Exception {
        long expectedConsumption = 0;
        long[][] cases = {{20_000, 10_000}, {1_001, 1}, {99_999, 50_000, 40_000}, {333, 111, 111}};
        for (long[] c : cases) {
            long total = c[0];
            StringBuilder shares = new StringBuilder("[");
            long sum = 0;
            for (int i = 1; i < c.length; i++) {
                shares.append(i > 1 ? "," : "").append(share(String.format("%d.%02d", c[i] / 100, c[i] % 100), i % 2 == 0 ? maria : joao));
                sum += c[i];
            }
            shares.append("]");
            JsonNode tx = created(expense(String.format("%d.%02d", total / 100, total % 100), shares.toString()));
            expectedConsumption += total - sum;
            assertThat(ledgerTotal()).isZero();
            assertThat(money(summary().get("month"), "consumption")).isEqualTo(String.format("%d.%02d", expectedConsumption / 100, expectedConsumption % 100));
            if (c.length == 3 && c[0] == 1_001) {
                // estorna a segunda despesa: o consumo dela sai
                mockMvc.perform(post("/v1/transactions/" + tx.get("id").asString() + "/void", voidBody("x"))).andExpect(status().isOk());
                expectedConsumption -= total - sum;
            }
        }
        assertThat(ledgerTotal()).isZero();
        assertThat(money(summary().get("month"), "consumption")).isEqualTo(String.format("%d.%02d", expectedConsumption / 100, expectedConsumption % 100));
    }

    @Test
    @DisplayName("reembolsar uma despesa dividida: o teto é a parte do usuário (100,00 de 200,00), então o consumo nunca fica negativo; a despesa com reembolso ativo não se estorna")
    void deveLimitarOReembolsoAParteDoUsuario() throws Exception {
        JsonNode tx = created(expense("200.00", "[" + share("100.00", joao) + "]"));
        String refund = "{\"type\":\"REFUND\",\"amount\":\"%s\",\"date\":\"2026-10-10\",\"description\":\"Estorno do restaurante\","
                + "\"accountId\":\"" + nubank + "\",\"categoryId\":\"" + food + "\",\"refundOfTransactionId\":\"" + tx.get("id").asString() + "\"}";
        createTx(refund.formatted("150.00")).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_ORIGINAL"));
        created(refund.formatted("100.00"));
        createTx(refund.formatted("1.00")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REFUND_EXCEEDS_ORIGINAL"));
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("0.00");
        mockMvc.perform(post("/v1/transactions/" + tx.get("id").asString() + "/void", voidBody("engano"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_HAS_ACTIVE_REFUNDS"));
        assertThat(ledgerTotal()).isZero();
    }
}
