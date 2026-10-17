package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

@DisplayName("A2c: migração das marcas antigas de terceiro para o rateio (dry-run, apply, hash, idempotência, REFUND neutralizado)")
class ShareMigrationIT extends ResumaoItBase {

    @BeforeEach
    void mundo() throws Exception {
        basicWorld();
    }

    private String legacyPurchase(String amount, int installments, String date) throws Exception {
        return created(sharedPurchase(cardA, amount, installments, date, null, null)).get("id").asString();
    }

    private void mark(String transactionId, String personId, String amountOrNull) throws Exception {
        mockMvc.perform(post("/v1/third-party-expenses", "{\"transactionId\":\"" + transactionId + "\",\"personId\":\"" + personId + "\""
                + (amountOrNull == null ? "" : ",\"amount\":\"" + amountOrNull + "\"") + "}")).andExpect(status().isCreated());
    }

    private void refund(String purchaseId, String amount) throws Exception {
        created("{\"type\":\"REFUND\",\"amount\":\"" + amount + "\",\"date\":\"2026-10-12\",\"description\":\"Reembolso\",\"accountId\":\"" + accountA
                + "\",\"categoryId\":\"" + catFood + "\",\"refundOfTransactionId\":\"" + purchaseId + "\"}");
    }

    private JsonNode dryRun() throws Exception {
        return okBody(post("/v1/third-party-expenses/share-migration/dry-run", ""));
    }

    private MockMvcResultActionsHolder apply(String hash, boolean backup) throws Exception {
        return new MockMvcResultActionsHolder(mockMvc.perform(post("/v1/third-party-expenses/share-migration/apply",
                "{\"reportHash\":\"" + hash + "\",\"backupConfirmed\":" + backup + "}")).andReturn());
    }

    private record MockMvcResultActionsHolder(org.springframework.test.web.servlet.MvcResult result) {
        int status() {
            return result.getResponse().getStatus();
        }

        JsonNode json(tools.jackson.databind.ObjectMapper mapper) throws Exception {
            return mapper.readTree(result.getResponse().getContentAsString());
        }
    }

    private JsonNode purchaseOf(JsonNode report, String transactionId) {
        for (JsonNode purchase : report.get("purchases")) {
            if (purchase.get("transactionId").asString().equals(transactionId)) {
                return purchase;
            }
        }
        throw new AssertionError("compra " + transactionId + " fora do relatório");
    }

    @Test
    @DisplayName("dry-run não grava nada (razão, transações, revisões e marcas intactos), traz hash de 64 hex, receivableRecognized em destaque e por compra/mês")
    void dryRunNaoGrava() throws Exception {
        String full = legacyPurchase("100.00", 1, "2026-10-05");
        mark(full, ana, null);
        refund(full, "100.00");
        String partial = legacyPurchase("300.00", 3, "2026-10-06");
        mark(partial, beto, "90.00");
        refund(partial, "30.00");
        String none = legacyPurchase("80.00", 1, "2026-10-07");
        mark(none, ana, null);
        legacyPurchase("55.00", 1, "2026-10-08");

        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);
        long transactions = count("transactions");
        long revisions = count("transaction_revisions");
        long marks = count("third_party_expenses");

        JsonNode report = dryRun();

        assertThat(report.get("hash").asString()).matches("^[0-9a-f]{64}$");
        assertThat(report.get("eligible").asInt()).isEqualTo(3);
        assertThat(report.get("exceptions").asInt()).isZero();
        assertThat(report.get("purchases")).hasSize(3);
        assertThat(report.get("months")).isNotEmpty();
        assertThat(cents(report.get("receivableRecognized"))).isEqualTo(10000L - 10000L + 9000L - 3000L + 8000L);
        assertThat(cents(report.get("refundsNeutralized"))).isEqualTo(13000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(postings);
        assertThat(count("transactions")).isEqualTo(transactions);
        assertThat(count("transaction_revisions")).isEqualTo(revisions);
        assertThat(count("third_party_expenses")).isEqualTo(marks);
        assertThat(dryRun().get("hash").asString()).as("o mesmo estado produz o mesmo hash").isEqualTo(report.get("hash").asString());
    }

