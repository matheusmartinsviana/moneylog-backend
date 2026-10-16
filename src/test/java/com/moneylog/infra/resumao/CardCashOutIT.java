package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

@DisplayName("F4/F5: Pix no crédito como empréstimo (ADR-031), taxa efetiva (TIR) e guardas de edição")
class CardCashOutIT extends ResumaoItBase {

    @BeforeEach
    void mundo() throws Exception {
        basicWorld();
    }

    private String cashOut(String beneficiary, String total) {
        return "{\"cardId\":\"" + cardA + "\",\"principal\":\"1000.00\",\"installments\":4,\"totalWithInterest\":\"" + total + "\","
                + "\"firstInvoiceDate\":\"2026-10-10\",\"channel\":\"Pix no credito\""
                + (beneficiary == null ? ",\"accountId\":\"" + accountA + "\"" : ",\"beneficiaryPersonId\":\"" + beneficiary + "\"") + "}";
    }

    private JsonNode debtOf(JsonNode response) {
        return response;
    }

    private String transactionOf(JsonNode debt) {
        return debt.get("originTransactionId").asString();
    }

    @Test
    @DisplayName("principal 1.000,00 em 4x de 280,00 no Cartão A: ACCOUNT +1.000,00, EXPENSE só dos juros (120,00), CARD -1.120,00, soma zero, nenhuma renda, consumo do mês longe de 1.000,00")
    void casoDoPr01() throws Exception {
        long cash = book("ACCOUNT");
        JsonNode debt = createdBody(idem(post("/v1/card-cash-outs", cashOut(null, "1120.00"))));
        String tx = transactionOf(debt);

        assertThat(book("ACCOUNT") - cash).isEqualTo(100000L);
        assertThat(book("EXPENSE")).as("o principal nunca é despesa; só os juros").isEqualTo(12000L);
        assertThat(book("CARD")).isEqualTo(-112000L);
        assertThat(book("INCOME")).isZero();
        assertThat(totalOfTransaction(tx)).isZero();
        assertThat(ledgerTotal()).isZero();
        assertThat(cents(okBody(get("/v1/summaries/2026-10")).get("expenses"))).isLessThanOrEqualTo(12000L);
        assertThat(cents(okBody(get("/v1/summaries/2026-10")).get("income"))).isZero();
        assertThat(debt.get("kind").asString()).isEqualTo("CARD_CASH_OUT");
        assertThat(debt.get("channel").asString()).isEqualTo("Pix no credito");
    }

    @Test
    @DisplayName("a parcela de 280,00 compõe a fatura cheia (1.120,00 no total das 4 faturas) e a taxa efetiva é a TIR de referência (469 bps a.m., 7.337 bps a.a.)")
    void faturaETaxaEfetiva() throws Exception {
        JsonNode debt = createdBody(idem(post("/v1/card-cash-outs", cashOut(null, "1120.00"))));

        JsonNode detail = okBody(get("/v1/debts/" + debt.get("id").asString()));
        long invoiced = 0;
        for (JsonNode invoice : okBody(get("/v1/cards/" + cardA + "/invoices")).get("items")) {
            invoiced += cents(invoice.get("total"));
        }

        assertThat(invoiced).isEqualTo(112000L);
        JsonNode rate = detail.get("effectiveRate");
        assertThat(rate.get("method").asString()).isEqualTo("IRR");
        assertThat((double) rate.get("monthlyBps").asLong()).isBetween(468.0, 470.0);
        assertThat((double) rate.get("annualBps").asLong()).isBetween(7336.0, 7339.0);
    }

    @Test
    @DisplayName("Pix feito para a Ana: o principal vira RECEIVABLE(Ana) de 1.000,00 em a receber, nenhum ACCOUNT, e o consumo meu é só o que absorvo dos juros")
    void pixParaTerceiro() throws Exception {
        long cash = book("ACCOUNT");
        JsonNode debt = createdBody(idem(post("/v1/card-cash-outs", cashOut(ana, "1120.00"))));

        assertThat(book("ACCOUNT")).isEqualTo(cash);
        assertThat(book("RECEIVABLE", debt.get("id").asString())).isEqualTo(100000L);
        assertThat(book("CARD")).isEqualTo(-112000L);
        assertThat(ledgerTotal()).isZero();
        assertThat(book("EXPENSE")).as("principal de terceiro nunca é consumo meu").isLessThanOrEqualTo(12000L);
        JsonNode receivables = okBody(get("/v1/debts/third-party-receivables"));
        JsonNode anaLine = null;
        for (JsonNode person : receivables.get("people")) {
            if (person.get("personId").asString().equals(ana)) {
                anaLine = person;
            }
        }
        assertThat(anaLine).as("Ana aparece em a receber").isNotNull();
        assertThat(money(anaLine, "pending")).isEqualTo("1000.00");
        assertThat(debt.get("kind").asString()).isEqualTo("CARD_CASH_OUT");
    }

