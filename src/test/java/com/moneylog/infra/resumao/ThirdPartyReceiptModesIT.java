package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

@DisplayName("A4: a receber de terceiros e os dois modos de recebimento (conta e já no saldo), replay e exclusão")
class ThirdPartyReceiptModesIT extends ResumaoItBase {

    private String purchase;

    @BeforeEach
    void mundo() throws Exception {
        basicWorld();
        purchase = created(sharedPurchase(cardA, "150.00", 3, "2026-10-05", "BPS", "[" + share(null, 5000) + "," + share(ana, 5000) + "]"))
                .get("id").asString();
    }

    private JsonNode anaOverview() throws Exception {
        JsonNode overview = okBody(get("/v1/debts/third-party-receivables"));
        for (JsonNode person : overview.get("people")) {
            if (person.get("personId").asString().equals(ana)) {
                return person;
            }
        }
        throw new AssertionError("Ana não aparece em a receber");
    }

    @Test
    @DisplayName("Ana com 25,00/mês em 3 parcelas: três competências com owed 25,00, received 0 e pending 25,00; total devido 75,00; fonte CARD_SHARE")
    void aReceberPorCompetencia() throws Exception {
        JsonNode person = anaOverview();
        assertThat(money(person, "owed")).isEqualTo("75.00");
        assertThat(money(person, "pending")).isEqualTo("75.00");
        assertThat(person.get("months")).hasSize(3);
        for (JsonNode month : person.get("months")) {
            assertThat(money(month, "owed")).isEqualTo("25.00");
            assertThat(money(month, "received")).isEqualTo("0.00");
            assertThat(money(month, "pending")).isEqualTo("25.00");
        }
        assertThat(person.get("items").get(0).get("source").asString()).isEqualTo("CARD_SHARE");
    }

