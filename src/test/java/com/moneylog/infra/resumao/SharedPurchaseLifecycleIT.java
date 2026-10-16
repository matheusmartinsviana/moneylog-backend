package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

@DisplayName("A2b: alterar rateio, estornar, cancelar e estornar a compra rateada (ADR-022)")
class SharedPurchaseLifecycleIT extends ResumaoItBase {

    private static final String HALF = "BPS";

    @BeforeEach
    void mundo() throws Exception {
        basicWorld();
    }

    private String halfAndHalf(String amount, int installments, String date) throws Exception {
        return created(sharedPurchase(cardA, amount, installments, date, HALF, "[" + share(null, 5000) + "," + share(ana, 5000) + "]"))
                .get("id").asString();
    }

    private long version(String id) throws Exception {
        return okBody(get("/v1/transactions/" + id)).get("version").asLong();
    }

    private String refundBody(String amount, String purchaseId) {
        return "{\"type\":\"REFUND\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-15\",\"description\":\"Estorno da compra\",\"cardId\":\""
                + cardA + "\",\"categoryId\":\"" + catFood + "\",\"refundOfTransactionId\":\"" + purchaseId + "\"}";
    }

    @Test
    @DisplayName("PUT shares em fatura aberta (5000/5000 para 7000/3000): EXPENSE 70,00, RECEIVABLE 30,00 por parcela, CARD e caixa intactos, revisão gravada")
    void reclassificaEmFaturaAberta() throws Exception {
        String id = halfAndHalf("300.00", 3, "2026-10-05");
        long card = book("CARD");
        long cash = book("ACCOUNT");
        long worthBefore = netWorthTotal();
        long revisions = count("transaction_revisions");

        JsonNode updated = putShares(id, HALF, "[" + share(null, 7000) + "," + share(ana, 3000) + "]", version(id), 200);

        assertThat(updated.get("personShares")).hasSize(2);
        assertThat(money(updated, "myAmount")).isEqualTo("210.00");
        byInstallment(id).forEach((number, books) -> assertThat(books).containsEntry("EXPENSE", 7000L).containsEntry("RECEIVABLE", 3000L)
                .containsEntry("CARD", -10000L));
        assertThat(book("CARD")).isEqualTo(card);
        assertThat(book("ACCOUNT")).isEqualTo(cash);
        assertThat(ledgerTotal()).isZero();
        assertThat(count("transaction_revisions")).isGreaterThan(revisions);
        assertThat(netWorthTotal()).as("ADR-022 §7 diz antes = depois; pela contabilidade cai o aumento da minha parte da 1a parcela (20,00)")
                .isEqualTo(worthBefore - 2000L);
    }