    @Test
    @DisplayName("taxa mensal em vez do total: rateBps gera parcelas com resto na primeira, soma zero e o total com juros acima do principal; sem total nem taxa: 4xx")
    void porTaxa() throws Exception {
        JsonNode debt = createdBody(idem(post("/v1/card-cash-outs", "{\"cardId\":\"" + cardA + "\",\"accountId\":\"" + accountA
                + "\",\"principal\":\"1000.00\",\"installments\":3,\"rateBps\":200,\"firstInvoiceDate\":\"2026-10-10\"}")));
        assertThat(ledgerTotal()).isZero();
        assertThat(book("CARD")).isLessThan(-100000L);
        assertThat(debt.get("kind").asString()).isEqualTo("CARD_CASH_OUT");
        mockMvc.perform(idem(post("/v1/card-cash-outs", "{\"cardId\":\"" + cardA + "\",\"accountId\":\"" + accountA
                + "\",\"principal\":\"1000.00\",\"installments\":3,\"firstInvoiceDate\":\"2026-10-10\"}"))).andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("entradas inválidas: principal zero, parcelas 0 e 37, total abaixo do principal, conta e beneficiário juntos, nada informado de destino, cartão e pessoa de outro usuário: 4xx e nada gravado")
    void entradasInvalidas() throws Exception {
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);
        String base = "\"firstInvoiceDate\":\"2026-10-10\"";
        String[] bodies = {
                "{\"cardId\":\"" + cardA + "\",\"accountId\":\"" + accountA + "\",\"principal\":\"0.00\",\"installments\":4,\"totalWithInterest\":\"10.00\"," + base + "}",
                "{\"cardId\":\"" + cardA + "\",\"accountId\":\"" + accountA + "\",\"principal\":\"100.00\",\"installments\":0,\"totalWithInterest\":\"110.00\"," + base + "}",
                "{\"cardId\":\"" + cardA + "\",\"accountId\":\"" + accountA + "\",\"principal\":\"100.00\",\"installments\":37,\"totalWithInterest\":\"110.00\"," + base + "}",
                "{\"cardId\":\"" + cardA + "\",\"accountId\":\"" + accountA + "\",\"principal\":\"100.00\",\"installments\":4,\"totalWithInterest\":\"90.00\"," + base + "}",
                "{\"cardId\":\"" + cardA + "\",\"accountId\":\"" + accountA + "\",\"beneficiaryPersonId\":\"" + ana
                        + "\",\"principal\":\"100.00\",\"installments\":4,\"totalWithInterest\":\"110.00\"," + base + "}",
                "{\"cardId\":\"" + cardA + "\",\"principal\":\"100.00\",\"installments\":4,\"totalWithInterest\":\"110.00\"," + base + "}"};
        for (String body : bodies) {
            mockMvc.perform(idem(post("/v1/card-cash-outs", body))).andExpect(status().is4xxClientError());
        }
        String other = otherUserBearer();
        mockMvc.perform(idem(postAs(other, "/v1/card-cash-outs", cashOut(null, "1120.00")))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(postings);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/card-cash-outs").contentType("application/json")
                .content(cashOut(null, "1120.00"))).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("replay com a mesma Idempotency-Key devolve a mesma dívida e o razão não dobra")
    void replay() throws Exception {
        String key = java.util.UUID.randomUUID().toString();
        JsonNode first = body(mockMvc.perform(post("/v1/card-cash-outs", cashOut(null, "1120.00")).header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andReturn());
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);
        JsonNode second = body(mockMvc.perform(post("/v1/card-cash-outs", cashOut(null, "1120.00")).header("Idempotency-Key", key)).andReturn());
        assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(postings);
    }

    @Test
    @DisplayName("guarda C2: editar a descrição pelo PUT da compra não reconstrói o Pix no crédito como despesa (o principal continua fora do consumo e a conta continua com +1.000,00)")
    void editarNaoViraDespesa() throws Exception {
        JsonNode debt = createdBody(idem(post("/v1/card-cash-outs", cashOut(null, "1120.00"))));
        String tx = transactionOf(debt);
        JsonNode purchase = okBody(get("/v1/transactions/" + tx));
        long cash = book("ACCOUNT");

        int edit = mockMvc.perform(put("/v1/transactions/" + tx, "{\"type\":\"CARD_PURCHASE\",\"amount\":\"" + money(purchase, "amount") + "\",\"date\":\""
                + purchase.get("date").asString() + "\",\"description\":\"Renomeado\",\"cardId\":\"" + cardA + "\",\"categoryId\":\""
                + purchase.get("category").get("id").asString() + "\",\"installments\":4,\"version\":" + purchase.get("version").asLong() + "}"))
                .andReturn().getResponse().getStatus();

        assertThat(edit).isIn(200, 422);
        assertThat(book("EXPENSE")).as("o principal não pode virar despesa depois da edição").isEqualTo(12000L);
        assertThat(book("ACCOUNT")).isEqualTo(cash);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("guarda C2: PUT shares e estorno parcial do Pix no crédito são recusados (422) ou preservam o principal; nunca viram despesa nem perdem o ACCOUNT")
    void sharesEEstornoParcial() throws Exception {
        JsonNode debt = createdBody(idem(post("/v1/card-cash-outs", cashOut(null, "1120.00"))));
        String tx = transactionOf(debt);
        JsonNode purchase = okBody(get("/v1/transactions/" + tx));
        long cash = book("ACCOUNT");

        int shares = mockMvc.perform(put("/v1/transactions/" + tx + "/shares", "{\"splitMode\":\"BPS\",\"shares\":[" + share(null, 5000) + "," + share(ana, 5000)
                + "],\"version\":" + purchase.get("version").asLong() + "}")).andReturn().getResponse().getStatus();
        int refund = mockMvc.perform(idem(post("/v1/transactions", "{\"type\":\"REFUND\",\"amount\":\"100.00\",\"date\":\"2026-10-10\",\"description\":\"Estorno\","
                + "\"cardId\":\"" + cardA + "\",\"categoryId\":\"" + purchase.get("category").get("id").asString() + "\",\"refundOfTransactionId\":\"" + tx
                + "\"}"))).andReturn().getResponse().getStatus();

        assertThat(shares).isIn(422, 200);
        assertThat(refund).isIn(201, 422);
        assertThat(book("ACCOUNT")).isEqualTo(cash);
        assertThat(book("EXPENSE")).as("juros 120,00 menos o estorno parcial, nunca o principal").isLessThanOrEqualTo(12000L);
        assertThat(book("RECEIVABLE")).as("rateio do Pix no crédito não cria recebível sobre o principal já na conta").isZero();
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("guarda C2: cancelar as parcelas restantes do Pix no crédito não deixa o principal na conta com a dívida encolhida: recusa ou reverte o principal; razão sempre zero")
    void cancelarParcelasRestantes() throws Exception {
        JsonNode debt = createdBody(idem(post("/v1/card-cash-outs", cashOut(null, "1120.00"))));
        String tx = transactionOf(debt);
        advanceTo(Instant.parse("2026-10-26T15:00:00Z"));
        long cash = book("ACCOUNT");
        long card = book("CARD");

        int cancel = mockMvc.perform(post("/v1/transactions/" + tx + "/cancel-installments", "{\"reason\":\"cancelei\"}")).andReturn().getResponse().getStatus();

        assertThat(ledgerTotal()).isZero();
        if (cancel == 200) {
            long cardAfter = book("CARD");
            long freed = cardAfter - card;
            assertThat(freed).as("cartão devolve parcelas canceladas").isPositive();
            assertThat(book("ACCOUNT")).as("com parcelas canceladas o principal proporcional tem de sair da conta").isLessThan(cash);
        } else {
            assertThat(cancel).isEqualTo(422);
            assertThat(book("CARD")).isEqualTo(card);
        }
    }

    @Test
    @DisplayName("estornar o Pix no crédito inteiro (fatura aberta): reverte conta, cartão e juros e a dívida fica estornada; razão zero")
    void estornoTotal() throws Exception {
        JsonNode debt = createdBody(idem(post("/v1/card-cash-outs", cashOut(null, "1120.00"))));
        String tx = transactionOf(debt);
        long cash = book("ACCOUNT");

        mockMvc.perform(post("/v1/transactions/" + tx + "/void", "{\"reason\":\"errei\"}")).andExpect(status().isOk());

        assertThat(book("ACCOUNT")).isEqualTo(cash - 100000L);
        assertThat(book("CARD")).isZero();
        assertThat(book("EXPENSE")).isZero();
        assertThat(ledgerTotal()).isZero();
        assertThat(okBody(get("/v1/debts/" + debt.get("id").asString())).get("status").asString()).isIn("VOIDED", "CANCELED");
    }
}
