package com.moneylog.infra.thirdparty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;

@DisplayName("Dinheiro a receber de terceiros: bruto x terceiros x líquido (ADR-014)")
class ThirdPartyReceivablesIT extends E4ItBase {

    private String purchase(String amount, String date) throws Exception {
        return created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"Compra\",\"cardId\":\""
                + card + "\",\"categoryId\":\"" + food + "\",\"installments\":1}").get("id").asString();
    }

    @Test
    @DisplayName("marcar não muda razão nem consumo; a receber = valor da compra; o REFUND ligado zera o pendente, reduz o consumo e não é receita")
    void deveAcompanharAteOReembolso() throws Exception {
        String tx = purchase("430.00", "2026-10-05");
        long ledger = ledgerTotal();
        long entries = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);
        JsonNode mark = body(mockMvc.perform(post("/v1/third-party-expenses", "{\"transactionId\":\"" + tx + "\",\"personId\":\"" + maria + "\"}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(mark.get("transactionId").asString()).isEqualTo(tx);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(entries);
        assertThat(ledgerTotal()).isEqualTo(ledger);
        mockMvc.perform(post("/v1/third-party-expenses", "{\"transactionId\":\"" + tx + "\",\"personId\":\"" + maria + "\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("THIRD_PARTY_EXPENSE_ALREADY_MARKED"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/third-party-expenses/" + mark.get("id").asString())
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isNoContent());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/third-party-expenses/" + mark.get("id").asString())
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isNotFound());
        assertThat(ledgerTotal()).isEqualTo(ledger);
        mark = body(mockMvc.perform(post("/v1/third-party-expenses", "{\"transactionId\":\"" + tx + "\",\"personId\":\"" + joao + "\"}"))
                .andExpect(status().isCreated()).andReturn());
        assertThat(okBody(get("/v1/debts/third-party-receivables")).get("people").get(0).get("name").asString()).isEqualTo("João");

        JsonNode before = okBody(get("/v1/debts/third-party-receivables?month=2026-10"));
        assertThat(money(before, "owed")).isEqualTo("430.00");
        assertThat(money(before, "received")).isEqualTo("0.00");
        assertThat(money(before, "pending")).isEqualTo("430.00");
        assertThat(before.get("people")).hasSize(1);
        assertThat(before.get("people").get(0).get("items").get(0).get("source").asString()).isEqualTo("EXPENSE");
        JsonNode month = before.get("month");
        assertThat(money(month, "grossConsumption")).isEqualTo("430.00");
        assertThat(money(month, "personalNet")).isEqualTo("430.00");
        assertThat(money(month, "thirdPartyConsumption")).isEqualTo("430.00");
        assertThat(money(month, "thirdPartyReimbursed")).isEqualTo("0.00");
        assertThat(money(month, "thirdPartyPending")).isEqualTo("430.00");

        created("{\"type\":\"REFUND\",\"amount\":\"430.00\",\"date\":\"2026-10-08\",\"description\":\"Reembolso\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"refundOfTransactionId\":\"" + tx + "\"}");
        JsonNode after = okBody(get("/v1/debts/third-party-receivables?month=2026-10"));
        assertThat(money(after, "received")).isEqualTo("430.00");
        assertThat(money(after, "pending")).isEqualTo("0.00");
        assertThat(after.get("people").get(0).get("items").get(0).get("status").asString()).isEqualTo("RECEIVED");
        assertThat(money(after.get("month"), "grossConsumption")).isEqualTo("430.00");
        assertThat(money(after.get("month"), "refunds")).isEqualTo("430.00");
        assertThat(money(after.get("month"), "personalNet")).isEqualTo("0.00");
        assertThat(money(after.get("month"), "thirdPartyReimbursed")).isEqualTo("430.00");
        assertThat(money(after.get("month"), "thirdPartyPending")).isEqualTo("0.00");
        assertThat(money(summary().get("month"), "income")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("empréstimo a receber entra por pessoa, sem dupla contagem com a despesa de terceiro; despesa estornada sai; outro usuário recebe 404 e vê vazio")
    void deveSomarDividaEDespesaSemDuplicar() throws Exception {
        loan("100.00", "2026-10-03", maria, null);
        String tx = purchase("50.00", "2026-10-05");
        mockMvc.perform(post("/v1/third-party-expenses", "{\"transactionId\":\"" + tx + "\",\"personId\":\"" + maria + "\",\"note\":\"mercado\"}"))
                .andExpect(status().isCreated());
        JsonNode overview = okBody(get("/v1/debts/third-party-receivables"));
        assertThat(money(overview, "owed")).isEqualTo("150.00");
        assertThat(money(overview, "pending")).isEqualTo("150.00");
        assertThat(overview.get("people").get(0).get("items")).hasSize(2);

        mockMvc.perform(post("/v1/transactions/" + tx + "/void", voidBody("lançada errado"))).andExpect(status().isOk());
        assertThat(money(okBody(get("/v1/debts/third-party-receivables")), "owed")).isEqualTo("100.00");

        UUID other = UUID.randomUUID();
        committed(() -> com.moneylog.util.SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        String otherBearer = auth.bearer(other);
        mockMvc.perform(postAs(otherBearer, "/v1/third-party-expenses", "{\"transactionId\":\"" + tx + "\",\"personId\":\"" + maria + "\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(getAs(otherBearer, "/v1/debts/third-party-receivables")).andExpect(status().isOk())
                .andExpect(jsonPath("$.people.length()").value(0)).andExpect(jsonPath("$.owed.amount").value("0.00"));
        mockMvc.perform(get("/v1/debts/third-party-receivables?month=2026-13")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("recebimento já refletido no saldo, sem movimento: o ligado reduz o pendente da despesa marcada (parte comprovada); o sem vínculo vira item já recebido; razão e caixa não mudam")
    void deveRegistrarRecebimentoSemMovimento() throws Exception {
        String tx = purchase("600.00", "2026-10-05");
        mockMvc.perform(post("/v1/third-party-expenses", "{\"transactionId\":\"" + tx + "\",\"personId\":\"" + maria + "\",\"amount\":\"120.00\"}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.amount.amount").value("120.00"));
        mockMvc.perform(post("/v1/third-party-expenses", "{\"transactionId\":\"" + tx + "\",\"personId\":\"" + joao + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        long ledger = ledgerTotal();
        String balance = accountBalance();
        long transactions = count("transactions");
        mockMvc.perform(post("/v1/third-party-receipts", "{\"personId\":\"" + maria + "\",\"transactionId\":\"" + tx + "\",\"amount\":\"120.00\",\"note\":\"parte comprovada\"}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/third-party-receipts", "{\"personId\":\"" + maria + "\",\"amount\":\"80.00\",\"note\":\"origem a confirmar\"}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms());
        String orphan = jdbc.queryForObject("SELECT id FROM third_party_receipts WHERE user_id = ? AND transaction_id IS NULL", String.class, userId);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/third-party-receipts/" + orphan)
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isNoContent());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/third-party-receipts/" + orphan)
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isNotFound());
        mockMvc.perform(post("/v1/third-party-receipts", "{\"personId\":\"" + maria + "\",\"amount\":\"80.00\",\"note\":\"origem a confirmar\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/third-party-receipts", "{\"personId\":\"" + joao + "\",\"transactionId\":\"" + tx + "\",\"amount\":\"1.00\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/v1/third-party-receipts", "{\"personId\":\"" + maria + "\",\"amount\":\"0.00\"}")).andExpect(status().isUnprocessableEntity());
        assertThat(ledgerTotal()).isEqualTo(ledger);
        assertThat(accountBalance()).isEqualTo(balance);
        assertThat(count("transactions")).isEqualTo(transactions);
        JsonNode view = okBody(get("/v1/debts/third-party-receivables"));
        assertThat(money(view, "owed")).isEqualTo("200.00");
        assertThat(money(view, "received")).isEqualTo("200.00");
        assertThat(money(view, "pending")).isEqualTo("0.00");
        assertThat(view.get("people").get(0).get("items")).hasSize(2);
        assertThat(money(summary().get("month"), "income")).isEqualTo("0.00");
    }
}
