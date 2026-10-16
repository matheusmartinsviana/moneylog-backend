package com.moneylog.infra.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("Exportação CSV e backup: formato, injeção de fórmula, segredos, isolamento, coerência e volume (T97)")
class ExportsIT extends E4ItBase {

    @Value("${moneylog.security.jwt-secret}")
    private String jwtSecret;

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private String expense(String amount, String date, String description) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description.replace("\"", "\\\"")
                + "\",\"accountId\":\"" + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}";
    }

    private JsonNode scenario() throws Exception {
        String tech = category("Tecnologia", CategoryKind.EXPENSE);
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1200.00\",\"date\":\"2026-10-10\",\"description\":\"Notebook\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + tech + "\",\"installments\":6}");
        created(expense("100.00", "2026-10-10", "Restaurante"));
        created(expense("1234.50", "2026-10-11", "Aluguel"));
        created(expense("5.00", "2026-10-12", "=HYPERLINK(\"http://x\")"));
        created(expense("6.00", "2026-10-12", "+55 11 9999"));
        created(expense("7.00", "2026-10-12", "-1+1"));
        created(expense("8.00", "2026-10-12", "@SOMA(A1)"));
        created(expense("9.00", "2026-10-12", "Pizza; com \"aspas\""));
        created(expense("9.99", "2026-09-30", "Setembro"));
        JsonNode wrong = created(expense("30.00", "2026-10-13", "Lançada por engano"));
        okBody(post("/v1/transactions/" + wrong.get("id").asString() + "/void", "{\"reason\":\"duplicada\"}"));
        return wrong;
    }

    private MvcResult csv(String query, String language) throws Exception {
        var request = get("/v1/exports/transactions.csv" + query);
        if (language != null) {
            request.header(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn();
    }

    private static String text(MvcResult result) {
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith(BOM);
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("CA1: filtro de outubro: UTF-8 com BOM, ';', valor 1234,50, datas ISO, cabeçalho em português, uma linha por movimentação (a compra 6x aparece uma vez com 1.200,00) e a estornada fora")
    void deveExportarOCsvDoFiltro() throws Exception {
        scenario();

        MvcResult result = csv("?from=2026-10-01&to=2026-10-31", null);
        String text = text(result);
        List<String> lines = List.of(text.split("\r\n"));

        assertThat(result.getResponse().getContentType()).startsWith("text/csv").containsIgnoringCase("utf-8");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"moneylog-transactions-2026-10-01_2026-10-31.csv\"");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(lines.getFirst()).isEqualTo("Data;Tipo;Descrição;Categoria;Conta/Cartão;Método;Valor;Status;Direção;Competência da renda");
        assertThat(lines).contains("2026-10-10;Compra no cartão;Notebook;Tecnologia;Roxinho;;1200,00;Ativa;;");
        assertThat(lines).contains("2026-10-11;Despesa;Aluguel;Alimentação;Nubank;Pix;1234,50;Ativa;;");
        assertThat(lines).contains("2026-10-10;Despesa;Restaurante;Alimentação;Nubank;Pix;100,00;Ativa;;");
        assertThat(lines.stream().filter(l -> l.contains(";Notebook;"))).hasSize(1);
        assertThat(text).doesNotContain("Lançada por engano").doesNotContain("Setembro");
        assertThat(lines).hasSize(1 + 8);
    }

    @Test
    @DisplayName("CA2: texto que começa com =, +, - ou @ vem com ' na frente; descrição com ';' e aspas é escapada")
    void deveProtegerContraInjecaoDeFormula() throws Exception {
        scenario();

        String text = text(csv("?from=2026-10-01&to=2026-10-31", null));

        assertThat(text).contains(";\"'=HYPERLINK(\"\"http://x\"\")\";").contains(";'+55 11 9999;").contains(";'-1+1;").contains(";'@SOMA(A1);");
        assertThat(text).contains("\"Pizza; com \"\"aspas\"\"\"");
        assertThat(text).doesNotContain(";=HYPERLINK").doesNotContain(";+55 11").doesNotContain(";-1+1;").doesNotContain(";@SOMA");
        for (String line : text.split("\r\n")) {
            for (String cell : line.split(";")) {
                assertThat(cell).as("célula executável na linha: " + line).doesNotStartWith("=").doesNotStartWith("@");
            }
        }
    }

    @Test
    @DisplayName("Accept-Language en traz o cabeçalho em inglês; estornadas só com status=VOIDED ou includeVoided; filtros da lista valem")
    void deveTraduzirEFiltrar() throws Exception {
        scenario();

        assertThat(text(csv("?from=2026-10-01&to=2026-10-31", "en-US,en;q=0.9"))).startsWith("Date;Type;Description;Category;Account/Card;Method;Amount;Status;Direction;Income month\r\n");
        assertThat(text(csv("?from=2026-10-01&to=2026-10-31", "fr"))).startsWith("Data;Tipo;");
        assertThat(text(csv("?status=VOIDED", null))).contains("Lançada por engano").contains(";Estornada;").doesNotContain("Restaurante");
        String both = text(csv("?includeVoided=true&from=2026-10-01&to=2026-10-31", null));
        assertThat(both).contains("Lançada por engano").contains("Restaurante");
        assertThat(text(csv("?q=aluguel", null))).contains("Aluguel").doesNotContain("Restaurante");
        assertThat(text(csv("?type=CARD_PURCHASE", null))).contains("Notebook").doesNotContain("Aluguel");
        assertThat(text(csv("?accountId=" + nubank + "&method=PIX&categoryId=" + food, null))).contains("Aluguel").doesNotContain("Notebook");
        assertThat(text(csv("?from=2030-01-01", null))).isEqualTo("Data;Tipo;Descrição;Categoria;Conta/Cartão;Método;Valor;Status;Direção;Competência da renda\r\n");
    }

    @Test
    @DisplayName("filtro inválido é 400 (from depois de to, data fora da janela, tipo desconhecido); sem token é 401; só os dados do usuário do token")
    void deveValidarEIsolar() throws Exception {
        scenario();

        mockMvc.perform(get("/v1/exports/transactions.csv?from=2026-10-31&to=2026-10-01")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/exports/transactions.csv?from=1900-01-01")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/exports/transactions.csv?type=XYZ")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/exports/transactions.csv")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/exports/backup")).andExpect(status().isUnauthorized());
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "export-" + other + "@example.com"));
        MvcResult theirs = mockMvc.perform(getAs(auth.bearer(other), "/v1/exports/transactions.csv")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn();
        assertThat(text(theirs)).isEqualTo("Data;Tipo;Descrição;Categoria;Conta/Cartão;Método;Valor;Status;Direção;Competência da renda\r\n");
        JsonNode theirBackup = body(mockMvc.perform(getAs(auth.bearer(other), "/v1/exports/backup")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(theirBackup.get("accounts")).isEmpty();
        assertThat(theirBackup.get("transactions")).isEmpty();
        assertThat(theirBackup.get("ledgerPostings")).isEmpty();
    }

    @Test
    @DisplayName("M2 (S-261): INVESTMENT traz aplicação/resgate e ADJUSTMENT crédito/débito na coluna Direção (Direction em inglês); os demais tipos deixam a coluna vazia; tipo, método e status traduzidos")
    void deveTrazerADirecao() throws Exception {
        String invest = createdBody(post("/v1/accounts", "{\"name\":\"XP Invest\",\"type\":\"INVESTMENT\",\"openingBalance\":{\"amount\":\"1000.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        for (String direction : List.of("CONTRIBUTION", "REDEMPTION")) {
            created("{\"type\":\"INVESTMENT\",\"amount\":\"500.00\",\"date\":\"2026-10-10\",\"description\":\"CDB " + direction + "\",\"cashAccountId\":\"" + nubank
                    + "\",\"investmentAccountId\":\"" + invest + "\",\"direction\":\"" + direction + "\"}");
        }
        for (String direction : List.of("INCREASE", "DECREASE")) {
            created("{\"type\":\"ADJUSTMENT\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"Ajuste " + direction + "\",\"accountId\":\"" + nubank
                    + "\",\"direction\":\"" + direction + "\",\"kind\":\"CORRECTION\",\"reason\":\"conferido com o extrato\"}");
        }
        created(expense("1.00", "2026-10-10", "Normal"));

        String pt = text(csv("?from=2026-10-10&to=2026-10-10", null));
        assertThat(pt).contains("Investimento;CDB CONTRIBUTION;;Nubank > XP Invest;;500,00;Ativa;Aplicação;\r\n")
                .contains("Investimento;CDB REDEMPTION;;Nubank > XP Invest;;500,00;Ativa;Resgate;\r\n")
                .contains("Ajuste;Ajuste INCREASE;;Nubank;;10,00;Ativa;Crédito;\r\n").contains("Ajuste;Ajuste DECREASE;;Nubank;;10,00;Ativa;Débito;\r\n")
                .contains("Despesa;Normal;Alimentação;Nubank;Pix;1,00;Ativa;;\r\n");
        String en = text(csv("?from=2026-10-10&to=2026-10-10", "en"));
        assertThat(en).contains("Investment;CDB CONTRIBUTION;;Nubank > XP Invest;;500,00;Active;Contribution;\r\n")
                .contains(";Active;Redemption;\r\n").contains("Adjustment;Ajuste INCREASE;;Nubank;;10,00;Active;Credit;\r\n").contains(";Active;Debit;\r\n")
                .contains("Expense;Normal;Alimentação;Nubank;Pix;1,00;Active;;\r\n");
    }

    @Test
    @DisplayName("L3 (S-261): o status explícito vale mais que includeVoided (status=ACTIVE&includeVoided=true devolve só as ativas; status=VOIDED, só as estornadas); includeVoided sozinho traz as duas")
    void statusExplicitoNaoEhSobrescrito() throws Exception {
        scenario();

        String active = text(csv("?status=ACTIVE&includeVoided=true&from=2026-10-01&to=2026-10-31", null));
        assertThat(active).contains("Restaurante").doesNotContain("Lançada por engano");
        String voided = text(csv("?status=VOIDED&includeVoided=true&from=2026-10-01&to=2026-10-31", null));
        assertThat(voided).contains("Lançada por engano").doesNotContain("Restaurante");
        String both = text(csv("?includeVoided=true&from=2026-10-01&to=2026-10-31", null));
        assertThat(both).contains("Lançada por engano").contains("Restaurante");
    }

    @Test
    @DisplayName("BUG-QA-31 (S-261): q com caractere de controle ou acima de 100 caracteres é 400 no CSV, na lista de movimentações e na de pessoas (@Validated nos controllers)")
    void qComControleEhRecusado() throws Exception {
        List<String> notRejected = new ArrayList<>();
        for (String url : List.of("/v1/exports/transactions.csv?q=a%00b", "/v1/exports/transactions.csv?q=a%0Ab", "/v1/transactions?q=a%00b", "/v1/transactions?q=a%0Ab",
                "/v1/people?q=a%00b", "/v1/exports/transactions.csv?q=" + "x".repeat(101), "/v1/transactions?q=" + "x".repeat(101), "/v1/people?q=" + "x".repeat(101))) {
            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(java.net.URI.create(url)).header(HttpHeaders.AUTHORIZATION, bearer);
            int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
            if (status != 400) {
                notRejected.add(url.substring(0, Math.min(url.length(), 50)) + " -> " + status);
            } else {
                mockMvc.perform(request).andExpect(ContractValidation.responseOnly());
            }
        }
        assertThat(notRejected).as("q inválido tem de ser 400").isEmpty();
        mockMvc.perform(get("/v1/transactions?q=" + "x".repeat(100))).andExpect(status().isOk());
        mockMvc.perform(get("/v1/people?q=" + "x".repeat(100))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("BUG-QA-29 (S-261): o nome do backup usa o dia de America/Sao_Paulo: às 23:30 de 10/10 (02:30Z de 11/10) é 2026-10-10")
    void nomeDoBackupNoFusoDoUsuario() throws Exception {
        advanceTo(Instant.parse("2026-10-11T02:30:00Z"));

        assertThat(mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"moneylog-backup-2026-10-10.json\"");
    }

    @Test
    @DisplayName("CA3: o backup tem schemaVersion, generatedAt e todas as seções (contas, categorias, cartões, movimentações com parcelas, dívidas, pessoas, recorrências, metas e reservas, razão)")
    void deveGerarOBackupCompleto() throws Exception {
        scenario();
        String debtPerson = joao;
        loan("300.00", "2026-10-10", debtPerson, "2026-11-05");
        createdBody(post("/v1/recurrences", "{\"type\":\"EXPENSE\",\"amount\":\"119.00\",\"description\":\"Internet\",\"categoryId\":\"" + bills
                + "\",\"accountId\":\"" + nubank + "\",\"method\":\"PIX\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-11-10\"}"));
        String goal = createdBody(post("/v1/goals", "{\"name\":\"Reserva\",\"target\":\"20000.00\",\"priority\":\"HIGH\"}")).get("id").asString();
        okBody(post("/v1/goals/" + goal + "/reservations", "{\"accountId\":\"" + nubank + "\",\"amount\":\"8500.00\",\"date\":\"2026-10-10\",\"note\":\"aporte\"}")
                .header("Idempotency-Key", UUID.randomUUID().toString()));

        MvcResult result = mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn();
        JsonNode backup = body(result);

        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("attachment; filename=\"moneylog-backup-2026-10-10.json\"");
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(backup.get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(backup.get("generatedAt").asString()).startsWith("2026-10-10");
        assertThat(backup.get("accounts")).hasSize(1);
        assertThat(backup.get("accounts").get(0).get("name").asString()).isEqualTo("Nubank");
        assertThat(backup.get("categories").size()).isGreaterThanOrEqualTo(5);
        assertThat(backup.get("cards")).hasSize(1);
        assertThat(backup.get("cards").get(0).get("billingRules")).isNotEmpty();
        assertThat(backup.get("people")).hasSize(2);
        assertThat(backup.get("debts")).hasSize(1);
        assertThat(backup.get("recurrences")).hasSize(1);
        assertThat(backup.get("goals")).hasSize(1);
        assertThat(backup.get("goals").get(0).get("reservations")).hasSize(1);
        assertThat(money(backup.get("goals").get(0).get("reservations").get(0), "amount")).isEqualTo("8500.00");
        JsonNode notebook = null;
        for (JsonNode tx : backup.get("transactions")) {
            if ("Notebook".equals(tx.get("description").asString())) {
                notebook = tx;
            }
        }
        assertThat(notebook).isNotNull();
        assertThat(notebook.get("installments")).hasSize(6);
        assertThat(backup.get("transactions").size()).as("inclui a estornada").isEqualTo(11);
        assertThat(backup.get("ledgerPostings")).isNotEmpty();
        assertThat(count("goal_reservations")).isEqualTo(1);
    }

    @Test
    @DisplayName("CA3: o backup não contém hash de senha, refresh token, JWT_SECRET nem e-mail (varre as chaves e o texto)")
    void naoDeveVazarSegredos() throws Exception {
        scenario();
        String raw = mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn()
                .getResponse().getContentAsString();
        JsonNode backup = objectMapper.readTree(raw);

        Set<String> keys = new HashSet<>();
        collectKeys(backup, keys);
        List<String> forbidden = List.of("password", "passwordhash", "hash", "token", "refresh", "secret", "jwt", "email", "idempotency", "apikey", "api_key", "cookie");
        for (String key : keys) {
            for (String word : forbidden) {
                assertThat(key.toLowerCase(Locale.ROOT)).as("chave proibida no backup: " + key).doesNotContain(word);
            }
        }
        assertThat(raw).doesNotContain(jwtSecret).doesNotContain("$2a$").doesNotContain("$2b$").doesNotContain("@example.com");
        assertThat(keys).contains("schemaVersion", "generatedAt", "ledgerPostings");
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            node.propertyNames().forEach(name -> {
                keys.add(name);
                collectKeys(node.get(name), keys);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectKeys(child, keys));
        }
    }

    @Test
    @DisplayName("CA4: outro usuário nunca aparece no backup: nenhum dado dele, nem no razão")
    void naoDeveMisturarUsuarios() throws Exception {
        scenario();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-backup-" + other + "@example.com"));
        String otherBearer = auth.bearer(other);
        mockMvc.perform(postAs(otherBearer, "/v1/accounts", "{\"name\":\"Conta Secreta\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"777.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andExpect(status().isCreated());

        String mine = mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String theirs = mockMvc.perform(getAs(otherBearer, "/v1/exports/backup")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(mine).doesNotContain("Conta Secreta").doesNotContain("777.00");
        assertThat(theirs).contains("Conta Secreta").doesNotContain("Nubank").doesNotContain("Notebook");
        assertThat(objectMapper.readTree(theirs).get("transactions")).isEmpty();
    }

    @Test
    @DisplayName("CA6: a soma dos lançamentos ativos do livro ACCOUNT recalculada a partir do backup fecha com o saldo de cada conta na API")
    void deveFecharOSaldoPeloBackup() throws Exception {
        scenario();
        String invest = createdBody(post("/v1/accounts", "{\"name\":\"XP Invest\",\"type\":\"INVESTMENT\",\"openingBalance\":{\"amount\":\"0.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        created("{\"type\":\"INVESTMENT\",\"amount\":\"500.00\",\"date\":\"2026-10-10\",\"description\":\"CDB\",\"cashAccountId\":\"" + nubank
                + "\",\"investmentAccountId\":\"" + invest + "\",\"direction\":\"CONTRIBUTION\"}");
        JsonNode backup = okBody(get("/v1/exports/backup"));
        LocalDate today = LocalDate.of(2026, 10, 10);

        for (JsonNode account : backup.get("accounts")) {
            String id = account.get("id").asString();
            long cents = 0;
            for (JsonNode posting : backup.get("ledgerPostings")) {
                if ("ACCOUNT".equals(posting.get("bookType").asString()) && id.equals(posting.get("bookId").asString()) && posting.get("active").asBoolean()
                        && !LocalDate.parse(posting.get("effectiveDate").asString()).isAfter(today)) {
                    cents += new java.math.BigDecimal(money(posting, "amount")).movePointRight(2).longValueExact();
                }
            }
            String fromApi = okBody(get("/v1/accounts/" + id)).get("currentBalance").get("amount").asString();
            assertThat(java.math.BigDecimal.valueOf(cents, 2).toPlainString()).as("saldo de " + account.get("name").asString()).isEqualTo(fromApi);
        }
        long total = 0;
        for (JsonNode posting : backup.get("ledgerPostings")) {
            if (posting.get("active").asBoolean()) {
                total += new java.math.BigDecimal(money(posting, "amount")).movePointRight(2).longValueExact();
            }
        }
        assertThat(total).as("o razão do backup fecha em zero").isZero();
    }

    private void seedBulk(int count) {
        UUID nubankId = UUID.fromString(nubank);
        UUID foodId = UUID.fromString(food);
        Timestamp now = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));
        LocalDate first = LocalDate.of(2020, 1, 1);
        for (int offset = 0; offset < count; offset += 1000) {
            List<Object[]> transactions = new ArrayList<>();
            List<Object[]> postings = new ArrayList<>();
            for (int i = offset; i < Math.min(count, offset + 1000); i++) {
                UUID id = UUID.randomUUID();
                LocalDate date = first.plusDays(i % 2500);
                Date sqlDate = Date.valueOf(date);
                long cents = 100 + (i % 9000);
                transactions.add(new Object[] {id, userId, cents, sqlDate, "Despesa " + i + " ; com \"aspas\"", nubankId, foodId, now, now});
                String competence = date.toString().substring(0, 7);
                postings.add(new Object[] {UUID.randomUUID(), userId, id, id, "EXPENSE", foodId, competence, sqlDate, cents, now, now});
                postings.add(new Object[] {UUID.randomUUID(), userId, id, id, "ACCOUNT", nubankId, competence, sqlDate, -cents, now, now});
            }
            committed(() -> {
                jdbc.batchUpdate("""
                        INSERT INTO transactions (id, user_id, type, status, amount_cents, currency, transaction_date, description, payment_method,
                            account_id, category_id, source, created_at, updated_at, version)
                        VALUES (?, ?, 'EXPENSE', 'ACTIVE', ?, 'BRL', ?, ?, 'PIX', ?, ?, 'MANUAL', ?, ?, 0)""", transactions);
                jdbc.batchUpdate("""
                        INSERT INTO ledger_postings (id, user_id, source_type, source_id, transaction_id, book_type, book_id, competence, effective_date,
                            amount_cents, currency, active, created_at, updated_at)
                        VALUES (?, ?, 'TRANSACTION', ?, ?, ?, ?, ?, ?, ?, 'BRL', true, ?, ?)""", postings);
            });
        }
    }

    @Test
    @DisplayName("CA5: com 10.000 movimentações o CSV e o backup respondem em tempo razoável e sem estourar a memória; o CSV tem uma linha por movimentação")
    void deveExportarDezMilMovimentacoes() throws Exception {
        seedBulk(10_000);
        long usedBefore = usedHeapAfterGc();
        long csvStart = System.nanoTime();
        MvcResult csvResult = mockMvc.perform(get("/v1/exports/transactions.csv")).andExpect(status().isOk()).andReturn();
        long csvMillis = (System.nanoTime() - csvStart) / 1_000_000;
        byte[] csvBytes = csvResult.getResponse().getContentAsByteArray();
        long backupStart = System.nanoTime();
        MvcResult backupResult = mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isOk()).andReturn();
        long backupMillis = (System.nanoTime() - backupStart) / 1_000_000;
        long usedWithFiles = usedHeapAfterGc();

        String text = new String(csvBytes, 3, csvBytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text.split("\r\n")).hasSize(10_001);
        JsonNode backup = objectMapper.readTree(backupResult.getResponse().getContentAsString());
        assertThat(backup.get("transactions")).hasSize(10_000);
        assertThat(backup.get("ledgerPostings").size()).isGreaterThanOrEqualTo(20_000);
        System.out.printf("[T97 volume] 10.000 movimentações: CSV %d ms (%d kB), backup %d ms (%d kB), heap vivo %d MB -> %d MB (com os arquivos)%n", csvMillis,
                csvBytes.length / 1024, backupMillis, backupResult.getResponse().getContentAsByteArray().length / 1024, usedBefore >> 20, usedWithFiles >> 20);
        assertThat(csvMillis).as("limite documentado: 15 s para o CSV de 10.000 movimentações").isLessThan(15_000);
        assertThat(backupMillis).as("limite documentado: 30 s para o backup de 10.000 movimentações").isLessThan(30_000);
        assertThat(usedWithFiles - usedBefore).as("heap vivo depois do GC: só os arquivos, sem vazamento").isLessThan(150L << 20);
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long usedHeapAfterGc() throws InterruptedException {
        long last = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(100);
            long used = usedHeap();
            if (used >= last) {
                return used;
            }
            last = used;
        }
        return last;
    }
}
