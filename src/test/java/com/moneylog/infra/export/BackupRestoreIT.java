package com.moneylog.infra.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.SqlFixtures;

@DisplayName("Backup v2 e restauração: ida e volta com dados sintéticos, versão 1, isolamento e validação")
class BackupRestoreIT extends E4ItBase {

    private static final List<String> NEW_TOP_LEVEL = List.of("purchaseGroups", "assets", "incomeProfiles", "incomeOccurrences",
            "payrollLoanPlans", "payrollLoanApplications", "commitments", "thirdPartyExpenses", "thirdPartyReceipts");

    private String sharedPurchaseId;
    private String fxPurchaseId;
    private String loanDebtId;

    @BeforeEach
    void categoriasDeSistema() {
        committed(() -> {
            jdbc.update("UPDATE categories SET system = TRUE, system_key = 'FOOD' WHERE id = ?", UUID.fromString(food));
            jdbc.update("UPDATE categories SET system = TRUE, system_key = 'SUBSCRIPTIONS' WHERE id = ?", UUID.fromString(subscriptions));
            jdbc.update("UPDATE categories SET system = TRUE, system_key = 'BILLS' WHERE id = ?", UUID.fromString(bills));
            jdbc.update("UPDATE categories SET system = TRUE, system_key = 'SALARY' WHERE id = ?", UUID.fromString(salary));
        });
    }