    @Test
    @DisplayName("com accountId: ACCOUNT +X / RECEIVABLE -X, settlement ACCOUNT, patrimônio e consumo inalterados, pendente da Ana cai")
    void recebimentoMovendoConta() throws Exception {
        long worth = netWorthTotal();
        JsonNode before = okBody(get("/v1/summaries/2026-10"));
        long cash = book("ACCOUNT");

        JsonNode receipt = receive(ana, purchase, "25.00", accountA, 201);

        assertThat(receipt.get("settlement").asString()).isEqualTo("ACCOUNT");
        assertThat(book("ACCOUNT")).isEqualTo(cash + 2500L);
        assertThat(book("RECEIVABLE", ana)).isEqualTo(7500L - 2500L);
        assertThat(netWorthTotal()).isEqualTo(worth);
        assertThat(okBody(get("/v1/summaries/2026-10"))).isEqualTo(before);
        assertThat(money(anaOverview(), "pending")).isEqualTo("50.00");
        assertThat(book("INCOME")).isZero();
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("sem accountId (já no saldo): RECEIVABLE -X / EQUITY +X, sem ACCOUNT/INCOME/REFUND, consumo MINE e GROSS iguais e patrimônio cai exatamente X")
    void recebimentoJaNoSaldo() throws Exception {
        long worth = netWorthTotal();
        long ledgerWorth = netWorthCents();
        JsonNode netBefore = okBody(get("/v1/dashboard/summary")).get("netWorth");
        JsonNode mine = okBody(get("/v1/summaries/2026-10"));
        JsonNode gross = okBody(get("/v1/summaries/2026-10?view=GROSS"));
        long cash = book("ACCOUNT");
        long equity = book("EQUITY");

        JsonNode receipt = receive(ana, purchase, "25.00", null, 201);

        assertThat(receipt.get("settlement").asString()).isEqualTo("ALREADY_IN_BALANCE");
        assertThat(book("ACCOUNT")).isEqualTo(cash);
        assertThat(book("EQUITY")).isEqualTo(equity + 2500L);
        assertThat(book("INCOME")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ? AND type = 'REFUND'", Long.class, userId)).isZero();
        assertThat(netWorthCents()).as("patrimônio pelo razão").isEqualTo(ledgerWorth - 2500L);
        assertThat(netWorthTotal()).as("patrimônio do dashboard; antes=" + netBefore + " depois=" + okBody(get("/v1/dashboard/summary")).get("netWorth"))
                .isEqualTo(worth - 2500L);
        assertThat(okBody(get("/v1/summaries/2026-10"))).isEqualTo(mine);
        assertThat(okBody(get("/v1/summaries/2026-10?view=GROSS"))).isEqualTo(gross);
        assertThat(money(anaOverview(), "pending")).isEqualTo("50.00");
        assertThat(postings("source_type = 'THIRD_PARTY_RECEIPT_IN_BALANCE'")).hasSize(2);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("replay do modo já no saldo com a mesma Idempotency-Key devolve o mesmo recebimento e não baixa duas vezes; sem chave: 400")
    void replayEIdempotenciaObrigatoria() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"personId\":\"" + ana + "\",\"transactionId\":\"" + purchase + "\",\"amount\":\"25.00\",\"receivedOn\":\"2026-10-15\"}";
        JsonNode first = body(mockMvc.perform(post("/v1/third-party-receipts", body).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andReturn());
        JsonNode second = body(mockMvc.perform(post("/v1/third-party-receipts", body).header("Idempotency-Key", key)).andReturn());

        assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(book("RECEIVABLE", ana)).isEqualTo(7500L - 2500L);

        mockMvc.perform(post("/v1/third-party-receipts", body)).andExpect(status().isBadRequest());
        assertThat(book("RECEIVABLE", ana)).isEqualTo(7500L - 2500L);
    }

    @Test
    @DisplayName("replay do modo com conta devolve o mesmo recebimento e a conta só recebe uma vez")
    void replayComConta() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"personId\":\"" + ana + "\",\"transactionId\":\"" + purchase + "\",\"amount\":\"25.00\",\"accountId\":\"" + accountA
                + "\",\"receivedOn\":\"2026-10-15\"}";
        long cash = book("ACCOUNT");
        JsonNode first = body(mockMvc.perform(post("/v1/third-party-receipts", body).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andReturn());
        JsonNode second = body(mockMvc.perform(post("/v1/third-party-receipts", body).header("Idempotency-Key", key)).andReturn());
        assertThat(second.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(book("ACCOUNT")).isEqualTo(cash + 2500L);
    }

    @Test
    @DisplayName("recebimento acima do pendente: 422 DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING e nada gravado, nos dois modos")
    void recebimentoAcimaDoPendente() throws Exception {
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);
        assertThat(receive(ana, purchase, "75.01", accountA, 422).get("code").asString()).isEqualTo("DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING");
        assertThat(receive(ana, purchase, "75.01", null, 422).get("code").asString()).isEqualTo("DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING");
        receive(ana, purchase, "75.00", accountA, 201);
        assertThat(receive(ana, purchase, "0.01", null, 422).get("code").asString()).isEqualTo("DEBT_SETTLEMENT_EXCEEDS_OUTSTANDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isGreaterThan(postings);
        assertThat(book("RECEIVABLE", ana)).isZero();
    }

    @Test
    @DisplayName("pessoa que não está no rateio, conta de outro usuário e compra de outro usuário: 404/422 sem gravar")
    void recebimentoComDadosInvalidos() throws Exception {
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);
        mockMvc.perform(idem(post("/v1/third-party-receipts", "{\"personId\":\"" + beto + "\",\"transactionId\":\"" + purchase
                + "\",\"amount\":\"10.00\",\"accountId\":\"" + accountA + "\"}"))).andExpect(status().is4xxClientError());
        String other = otherUserBearer();
        mockMvc.perform(idem(postAs(other, "/v1/third-party-receipts", "{\"personId\":\"" + ana + "\",\"transactionId\":\"" + purchase
                + "\",\"amount\":\"10.00\"}"))).andExpect(status().isNotFound());
        mockMvc.perform(getAs(other, "/v1/debts/third-party-receivables")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(postings);
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/third-party-receipts").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE de recebimento lançado no razão: ou desfaz o razão por inteiro (soma zero, RECEIVABLE de volta) ou recusa; nunca deixa meio lançamento")
    void deleteDeRecebimento() throws Exception {
        JsonNode receipt = receive(ana, purchase, "25.00", accountA, 201);
        long cash = book("ACCOUNT");
        int status = mockMvc.perform(MockMvcRequestBuilders.delete("/v1/third-party-receipts/" + receipt.get("id").asString())
                .header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse().getStatus();

        assertThat(status).isIn(204, 422, 409);
        assertThat(ledgerTotal()).isZero();
        if (status == 204) {
            assertThat(book("RECEIVABLE", ana)).isEqualTo(7500L);
            assertThat(book("ACCOUNT")).isEqualTo(cash - 2500L);
        } else {
            assertThat(book("RECEIVABLE", ana)).isEqualTo(5000L);
        }
    }

    @Test
    @DisplayName("DELETE de recebimento já no saldo devolve o RECEIVABLE e o EQUITY; de outro usuário 404; repetir 404")
    void deleteJaNoSaldo() throws Exception {
        JsonNode receipt = receive(ana, purchase, "25.00", null, 201);
        String url = "/v1/third-party-receipts/" + receipt.get("id").asString();
        mockMvc.perform(MockMvcRequestBuilders.delete(url).header(HttpHeaders.AUTHORIZATION, otherUserBearer())).andExpect(status().isNotFound());
        int status = mockMvc.perform(MockMvcRequestBuilders.delete(url).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse().getStatus();
        assertThat(status).isIn(204, 422, 409);
        assertThat(ledgerTotal()).isZero();
        if (status == 204) {
            assertThat(book("RECEIVABLE", ana)).isEqualTo(7500L);
            assertThat(book("EQUITY")).isEqualTo(book("EQUITY"));
            mockMvc.perform(MockMvcRequestBuilders.delete(url).header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("estorno parcial de 20,00 numa compra de 100,00 rateada 50/50: o a receber da Ana é 40,00 (devido e pendente) e a linha de cada mês fecha (devido = recebido + pendente)")
    void estornoParcialReduzOAReceber() throws Exception {
        String shared = created(sharedPurchase(cardB, "100.00", 1, "2026-10-06", "BPS", "[" + share(null, 5000) + "," + share(ana, 5000) + "]")).get("id").asString();
        created("{\"type\":\"REFUND\",\"amount\":\"20.00\",\"date\":\"2026-10-15\",\"description\":\"Estorno\",\"cardId\":\"" + cardB + "\",\"categoryId\":\""
                + catFood + "\",\"refundOfTransactionId\":\"" + shared + "\"}");

        JsonNode person = anaOverview();

        assertThat(book("RECEIVABLE", ana)).as("o razão: Ana deve 75,00 + 40,00").isEqualTo(7500L + 4000L);
        assertThat(money(person, "owed")).isEqualTo("115.00");
        assertThat(money(person, "pending")).as("a tela de a receber precisa bater com o razão").isEqualTo("115.00");
        long pending = 0;
        for (JsonNode month : person.get("months")) {
            assertThat(cents(month.get("owed"))).isEqualTo(cents(month.get("received")) + cents(month.get("pending")));
            pending += cents(month.get("pending"));
        }
        assertThat(pending).isEqualTo(cents(person.get("pending")));
    }
}