    @Test
    @DisplayName("PUT shares com a fatura fechada (P-6): vale, reclassifica só EXPENSE x RECEIVABLE e a fatura continua com o mesmo total")
    void reclassificaEmFaturaFechada() throws Exception {
        String id = halfAndHalf("100.00", 1, "2026-10-05");
        advanceTo(Instant.parse("2026-10-26T15:00:00Z"));
        long card = book("CARD");

        putShares(id, HALF, "[" + share(null, 2000) + "," + share(ana, 8000) + "]", version(id), 200);

        assertThat(byInstallment(id).get(1)).containsEntry("EXPENSE", 2000L).containsEntry("RECEIVABLE", 8000L);
        assertThat(book("CARD")).isEqualTo(card);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("novo devido da Ana abaixo do que ela já pagou: 422 SHARES_BELOW_RECEIVED e nada é gravado")
    void recusaAbaixoDoRecebido() throws Exception {
        String id = halfAndHalf("100.00", 1, "2026-10-05");
        receive(ana, id, "40.00", accountA, 201);
        long before = count("transaction_revisions");
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);

        JsonNode error = putShares(id, HALF, "[" + share(null, 7000) + "," + share(ana, 3000) + "]", version(id), 422);

        assertThat(error.get("code").asString()).isEqualTo("SHARES_BELOW_RECEIVED");
        assertThat(count("transaction_revisions")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(postings);
        putShares(id, HALF, "[" + share(null, 6000) + "," + share(ana, 4000) + "]", version(id), 200);
        assertThat(book("RECEIVABLE", ana)).isEqualTo(4000L - 4000L);
    }

    @Test
    @DisplayName("PUT shares: versão antiga 409, rateio inválido 422 SHARES_INVALID, outro usuário 404, valor da compra continua imutável")
    void errosDoPutShares() throws Exception {
        String id = halfAndHalf("100.00", 1, "2026-10-05");
        long version = version(id);
        putShares(id, HALF, "[" + share(null, 7000) + "," + share(ana, 3000) + "]", version, 200);
        putShares(id, HALF, "[" + share(null, 5000) + "," + share(ana, 5000) + "]", version, 409);
        assertThat(putShares(id, HALF, "[" + share(null, 5000) + "," + share(ana, 4000) + "]", version(id), 422).get("code").asString())
                .isEqualTo("SHARES_INVALID");
        mockMvc.perform(putAs(otherUserBearer(), "/v1/transactions/" + id + "/shares",
                "{\"splitMode\":\"BPS\",\"shares\":[" + share(null, 5000) + "," + share(ana, 5000) + "],\"version\":1}")).andExpect(status().isNotFound());
        mockMvc.perform(put("/v1/transactions/" + id, "{\"type\":\"CARD_PURCHASE\",\"amount\":\"200.00\",\"date\":\"2026-10-05\",\"cardId\":\"" + cardA
                + "\",\"categoryId\":\"" + catFood + "\",\"installments\":1,\"version\":" + version(id) + "}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("PUT shares em compra sem rateio e voltar a 100% meu: vira rateio e depois remove o RECEIVABLE, soma sempre zero")
    void deRateioParaIntegral() throws Exception {
        String id = created(sharedPurchase(cardA, "100.00", 1, "2026-10-05", null, null)).get("id").asString();
        putShares(id, HALF, "[" + share(null, 6000) + "," + share(ana, 4000) + "]", version(id), 200);
        assertThat(receivableOf(id, ana)).isEqualTo(4000L);
        putShares(id, HALF, "[" + share(null, 10000) + "]", version(id), 200);
        assertThat(book("RECEIVABLE")).isZero();
        assertThat(bookOfTransaction(id, "EXPENSE")).isEqualTo(10000L);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("estorno total de compra 100,00 rateada 50/50: EXPENSE -50,00, RECEIVABLE(Ana) -50,00, CARD +100,00, soma zero, sem dupla contagem")
    void estornoTotalRespeitaORateio() throws Exception {
        String id = halfAndHalf("100.00", 1, "2026-10-05");

        String refund = created(refundBody("100.00", id)).get("id").asString();

        assertThat(bookOfTransaction(refund, "EXPENSE")).isEqualTo(-5000L);
        assertThat(bookOfTransaction(refund, "RECEIVABLE")).isEqualTo(-5000L);
        assertThat(bookOfTransaction(refund, "CARD")).isEqualTo(10000L);
        assertThat(totalOfTransaction(refund)).isZero();
        assertThat(receivableOf(id, ana) + jdbc.queryForObject("SELECT coalesce(sum(amount_cents),0) FROM ledger_postings WHERE user_id = ? AND "
                + "transaction_id = ?::uuid AND book_type = 'RECEIVABLE' AND active", Long.class, userId, refund)).isZero();
        assertThat(money(okBody(get("/v1/summaries/2026-10")), "expenses")).isEqualTo("0.00");
        assertThat(money(okBody(get("/v1/summaries/2026-10?view=GROSS")), "expenses")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("estorno parcial de 20,00 em outro mês: o a receber do mês do estorno fica negativo (-10,00) nas duas leituras e a soma dos meses fecha com o pendente da pessoa (40,00)")
    void estornoParcialEmOutroMesFechaASoma() throws Exception {
        String id = halfAndHalf("100.00", 1, "2026-10-05");
        advanceTo(java.time.Instant.parse("2026-12-10T15:00:00Z"));

        created(refundBody("20.00", id).replace("2026-10-15", "2026-12-10"));

        JsonNode december = okBody(get("/v1/debts/third-party-receivables?month=2026-12"));
        JsonNode ana = null;
        for (JsonNode person : december.get("people")) {
            if (person.get("personId").asString().equals(this.ana)) {
                ana = person;
            }
        }
        assertThat(ana).isNotNull();
        long months = 0;
        long decemberPending = 0;
        for (JsonNode month : ana.get("months")) {
            months += cents(month.get("pending"));
            if (month.get("competence").asString().equals("2026-12")) {
                decemberPending = cents(month.get("pending"));
            }
        }
        assertThat(months).isEqualTo(cents(ana.get("pending"))).isEqualTo(4000L);
        assertThat(decemberPending).isEqualTo(-1000L);
        assertThat(cents(december.get("month").get("thirdPartyPending"))).isEqualTo(-1000L);
    }

    @Test
    @DisplayName("estorno parcial de 40,00 numa compra 100,00 rateada 50/50: EXPENSE -20,00, RECEIVABLE -20,00, CARD +40,00")
    void estornoParcialProporcional() throws Exception {
        String id = halfAndHalf("100.00", 1, "2026-10-05");

        String refund = created(refundBody("40.00", id)).get("id").asString();

        assertThat(bookOfTransaction(refund, "EXPENSE")).isEqualTo(-2000L);
        assertThat(bookOfTransaction(refund, "RECEIVABLE")).isEqualTo(-2000L);
        assertThat(bookOfTransaction(refund, "CARD")).isEqualTo(4000L);
        assertThat(money(okBody(get("/v1/summaries/2026-10")), "expenses")).isEqualTo("30.00");
        assertThat(money(okBody(get("/v1/summaries/2026-10?view=GROSS")), "expenses")).isEqualTo("60.00");
    }

    @Test
    @DisplayName("estorno acima do valor da compra: 422 REFUND_EXCEEDS_ORIGINAL e nada gravado")
    void estornoAcimaDoOriginal() throws Exception {
        String id = halfAndHalf("100.00", 1, "2026-10-05");
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);
        createTx(refundBody("100.01", id)).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(postings);
    }

    @Test
    @DisplayName("estorno total com 50,00 já recebidos da Ana: o excedente vira PAYABLE à Ana (P-28b), RECEIVABLE da Ana zera, soma zero")
    void estornoComRecebidoViraPayable() throws Exception {
        String id = halfAndHalf("100.00", 1, "2026-10-05");
        receive(ana, id, "50.00", accountA, 201);

        created(refundBody("100.00", id));

        assertThat(book("RECEIVABLE", ana)).isZero();
        assertThat(book("PAYABLE", ana)).as("a pessoa pagou 50,00 de uma compra estornada: eu devo 50,00").isEqualTo(-5000L);
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("cancelar as 2 parcelas restantes de 3 (a 1ª já em fatura fechada): o recebível pendente da Ana cai 100,00 e a lançada permanece")
    void cancelaParcelasRestantes() throws Exception {
        String id = halfAndHalf("300.00", 3, "2026-10-05");
        advanceTo(Instant.parse("2026-10-26T15:00:00Z"));
        long pendingBefore = receivableOf(id, ana);
        assertThat(pendingBefore).isEqualTo(15000L);

        mockMvc.perform(post("/v1/transactions/" + id + "/cancel-installments", "{\"reason\":\"cancelei o restante\"}")).andExpect(status().isOk());

        assertThat(receivableOf(id, ana)).isEqualTo(5000L);
        assertThat(bookOfTransaction(id, "EXPENSE")).isEqualTo(5000L);
        assertThat(bookOfTransaction(id, "CARD")).isEqualTo(-10000L);
        assertThat(byInstallment(id)).containsOnlyKeys(1);
        assertThat(totalOfTransaction(id)).isZero();
    }

    @Test
    @DisplayName("estornar a compra toda com 30,00 já recebidos: Ana fica sem RECEIVABLE, 30,00 viram PAYABLE e o razão fecha em zero")
    void voidDaCompraComRecebido() throws Exception {
        String id = halfAndHalf("100.00", 1, "2026-10-05");
        receive(ana, id, "30.00", accountA, 201);

        mockMvc.perform(post("/v1/transactions/" + id + "/void", "{\"reason\":\"lançada errado\"}")).andExpect(status().isOk());

        assertThat(book("RECEIVABLE", ana)).isZero();
        assertThat(book("PAYABLE", ana)).isEqualTo(-3000L);
        assertThat(book("EXPENSE")).isZero();
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("estornar compra rateada sem recebimentos: tudo volta a zero, sem PAYABLE")
    void voidDaCompraSemRecebido() throws Exception {
        String id = halfAndHalf("100.00", 1, "2026-10-05");
        mockMvc.perform(post("/v1/transactions/" + id + "/void", "{\"reason\":\"lançada errado\"}")).andExpect(status().isOk());
        assertThat(book("RECEIVABLE")).isZero();
        assertThat(book("PAYABLE")).isZero();
        assertThat(book("EXPENSE")).isZero();
        assertThat(UUID.fromString(id)).isNotNull();
    }
}