    private JsonNode looseTx(String json) throws Exception {
        return body(createTx(json).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode loose(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return body(mockMvc.perform(request).andExpect(status().isCreated()).andReturn());
    }

    private String sharedPurchase() {
        return "{\"type\":\"CARD_PURCHASE\",\"amount\":\"300.00\",\"date\":\"2026-10-05\",\"description\":\"Compra rateada\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + food + "\",\"installments\":3,\"splitMode\":\"BPS\",\"shares\":[{\"personId\":null,\"bps\":5000},"
                + "{\"personId\":\"" + joao + "\",\"bps\":5000}]}";
    }

    private void richScenario() throws Exception {
        sharedPurchaseId = looseTx(sharedPurchase()).get("id").asString();
        fxPurchaseId = looseTx("{\"type\":\"CARD_PURCHASE\",\"amount\":\"103.50\",\"date\":\"2026-10-06\",\"description\":\"Assinatura no exterior\","
                + "\"cardId\":\"" + card + "\",\"categoryId\":\"" + subscriptions + "\",\"installments\":1,\"originalAmount\":{\"amount\":\"20.00\","
                + "\"currency\":\"USD\"},\"fxRate\":\"5.000000\",\"iofBps\":350}").get("id").asString();
        looseTx("{\"type\":\"EXPENSE\",\"amount\":\"150.00\",\"date\":\"2026-10-07\",\"description\":\"Mercado\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\",\"nature\":\"VARIABLE\"}");
        looseTx("{\"type\":\"EXPENSE\",\"amount\":\"1800.00\",\"date\":\"2026-10-08\",\"description\":\"Aluguel\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + bills + "\",\"method\":\"PIX\",\"nature\":\"FIXED\"}");
        String engano = looseTx("{\"type\":\"EXPENSE\",\"amount\":\"30.00\",\"date\":\"2026-10-09\",\"description\":\"Lançada por engano\","
                + "\"accountId\":\"" + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}").get("id").asString();
        okBody(post("/v1/transactions/" + engano + "/void", voidBody("duplicada")));

        String parcelada = looseTx("{\"type\":\"CARD_PURCHASE\",\"amount\":\"400.00\",\"date\":\"2026-10-04\",\"description\":\"Parcelada\","
                + "\"cardId\":\"" + card + "\",\"categoryId\":\"" + food + "\",\"installments\":4}").get("id").asString();
        mockMvc.perform(post("/v1/transactions/" + parcelada + "/cancel-installments", "{\"reason\":\"cancelei o restante\"}"))
                .andExpect(status().isOk());

        JsonNode loan = loan("500.00", "2026-10-02", joao, "2026-12-01");
        loanDebtId = loan.get("debt").get("id").asString();
        created(received("200.00", "2026-10-09", loanDebtId));
        loose(post("/v1/debts", "{\"direction\":\"PAYABLE\",\"principal\":\"900.00\",\"date\":\"2026-10-03\",\"expectedDate\":\"2027-01-10\","
                + "\"creditorName\":\"Banco Exemplo\",\"description\":\"Financiamento\"}"));

        loose(post("/v1/recurrences", "{\"type\":\"EXPENSE\",\"amount\":\"39.90\",\"description\":\"Streaming\",\"categoryId\":\"" + subscriptions
                + "\",\"accountId\":\"" + nubank + "\",\"method\":\"PIX\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-10-15\","
                + "\"nature\":\"FIXED\",\"isSubscription\":true,\"scope\":\"COMPANY\",\"channel\":\"app\",\"overlapGroup\":\"video\","
                + "\"lastUsedOn\":\"2026-10-01\",\"provenance\":{\"origin\":\"PRINT\",\"confidence\":\"ESTIMATED\"}}"));
        String salaryRecurrence = loose(post("/v1/recurrences", "{\"type\":\"INCOME\",\"amount\":\"5000.00\",\"description\":\"Salário\","
                + "\"categoryId\":\"" + salary + "\",\"accountId\":\"" + nubank + "\",\"method\":\"PIX\",\"frequency\":\"MONTHLY\","
                + "\"startDate\":\"2026-10-20\",\"incomeCompetenceOffsetMonths\":1}")).get("id").asString();
        mockMvc.perform(post("/v1/recurrences/" + salaryRecurrence + "/occurrences/2026-10-20/skip", "{}")).andExpect(status().isOk());

        String goal = loose(post("/v1/goals", "{\"name\":\"Reserva\",\"target\":\"10000.00\",\"deadline\":\"2027-12-01\",\"priority\":\"HIGH\","
                + "\"description\":\"Seis meses\"}")).get("id").asString();
        mockMvc.perform(post("/v1/goals/" + goal + "/reservations", "{\"accountId\":\"" + nubank + "\",\"amount\":\"250.00\",\"date\":\"2026-10-09\"}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());

        loose(post("/v1/assets", "{\"kind\":\"FGTS\",\"name\":\"FGTS Empresa\",\"employer\":\"Empresa Exemplo\",\"balance\":\"12000.00\","
                + "\"balanceDate\":\"2026-10-01\",\"monthlyDeposit\":\"400.00\",\"availability\":\"RESERVED\","
                + "\"provenance\":{\"origin\":\"PAYSLIP\",\"confidence\":\"CONFIRMED\"}}"));
        String profile = loose(post("/v1/income-profiles", "{\"source\":\"Empresa Exemplo\",\"gross\":\"8000.00\",\"dependents\":1,"
                + "\"advance\":{\"dayOfMonth\":15,\"percentBps\":4000},\"balanceDayOfMonth\":5,\"deductions\":[{\"kind\":\"BENEFIT\","
                + "\"label\":\"Vale refeição\",\"amount\":\"300.00\"},{\"kind\":\"UNION\",\"label\":\"Sindicato\",\"percentBps\":100}],"
                + "\"thirteenth\":{\"enabled\":true,\"firstInstallmentMonth\":11}}")).get("id").asString();
        loose(post("/v1/commitments", "{\"kind\":\"LONG_TERM_PROJECT\",\"name\":\"Apartamento\",\"counterparty\":\"Construtora Exemplo\","
                + "\"total\":\"3000.00\",\"schedule\":[{\"label\":\"Entrada\",\"type\":\"DOWN_PAYMENT\",\"dueDate\":\"2026-11-10\",\"amount\":\"1000.00\","
                + "\"paid\":false,\"shares\":[{\"personId\":null,\"amount\":\"600.00\"},{\"personId\":\"" + maria + "\",\"amount\":\"400.00\"}],"
                + "\"coBuyerPersonId\":\"" + maria + "\"},{\"label\":\"Parcela 1\",\"type\":\"INSTALLMENT\",\"dueDate\":\"2026-12-10\","
                + "\"amount\":\"2000.00\",\"paid\":false}],\"clauses\":{\"lateFeeBps\":200,\"monthlyInterestBps\":100,"
                + "\"terminationRetentionBps\":1000,\"notes\":\"Cláusulas de teste\"}}"));
        String groupPurchase = "{\"cardId\":\"" + card + "\",\"totalInstallments\":4,\"installmentAmount\":\"100.00\",\"firstInvoiceDate\":\"2026-10-12\","
                + "\"categoryId\":\"" + food + "\"}";
        loose(post("/v1/purchase-groups", "{\"name\":\"Compra agrupada\",\"interest\":false,\"members\":[" + groupPurchase + ",{\"cardId\":\""
                + card + "\",\"totalInstallments\":2,\"installmentAmount\":\"50.00\",\"firstInvoiceDate\":\"2026-10-12\",\"categoryId\":\"" + food + "\"}]}"));
        mockMvc.perform(post("/v1/third-party-expenses", "{\"transactionId\":\"" + fxPurchaseId + "\",\"personId\":\"" + maria
                + "\",\"note\":\"Dividida\",\"amount\":\"20.00\"}")).andExpect(status().isCreated());
        mockMvc.perform(post("/v1/third-party-receipts", "{\"personId\":\"" + joao + "\",\"transactionId\":\"" + sharedPurchaseId
                + "\",\"amount\":\"50.00\",\"receivedOn\":\"2026-10-09\",\"accountId\":\"" + nubank + "\"}")).andExpect(status().isCreated());

        String lastDigits = "1234";
        committed(() -> {
            jdbc.update("UPDATE credit_cards SET last_digits = ?, auto_debit = TRUE, brand = 'Visa', best_purchase_day = 26 WHERE user_id = ?",
                    lastDigits, userId);
            jdbc.update("UPDATE transactions SET last_digits = ? WHERE id = ?", lastDigits, UUID.fromString(sharedPurchaseId));
            UUID loanRecurrence = UUID.fromString(salaryRecurrence);
            jdbc.update("INSERT INTO income_profile_occurrences (id, user_id, profile_id, competence, kind, recurrence_id, created_at) "
                    + "VALUES (?, ?, ?, '2026-10', 'BALANCE', ?, ?)", UUID.randomUUID(), userId, UUID.fromString(profile), loanRecurrence,
                    Timestamp.from(Instant.parse("2026-10-10T15:00:00Z")));
            jdbc.update("INSERT INTO income_occurrence_loans (id, user_id, recurrence_id, debt_id, applied_cents, interest_cents, created_at) "
                    + "VALUES (?, ?, ?, ?, 30000, 5000, ?)", UUID.randomUUID(), userId, loanRecurrence, UUID.fromString(loanDebtId),
                    Timestamp.from(Instant.parse("2026-10-10T15:00:00Z")));
            jdbc.update("INSERT INTO payroll_loan_applications (transaction_id, user_id, debt_id, applied_cents, interest_cents, principal_cents, "
                    + "created_at) VALUES (?, ?, ?, 30000, 5000, 25000, ?)", UUID.fromString(fxPurchaseId), userId, UUID.fromString(loanDebtId),
                    Timestamp.from(Instant.parse("2026-10-10T15:00:00Z")));
        });
    }

    private void wipe(UUID user) {
        committed(() -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.columns WHERE table_schema = current_schema() "
                    + "AND column_name = 'user_id'", String.class);
            for (String table : tables) {
                if (!"users".equals(table)) {
                    jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", user);
                }
            }
        });
    }

    private JsonNode exportBackup() throws Exception {
        return okBody(get("/v1/exports/backup"));
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(canonical(item)));
            items.sort(Comparator.comparing(JsonNode::toString));
            ArrayNode sorted = objectMapper.createArrayNode();
            items.forEach(sorted::add);
            return sorted;
        }
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            node.properties().forEach(entry -> copy.set(entry.getKey(), canonical(entry.getValue())));
            return copy;
        }
        return node;
    }

    private static ObjectNode copyOf(JsonNode node) {
        return (ObjectNode) node.deepCopy();
    }

    private JsonNode withoutVolatile(JsonNode backup, JsonNode reference) {
        ObjectNode copy = copyOf(backup);
        copy.remove("generatedAt");
        java.util.Set<String> original = new java.util.HashSet<>();
        reference.get("categories").forEach(c -> original.add(c.get("id").asString()));
        ArrayNode kept = objectMapper.createArrayNode();
        copy.get("categories").forEach(c -> {
            if (original.contains(c.get("id").asString())) {
                kept.add(c);
            }
        });
        copy.set("categories", kept);
        return canonical(copy);
    }

    private ObjectNode asVersionOne(JsonNode backup) {
        ObjectNode v1 = copyOf(backup);
        v1.put("schemaVersion", 1);
        NEW_TOP_LEVEL.forEach(v1::remove);
        java.util.Map<String, List<String>> newFields = java.util.Map.of(
                "categories", List.of("systemKey"),
                "cards", List.of("closingDayConfirmed", "autoDebit", "brand", "bestPurchaseDay", "lastDigits"),
                "transactions", List.of("nature", "shares", "originalAmount", "fxRate", "iofBps", "lastDigits"),
                "debts", List.of("kind", "channel"),
                "recurrences", List.of("nature", "shares", "originalAmount", "referenceFxRate", "iofBps", "isSubscription", "scope", "channel",
                        "overlapGroup", "lastUsedOn", "provenance"),
                "goals", List.of("kind", "metric", "direction", "baseline", "baselineAt", "planNote", "featured", "actions"));
        newFields.forEach((list, fields) -> v1.withArray(list).forEach(item -> fields.forEach(((ObjectNode) item)::remove)));
        return v1;
    }

    private JsonNode restore(JsonNode body, int expectedStatus) throws Exception {
        return body(mockMvc.perform(post("/v1/backups/restore", body.toString())).andExpect(status().is(expectedStatus)).andReturn());
    }

    @Test
    @DisplayName("ida e volta: exporta, apaga tudo da conta, restaura e o novo backup é idêntico ao antigo (rateio, câmbio, assinatura, procedência, ativos, renda, compromissos, grupos, terceiros)")
    void deveRestaurarOBackupDeIdaEVolta() throws Exception {
        richScenario();
        JsonNode before = exportBackup();
        String balanceBefore = accountBalance();
        String summaryBefore = summary().toString();
        JsonNode debtsOverviewBefore = okBody(get("/v1/debts/overview"));

        assertThat(before.get("schemaVersion").asInt()).isEqualTo(2);
        JsonNode purchase = transactionOf(before, sharedPurchaseId);
        assertThat(purchase.get("shares").get("mode").asString()).isEqualTo("BPS");
        assertThat(purchase.get("shares").get("lines")).hasSize(2);
        assertThat(purchase.get("lastDigits").asString()).isEqualTo("1234");
        JsonNode fx = transactionOf(before, fxPurchaseId);
        assertThat(fx.get("originalAmount").get("amount").asString()).isEqualTo("20.00");
        assertThat(fx.get("fxRate").asString()).isEqualTo("5.000000");
        assertThat(fx.get("iofBps").asInt()).isEqualTo(350);
        assertThat(before.get("recurrences").get(0).has("isSubscription")).isTrue();
        assertThat(canceledInstallments(before)).isPositive();
        assertThat(before.get("transactions").toString()).contains("\"status\":\"VOIDED\"");
        assertThat(before.get("cards").get(0).get("autoDebit").asBoolean()).isTrue();
        for (String list : NEW_TOP_LEVEL) {
            assertThat(before.get(list)).as(list).isNotEmpty();
        }

        wipe(userId);
        assertThat(count("transactions")).isZero();

        JsonNode result = restore(before, 200);
        assertThat(result.get("counts").get("transactions").asInt()).isEqualTo(before.get("transactions").size());

        JsonNode after = exportBackup();
        assertThat(withoutVolatile(after, before)).isEqualTo(withoutVolatile(before, before));
        assertThat(after.get("categories")).hasSizeGreaterThan(before.get("categories").size());
        assertThat(accountBalance()).isEqualTo(balanceBefore);
        assertThat(summary().toString()).isEqualTo(summaryBefore);
        assertThat(okBody(get("/v1/debts/overview"))).isEqualTo(debtsOverviewBefore);
        assertThat(ledgerBalanceByBook()).isEqualTo(ledgerBalanceByBookOf(before));
        mockMvc.perform(post("/v1/backups/restore", before.toString())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTORE_TARGET_NOT_EMPTY"));
    }

    @Test
    @DisplayName("duas restaurações simultâneas do mesmo usuário com arquivos de ids diferentes: uma grava, a outra recebe 409 e a conta nunca fica híbrida")
    void deveSerializarRestauracoesSimultaneas() throws Exception {
        richScenario();
        JsonNode full = exportBackup();
        ObjectNode minimal = copyOf(full);
        minimal.properties().forEach(entry -> {
            if (entry.getValue().isArray() && !"categories".equals(entry.getKey())) {
                minimal.set(entry.getKey(), objectMapper.createArrayNode());
            }
        });
        ObjectNode account = copyOf(full.get("accounts").get(0));
        account.put("id", UUID.randomUUID().toString());
        minimal.withArray("accounts").add(account);
        long accountsInFull = full.get("accounts").size();
        wipe(userId);

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Integer>> futures = new ArrayList<>();
            for (JsonNode backup : List.of(full, minimal)) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/v1/backups/restore", backup.toString())).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (java.util.concurrent.Future<Integer> future : futures) {
                statuses.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        UUID minimalAccount = UUID.fromString(account.get("id").asString());
        boolean minimalPresent = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE id = ?", Long.class, minimalAccount) == 1L;
        if (count("transactions") == 0) {
            assertThat(minimalPresent).isTrue();
            assertThat(count("accounts")).isEqualTo(1L);
        } else {
            assertThat(minimalPresent).isFalse();
            assertThat(count("accounts")).isEqualTo(accountsInFull);
        }
    }

    @Test
    @DisplayName("a restauração espera a trava da linha do usuário: com a linha travada por outra transação ela não termina, e termina com 200 quando a trava é liberada")
    void deveEsperarATravaDoUsuario() throws Exception {
        richScenario();
        JsonNode backup = exportBackup();
        wipe(userId);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<Integer> restoring;
        try {
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Integer>> started = new java.util.concurrent.atomic.AtomicReference<>();
            committed(() -> {
                jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR NO KEY UPDATE", userId);
                started.set(pool.submit(() -> mockMvc.perform(post("/v1/backups/restore", backup.toString())).andReturn().getResponse().getStatus()));
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                assertThat(started.get().isDone()).as("restauração bloqueada pela trava do usuário").isFalse();
                assertThat(count("accounts")).isZero();
            });
            restoring = started.get();
            assertThat(restoring.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("transactions")).isEqualTo(backup.get("transactions").size());
    }

    @Test
    @DisplayName("lê o formato v1: o que o v1 não tinha volta com o padrão e o razão e os saldos fecham com o arquivo")
    void deveLerOFormatoAntigo() throws Exception {
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1200.00\",\"date\":\"2026-10-05\",\"description\":\"Notebook\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + food + "\",\"installments\":6}");
        created("{\"type\":\"EXPENSE\",\"amount\":\"150.00\",\"date\":\"2026-10-07\",\"description\":\"Mercado\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}");
        JsonNode loan = loan("500.00", "2026-10-02", joao, "2026-12-01");
        created(received("200.00", "2026-10-09", loan.get("debt").get("id").asString()));
        JsonNode before = exportBackup();
        String balanceBefore = accountBalance();
        ObjectNode v1 = asVersionOne(before);
        wipe(userId);

        restore(v1, 200);

        JsonNode after = exportBackup();
        assertThat(after.get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(after.get("transactions")).hasSameSizeAs(before.get("transactions"));
        assertThat(accountBalance()).isEqualTo(balanceBefore);
        assertThat(ledgerBalanceByBook()).isEqualTo(ledgerBalanceByBookOf(before));
        assertThat(after.get("transactions").get(0).get("shares").isNull()).isTrue();
    }

    @Test
    @DisplayName("isolamento: conta com dados recusa (409, nada muda); outro usuário com ids que já existem recebe 409 genérico, sem vazar nada e sem gravar")
    void deveIsolarPorUsuario() throws Exception {
        richScenario();
        JsonNode backup = exportBackup();
        long transactionsBefore = count("transactions");

        mockMvc.perform(post("/v1/backups/restore", backup.toString())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTORE_TARGET_NOT_EMPTY"));
        assertThat(count("transactions")).isEqualTo(transactionsBefore);

        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@example.com"));
        String otherBearer = auth.bearer(other);
        var response = mockMvc.perform(postAs(otherBearer, "/v1/backups/restore", backup.toString())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTORE_CONFLICT")).andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(userId.toString()).doesNotContain(sharedPurchaseId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Long.class, other)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts WHERE user_id = ?", Long.class, other)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM categories WHERE user_id = ?", Long.class, other)).isZero();
        assertThat(count("transactions")).isEqualTo(transactionsBefore);
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/backups/restore").contentType("application/json").content(backup.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("validação: versão desconhecida, referência para algo fora do arquivo, razão em livro inexistente e razão que não fecha viram 422 e nada é gravado")
    void deveRecusarBackupInvalido() throws Exception {
        richScenario();
        JsonNode backup = exportBackup();
        wipe(userId);

        ObjectNode unknownVersion = copyOf(backup);
        unknownVersion.put("schemaVersion", 3);
        mockMvc.perform(post("/v1/backups/restore", unknownVersion.toString())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BACKUP_SCHEMA_UNSUPPORTED"));

        ObjectNode orphanReference = copyOf(backup);
        ((ObjectNode) orphanReference.withArray("transactions").get(0)).put("categoryId", UUID.randomUUID().toString());
        mockMvc.perform(post("/v1/backups/restore", orphanReference.toString())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BACKUP_INVALID"));

        ObjectNode unknownBook = copyOf(backup);
        for (JsonNode posting : unknownBook.withArray("ledgerPostings")) {
            if ("ACCOUNT".equals(posting.get("bookType").asString())) {
                ((ObjectNode) posting).put("bookId", UUID.randomUUID().toString());
            }
        }
        mockMvc.perform(post("/v1/backups/restore", unknownBook.toString())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BACKUP_INVALID"));

        ObjectNode unbalanced = copyOf(backup);
        for (JsonNode posting : unbalanced.withArray("ledgerPostings")) {
            if ("TRANSACTION".equals(posting.get("sourceType").asString()) && "EXPENSE".equals(posting.get("bookType").asString())) {
                ((ObjectNode) posting.get("amount")).put("amount", "999999.00");
                break;
            }
        }
        mockMvc.perform(post("/v1/backups/restore", unbalanced.toString())).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BACKUP_INVALID"));

        assertThat(count("transactions")).isZero();
        assertThat(count("accounts")).isZero();
        assertThat(count("categories")).isZero();
    }

    private long canceledInstallments(JsonNode backup) {
        long canceled = 0;
        for (JsonNode transaction : backup.get("transactions")) {
            for (JsonNode installment : transaction.get("installments")) {
                canceled += installment.get("canceled").asBoolean() ? 1 : 0;
            }
        }
        return canceled;
    }

    private JsonNode transactionOf(JsonNode backup, String id) {
        for (JsonNode transaction : backup.get("transactions")) {
            if (id.equals(transaction.get("id").asString())) {
                return transaction;
            }
        }
        throw new AssertionError("movimentação ausente no backup: " + id);
    }

    private java.util.Map<String, Long> ledgerBalanceByBook() {
        java.util.Map<String, Long> balances = new java.util.TreeMap<>();
        jdbc.query("SELECT book_type, COALESCE(book_id::text, '-') AS book, SUM(amount_cents) AS total FROM ledger_postings "
                + "WHERE user_id = ? AND active GROUP BY book_type, book_id HAVING SUM(amount_cents) <> 0", rs -> {
            balances.put(rs.getString(1) + ":" + rs.getString(2), rs.getLong(3));
        }, userId);
        return balances;
    }

    private java.util.Map<String, Long> ledgerBalanceByBookOf(JsonNode backup) {
        java.util.Map<String, Long> balances = new java.util.TreeMap<>();
        for (JsonNode posting : backup.get("ledgerPostings")) {
            if (!posting.get("active").asBoolean()) {
                continue;
            }
            String book = posting.get("bookId") == null || posting.get("bookId").isNull() ? "-" : posting.get("bookId").asString();
            long cents = new java.math.BigDecimal(posting.get("amount").get("amount").asString()).movePointRight(2).longValueExact();
            balances.merge(posting.get("bookType").asString() + ":" + book, cents, Long::sum);
        }
        balances.values().removeIf(total -> total == 0L);
        return balances;
    }
}
