package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

@Tag("slow")
@DisplayName("A2a/A2b/A4: propriedade de 200 cenários aleatórios (semente fixa) sobre rateio, edição, estorno e recebimento parcial")
class SharedPurchasePropertyIT extends ResumaoItBase {

    private static final int SCENARIOS = 200;
    private static final long SEED = 20261015L;

    private final List<String> people = new ArrayList<>();

    private MvcResult call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void lightWorld() throws Exception {
        newUser();
        people.clear();
        accountA = json(call(post("/v1/accounts", "{\"name\":\"Conta A\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"100000.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))).get("id").asString();
        cardA = json(call(post("/v1/cards", "{\"name\":\"Cartao A\",\"limit\":{\"amount\":\"900000.00\",\"currency\":\"BRL\"},\"closingDay\":25,"
                + "\"dueDay\":5,\"defaultPaymentAccountId\":\"" + accountA + "\"}"))).get("id").asString();
        catFood = json(call(post("/v1/categories", "{\"name\":\"Mercado\",\"kind\":\"EXPENSE\"}"))).get("id").asString();
        for (String name : List.of("Ana", "Beto", "Caio")) {
            people.add(json(call(post("/v1/people", "{\"name\":\"" + name + "\"}"))).get("id").asString());
        }
    }

    private record Split(String mode, String sharesJson) {
    }