    @Test
    @DisplayName("apply com o hash: consumo MINE = P - t, pendente = t - r e MINE + pendente + reembolsado = P (nunca P - t - r); caixa igual, patrimônio sobe em receivableRecognized")
    void provaDeNaoDuplaContagem() throws Exception {
        String full = legacyPurchase("100.00", 1, "2026-10-05");
        mark(full, ana, null);
        refund(full, "100.00");
        long consumptionBefore = cents(okBody(get("/v1/summaries/2026-10")).get("expenses"));
        assertThat(consumptionBefore).as("legado: o REFUND abate o consumo").isZero();
        long cash = book("ACCOUNT");
        long worth = netWorthCents();
        JsonNode report = dryRun();
        long recognized = cents(report.get("receivableRecognized"));

        var applied = apply(report.get("hash").asString(), true);

        assertThat(applied.status()).isEqualTo(200);
        assertThat(applied.json(objectMapper).get("alreadyApplied").asBoolean()).isFalse();
        long mine = cents(okBody(get("/v1/summaries/2026-10")).get("expenses"));
        long gross = cents(okBody(get("/v1/summaries/2026-10?view=GROSS")).get("expenses"));
        JsonNode anaOverview = null;
        for (JsonNode person : okBody(get("/v1/debts/third-party-receivables")).get("people")) {
            if (person.get("personId").asString().equals(ana)) {
                anaOverview = person;
            }
        }
        long pending = cents(anaOverview.get("pending"));
        long reimbursed = cents(anaOverview.get("received"));
        assertThat(mine).as("MINE = P - t").isEqualTo(10000L - 10000L);
        assertThat(pending).as("pendente = t - r").isZero();
        assertThat(reimbursed).isEqualTo(10000L);
        assertThat(mine + pending + reimbursed).as("MINE + pendente + reembolsado = P").isEqualTo(10000L);
        assertThat(gross).isEqualTo(10000L);
        assertThat(book("ACCOUNT")).as("caixa antes = depois").isEqualTo(cash);
        assertThat(netWorthCents()).as("patrimônio sobe em receivableRecognized").isEqualTo(worth + recognized);
        assertThat(ledgerTotal()).isZero();
        assertThat(count("transactions")).as("nenhuma transação some").isEqualTo(2L);
    }

    @Test
    @DisplayName("marca parcial (valor 60,00 de 100,00) com reembolso de 60,00 e marca sem reembolso: cada compra fecha P = MINE + pendente + reembolsado")
    void marcaParcialESemReembolso() throws Exception {
        String partial = legacyPurchase("100.00", 1, "2026-10-05");
        mark(partial, ana, "60.00");
        refund(partial, "60.00");
        String none = legacyPurchase("40.00", 1, "2026-10-06");
        mark(none, beto, null);

        JsonNode report = dryRun();
        assertThat(report.get("eligible").asInt()).isEqualTo(2);
        JsonNode partialLine = purchaseOf(report, partial);
        assertThat(partialLine.get("status").asString()).isEqualTo("MIGRATE");
        assertThat(cents(partialLine.get("third"))).isEqualTo(6000L);
        assertThat(cents(partialLine.get("refunded"))).isEqualTo(6000L);
        JsonNode noneLine = purchaseOf(report, none);
        assertThat(cents(noneLine.get("third"))).isEqualTo(4000L);
        assertThat(cents(noneLine.get("refunded"))).isZero();

        assertThat(apply(report.get("hash").asString(), true).status()).isEqualTo(200);

        assertThat(cents(okBody(get("/v1/summaries/2026-10")).get("expenses"))).isEqualTo((10000L - 6000L) + (4000L - 4000L));
        assertThat(cents(okBody(get("/v1/summaries/2026-10?view=GROSS")).get("expenses"))).isEqualTo(10000L + 4000L);
        assertThat(book("RECEIVABLE", ana) - 0).isEqualTo(6000L - 6000L);
        assertThat(book("RECEIVABLE", beto)).isEqualTo(4000L);
    }

    @Test
    @DisplayName("reembolso maior que a parte do terceiro (r > t): a compra não migra, vira exceção no relatório e não bloqueia as demais")
    void reembolsoMaiorQueAParte() throws Exception {
        String odd = legacyPurchase("100.00", 1, "2026-10-05");
        mark(odd, ana, "20.00");
        refund(odd, "50.00");
        String fine = legacyPurchase("60.00", 1, "2026-10-06");
        mark(fine, beto, null);

        JsonNode report = dryRun();

        assertThat(purchaseOf(report, odd).get("status").asString()).isEqualTo("EXCEPTION");
        assertThat(purchaseOf(report, odd).get("reason").asString()).isIn("REFUND_EXCEEDS_THIRD_PART", "RECEIVED_EXCEEDS_THIRD_PART");
        assertThat(purchaseOf(report, fine).get("status").asString()).isEqualTo("MIGRATE");
        assertThat(report.get("exceptions").asInt()).isEqualTo(1);
        long expenseOfOdd = bookOfTransaction(odd, "EXPENSE");

        assertThat(apply(report.get("hash").asString(), true).status()).isEqualTo(200);

        assertThat(bookOfTransaction(odd, "EXPENSE")).isEqualTo(expenseOfOdd);
        assertThat(count("third_party_expenses")).as("a marca da exceção permanece").isGreaterThanOrEqualTo(1L);
        assertThat(book("RECEIVABLE", beto)).isEqualTo(6000L);
        assertThat(book("RECEIVABLE", ana)).isZero();
    }

    @Test
    @DisplayName("apply: hash errado 409 SHARE_MIGRATION_REPORT_MISMATCH, sem backup 422 SHARE_MIGRATION_BACKUP_REQUIRED, dados mudaram depois do dry-run 409, nada elegível 422; nada é gravado nas recusas")
    void recusasDoApply() throws Exception {
        var nothing = apply(dryRun().get("hash").asString(), true);
        assertThat(nothing.status()).isEqualTo(422);
        assertThat(nothing.json(objectMapper).get("code").asString()).isEqualTo("SHARE_MIGRATION_NOTHING_TO_APPLY");
        String tx = legacyPurchase("100.00", 1, "2026-10-05");
        mark(tx, ana, null);
        JsonNode report = dryRun();
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);