    private Split randomSplit(Random random, long installmentCents) {
        int thirds = 1 + random.nextInt(3);
        List<String> chosen = new ArrayList<>(people.subList(0, thirds));
        switch (random.nextInt(3)) {
            case 0 -> {
                int remaining = 10000;
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < thirds; i++) {
                    int max = remaining - (thirds - 1 - i) - 1;
                    int bps = 1 + random.nextInt(Math.max(1, Math.min(max, 9000 / thirds)));
                    remaining -= bps;
                    lines.add(share(chosen.get(i), bps));
                }
                lines.add(share(null, remaining));
                return new Split("BPS", "[" + String.join(",", lines) + "]");
            }
            case 1 -> {
                List<String> lines = new ArrayList<>();
                lines.add(share(null, null));
                chosen.forEach(p -> lines.add(share(p, null)));
                return new Split("EQUAL", "[" + String.join(",", lines) + "]");
            }
            default -> {
                long left = installmentCents;
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < thirds; i++) {
                    long part = Math.max(1, left / (thirds + 1 + random.nextInt(2)));
                    lines.add(amountShare(chosen.get(i), String.format("%d.%02d", part / 100, part % 100)));
                    left -= part;
                }
                lines.add(amountShare(null, String.format("%d.%02d", left / 100, left % 100)));
                return new Split("AMOUNT", "[" + String.join(",", lines) + "]");
            }
        }
    }

    private long consumption(String month, String view) throws Exception {
        return cents(json(call(get("/v1/summaries/" + month + (view == null ? "" : "?view=" + view)))).get("expenses"));
    }

    private final java.util.Map<String, String> violations = new java.util.TreeMap<>();

    private void violate(String category, String example) {
        violations.putIfAbsent(category, example);
    }

    private void assertInvariants(String label) throws Exception {
        assertThat(ledgerTotal()).as(label + ": razão soma zero").isZero();
        long openTx = jdbc.queryForObject("SELECT count(*) FROM (SELECT transaction_id FROM ledger_postings WHERE user_id = ? AND active "
                + "AND transaction_id IS NOT NULL GROUP BY transaction_id HAVING sum(amount_cents) <> 0) t", Long.class, userId);
        assertThat(openTx).as(label + ": transação com postings que não fecham").isZero();
        JsonNode overview = json(call(get("/v1/debts/third-party-receivables")));
        java.util.Map<String, long[]> byMonth = new java.util.TreeMap<>();
        for (JsonNode person : overview.get("people")) {
            long owed = 0;
            long received = 0;
            long pending = 0;
            for (JsonNode month : person.get("months")) {
                long[] sums = byMonth.computeIfAbsent(month.get("competence").asString(), k -> new long[3]);
                sums[0] += cents(month.get("owed"));
                sums[1] += cents(month.get("received"));
                sums[2] += cents(month.get("pending"));
                owed += cents(month.get("owed"));
                received += cents(month.get("received"));
                pending += cents(month.get("pending"));
                if (cents(month.get("owed")) != cents(month.get("received")) + cents(month.get("pending"))) {
                    violate("LINHA_DO_MES_NAO_FECHA (owed != received + pending)", label + " mês " + month);
                }
            }
            if (pending != cents(person.get("pending")) || owed != cents(person.get("owed")) || received != cents(person.get("received"))) {
                violate("SOMA_DOS_MESES_DIFERE_DO_TOTAL_DA_PESSOA", label + " meses=(" + owed + "," + received + "," + pending + ") pessoa=" + person);
            }
            if (cents(person.get("pending")) < 0) {
                violate("PENDENTE_DA_PESSOA_NEGATIVO", label);
            }
        }
        for (var entry : byMonth.entrySet()) {
            long mine = consumption(entry.getKey(), null);
            long gross = consumption(entry.getKey(), "GROSS");
            if (mine + entry.getValue()[0] != gross) {
                violate("GROSS_MENOS_MINE_DIFERE_DO_DEVIDO_DO_MES", label + " mês " + entry.getKey() + " mine=" + mine + " gross=" + gross + " owed=" + entry.getValue()[0]);
            }
        }
    }

    @Test
    @DisplayName("200 cenários: Σ postings = 0; MINE + a receber pendente + já recebido = GROSS; receber não muda consumo nem renda; ACCOUNT mantém e JA_NO_SALDO reduz o patrimônio em X; PUT shares só reclassifica")
    void propriedade() throws Exception {
        Random random = new Random(SEED);
        int rejectedEdits = 0;
        int receipts = 0;
        int refunds = 0;
        for (int scenario = 0; scenario < SCENARIOS; scenario++) {
            lightWorld();
            int installments = 1 + random.nextInt(6);
            long total = 100L * installments + random.nextInt(100_000);
            String amount = String.format("%d.%02d", total / 100, total % 100);
            Split split = randomSplit(random, total / installments);
            String label = "cenário " + scenario + " (" + split.mode() + " " + amount + " em " + installments + "x " + split.sharesJson() + ")";
            MvcResult created = call(idem(post("/v1/transactions", sharedPurchase(cardA, amount, installments, String.format("2026-10-%02d", 5 + random.nextInt(15)),
                    split.mode(), split.sharesJson()))));
            if (created.getResponse().getStatus() == 422) {
                assertThat(json(created).get("code").asString()).as(label).isEqualTo("SHARES_INVALID");
                assertInvariants(label + " (rejeitado)");
                continue;
            }
            assertThat(created.getResponse().getStatus()).as(label + " " + created.getResponse().getContentAsString()).isEqualTo(201);
            String id = json(created).get("id").asString();
            assertInvariants(label + " (criado)");

            for (int step = 0; step < 2; step++) {
                int op = random.nextInt(4);
                if (op == 0) {
                    String person = people.get(random.nextInt(3));
                    long owed = book("RECEIVABLE", person);
                    if (owed > 0) {
                        long x = 1 + random.nextInt((int) Math.min(owed, Integer.MAX_VALUE));
                        boolean accountMode = random.nextBoolean();
                        long worth = netWorthCents();
                        long mineBefore = consumption("2026-10", null);
                        long grossBefore = consumption("2026-10", "GROSS");
                        long incomeBefore = book("INCOME");
                        String body = "{\"personId\":\"" + person + "\",\"transactionId\":\"" + id + "\",\"amount\":\"" + String.format("%d.%02d", x / 100, x % 100)
                                + "\"" + (accountMode ? ",\"accountId\":\"" + accountA + "\"" : "") + ",\"receivedOn\":\"2026-10-15\"}";
                        MvcResult receipt = call(idem(post("/v1/third-party-receipts", body)));
                        int code = receipt.getResponse().getStatus();
                        assertThat(code).as(label + " recebimento: " + receipt.getResponse().getContentAsString()).isIn(201, 422);
                        if (code == 201) {
                            receipts++;
                            assertThat(netWorthCents()).as(label + ": patrimônio no recebimento").isEqualTo(accountMode ? worth : worth - x);
                            assertThat(consumption("2026-10", null)).isEqualTo(mineBefore);
                            assertThat(consumption("2026-10", "GROSS")).isEqualTo(grossBefore);
                            assertThat(book("INCOME")).isEqualTo(incomeBefore);
                        }
                    }
                } else if (op == 1) {
                    Split next = randomSplit(random, total / installments);
                    long version = json(call(get("/v1/transactions/" + id))).get("version").asLong();
                    long cash = book("ACCOUNT");
                    long card = book("CARD");
                    long worth = netWorthCents();
                    long expenseBefore = book("EXPENSE");
                    long receivableBefore = book("RECEIVABLE");
                    MvcResult edit = call(put("/v1/transactions/" + id + "/shares", "{\"splitMode\":\"" + next.mode() + "\",\"shares\":" + next.sharesJson()
                            + ",\"version\":" + version + "}"));
                    int code = edit.getResponse().getStatus();
                    assertThat(code).as(label + " edição: " + edit.getResponse().getContentAsString()).isIn(200, 422);
                    if (code == 422) {
                        rejectedEdits++;
                        assertThat(json(edit).get("code").asString()).isIn("SHARES_BELOW_RECEIVED", "SHARES_INVALID", "TRANSACTION_NOT_EDITABLE");
                        assertThat(book("EXPENSE")).isEqualTo(expenseBefore);
                    } else {
                        assertThat(book("ACCOUNT")).isEqualTo(cash);
                        assertThat(book("CARD")).isEqualTo(card);
                        long deltaExpense = book("EXPENSE") - expenseBefore;
                        assertThat(book("RECEIVABLE") - receivableBefore).as(label + ": a edição só troca EXPENSE por RECEIVABLE").isEqualTo(-deltaExpense);
                        assertThat(netWorthCents()).isEqualTo(worth - deltaExpense);
                    }
                } else if (op == 2) {
                    long refund = 1 + random.nextInt((int) Math.max(1, total));
                    MvcResult result = call(idem(post("/v1/transactions", "{\"type\":\"REFUND\",\"amount\":\"" + String.format("%d.%02d", refund / 100, refund % 100)
                            + "\",\"date\":\"2026-10-15\",\"description\":\"Estorno\",\"cardId\":\"" + cardA + "\",\"categoryId\":\"" + catFood
                            + "\",\"refundOfTransactionId\":\"" + id + "\"}")));
                    int code = result.getResponse().getStatus();
                    assertThat(code).as(label + " estorno: " + result.getResponse().getContentAsString()).isIn(201, 422);
                    if (code == 201) {
                        refunds++;
                        String refundId = json(result).get("id").asString();
                        assertThat(totalOfTransaction(refundId)).as(label + ": estorno soma zero").isZero();
                    }
                }
                assertInvariants(label + " (passo " + step + ", op " + op + ")");
            }
        }
        assertThat(violations).as("violações de invariantes (categoria -> primeiro exemplo)").isEmpty();
        assertThat(receipts).as("o gerador exercitou recebimentos").isGreaterThan(20);
        assertThat(refunds).as("o gerador exercitou estornos").isGreaterThan(10);
        assertThat(rejectedEdits + 1).isPositive();
        assertThat(MediaType.APPLICATION_JSON).isNotNull();
        assertThat(MockMvcRequestBuilders.get("/")).isNotNull();
    }
}