        var wrongHash = apply("b".repeat(64), true);
        assertThat(wrongHash.status()).isEqualTo(409);
        assertThat(wrongHash.json(objectMapper).get("code").asString()).isEqualTo("SHARE_MIGRATION_REPORT_MISMATCH");
        var noBackup = apply(report.get("hash").asString(), false);
        assertThat(noBackup.status()).isEqualTo(422);
        assertThat(noBackup.json(objectMapper).get("code").asString()).isEqualTo("SHARE_MIGRATION_BACKUP_REQUIRED");
        mockMvc.perform(post("/v1/third-party-expenses/share-migration/apply", "{\"reportHash\":\"xyz\",\"backupConfirmed\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/third-party-expenses/share-migration/apply").contentType("application/json")
                .content("{}")).andExpect(status().isUnauthorized());

        String second = legacyPurchase("10.00", 1, "2026-10-06");
        mark(second, beto, null);
        var stale = apply(report.get("hash").asString(), true);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId) - postings).isEqualTo(2L);
        assertThat(book("RECEIVABLE")).isZero();
    }

    @Test
    @DisplayName("repetir o apply com o mesmo hash devolve alreadyApplied=true sem gravar de novo; depois de aplicado o dry-run não acha mais nada elegível")
    void idempotencia() throws Exception {
        String tx = legacyPurchase("100.00", 1, "2026-10-05");
        mark(tx, ana, null);
        refund(tx, "30.00");
        JsonNode report = dryRun();
        assertThat(apply(report.get("hash").asString(), true).status()).isEqualTo(200);
        long postings = jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId);
        long receivable = book("RECEIVABLE");

        var again = apply(report.get("hash").asString(), true);

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json(objectMapper).get("alreadyApplied").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE user_id = ?", Long.class, userId)).isEqualTo(postings);
        assertThat(book("RECEIVABLE")).isEqualTo(receivable);
        assertThat(dryRun().get("eligible").asInt()).isZero();
    }

    @Test
    @DisplayName("compra sem marca não muda; o dry-run e o apply são por usuário (outro usuário não vê nem aplica as marcas alheias)")
    void isolamento() throws Exception {
        String tx = legacyPurchase("100.00", 1, "2026-10-05");
        mark(tx, ana, null);
        legacyPurchase("33.00", 1, "2026-10-06");
        JsonNode mine = dryRun();
        String other = otherUserBearer();
        JsonNode theirs = body(mockMvc.perform(MockMvcRequestBuilders.post("/v1/third-party-expenses/share-migration/dry-run")
                .header(HttpHeaders.AUTHORIZATION, other)).andExpect(status().isOk()).andReturn());
        assertThat(theirs.get("eligible").asInt()).isZero();
        var attempt = mockMvc.perform(postAs(other, "/v1/third-party-expenses/share-migration/apply",
                "{\"reportHash\":\"" + mine.get("hash").asString() + "\",\"backupConfirmed\":true}")).andReturn().getResponse().getStatus();
        assertThat(attempt).isIn(409, 422);
        assertThat(book("RECEIVABLE")).isZero();
        assertThat(count("transactions")).isEqualTo(2L);
    }

    @Test
    @DisplayName("compra marcada sem reembolso: o consumo GROSS do mês é o mesmo antes e depois da migração e o MINE cai exatamente a parte do terceiro")
    void semReembolsoGrossIgual() throws Exception {
        String tx = legacyPurchase("100.00", 2, "2026-10-05");
        mark(tx, ana, "60.00");
        long grossBefore = cents(okBody(get("/v1/summaries/2026-10?view=GROSS")).get("expenses")) + cents(okBody(get("/v1/summaries/2026-11?view=GROSS")).get("expenses"));
        long mineBefore = cents(okBody(get("/v1/summaries/2026-10")).get("expenses")) + cents(okBody(get("/v1/summaries/2026-11")).get("expenses"));
        JsonNode report = dryRun();

        assertThat(apply(report.get("hash").asString(), true).status()).isEqualTo(200);

        long grossAfter = cents(okBody(get("/v1/summaries/2026-10?view=GROSS")).get("expenses")) + cents(okBody(get("/v1/summaries/2026-11?view=GROSS")).get("expenses"));
        long mineAfter = cents(okBody(get("/v1/summaries/2026-10")).get("expenses")) + cents(okBody(get("/v1/summaries/2026-11")).get("expenses"));
        assertThat(grossAfter).isEqualTo(grossBefore).isEqualTo(10000L);
        assertThat(mineBefore - mineAfter).isEqualTo(6000L);
        assertThat(book("RECEIVABLE", ana)).isEqualTo(6000L);
    }
}
