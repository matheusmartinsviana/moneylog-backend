package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;
import com.moneylog.util.ContractValidation;

@DisplayName("POST /v1/quick-entries (T6): borda, erros, contrato e integridade financeira")
class QuickEntryEndpointIT extends QuickEntryItBase {

    private static final String PATH = "/v1/quick-entries";
    private static final String VALIDATION_DETAIL = "Dados inválidos. Confira o Atalho e tente de novo.";

    private record Reply(int status, JsonNode body, MvcResult result) {
        String code() {
            return body.get("code").asString();
        }

        String detail() {
            return body.get("detail").asString();
        }
    }

    private MockHttpServletRequestBuilder request(World w, String key, String json) {
        QuickEntryTokenSecret secret = QuickEntryTokenSecret.generate();
        Instant now = clock.instant();
        committed(() -> tokens.insert(QuickEntryToken.issue(UUID.randomUUID(), w.userId(), w.accountId(), "Toque duplo", secret.prefix(),
                secret.hash(), now, now.plusSeconds(3600))));
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + secret.value())
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return key == null ? builder : builder.header("Idempotency-Key", key);
    }

    private Reply send(World w, String key, String json) throws Exception {
        MvcResult result = mockMvc.perform(request(w, key, json)).andReturn();
        String content = result.getResponse().getContentAsString();
        return new Reply(result.getResponse().getStatus(), content.isEmpty() ? null : objectMapper.readTree(content), result);
    }

    private static String key() {
        return "chave" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String body(String kind, String amountLiteral) {
        return "{\"kind\":\"" + kind + "\",\"amount\":" + amountLiteral + "}";
    }

    @Test
    @DisplayName("token e corpo válidos: 201 + Location, no-store, corpo conforme o contrato, message pronta e sem saldo nem listas")
    void deveCriarEDevolverORecursoSemSaldo() throws Exception {
        World w = world();
        QuickEntryTokenSecret secret = QuickEntryTokenSecret.generate();
        Instant now = clock.instant();
        UUID tokenId = UUID.randomUUID();
        committed(() -> tokens.insert(QuickEntryToken.issue(tokenId, w.userId(), w.accountId(), "iPhone", secret.prefix(), secret.hash(), now,
                now.plusSeconds(3600))));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + secret.value())
                .header("Idempotency-Key", "20261021123045").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"EXPENSE\",\"amount\":\"35,50\",\"category\":\"Alimentação\",\"description\":\"Almoço\",\"date\":\"2026-10-21\"}"))
                .andExpect(ContractValidation.conforms()).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/transactions/" + json.get("id").asString());
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json.get("message").asString()).isEqualTo("Gasto de R$ 35,50 em Alimentação (Carteira) registrado.");
        assertThat(json.get("type").asString()).isEqualTo("EXPENSE");
        assertThat(json.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(json.get("amount").get("amount").asString()).isEqualTo("35.50");
        assertThat(json.get("amount").get("currency").asString()).isEqualTo("BRL");
        assertThat(json.get("date").asString()).isEqualTo("2026-10-21");
        assertThat(json.get("description").asString()).isEqualTo("Almoço");
        assertThat(json.get("category").get("id").asString()).isEqualTo(w.food().toString());
        assertThat(json.get("account").get("id").asString()).isEqualTo(w.accountId().toString());
        assertThat(json.get("method").asString()).isEqualTo("OTHER");
        assertThat(json.get("source").asString()).isEqualTo("QUICK_SHORTCUT");
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("id", "type", "status", "amount", "date", "description", "category",
                "account", "method", "source", "message");
        assertThat(jdbc.queryForObject("select token_id from quick_entry_audit where user_id = ?", UUID.class, w.userId()))
                .isNotNull();
        assertThat(jdbc.queryForObject("select ip from quick_entry_audit where user_id = ?", String.class, w.userId())).isEqualTo("127.0.0.0");
    }

    @Test
    @DisplayName("reenvio com a mesma chave: 201 com o mesmo id e message, uma só movimentação; chave UUID e opaca passam")
    void deveRepetirOMesmoRecurso() throws Exception {
        World w = world();
        String key = key();
        String json = "{\"kind\":\"EXPENSE\",\"amount\":\"35,50\",\"category\":\"FOOD\"}";

        Reply first = send(w, key, json);
        Reply second = send(w, key, json);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        assertThat(second.body().get("message").asString()).isEqualTo(first.body().get("message").asString());
        assertThat(count("transactions", w.userId())).isEqualTo(1);
        assertThat(count("quick_entry_audit", w.userId())).isEqualTo(1);
        assertThat(send(w, UUID.randomUUID().toString(), body("EXPENSE", "\"1\"")).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("mesma chave com corpo diferente: 409 IDEMPOTENCY_KEY_REUSED em português; 27.6 e 27.60 são corpos diferentes (texto cru)")
    void deveRecusarChaveReutilizada() throws Exception {
        World w = world();
        String key = key();

        assertThat(send(w, key, body("EXPENSE", "27.6")).status()).isEqualTo(201);
        Reply reused = send(w, key, body("EXPENSE", "27.60"));

        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(reused.detail()).isEqualTo("Este envio repetiu uma chave com outro valor. Rode o Atalho de novo.");
        assertThat(reused.result().getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(count("transactions", w.userId())).isEqualTo(1);
        assertThat(send(w, key, body("EXPENSE", "27.6")).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("PR 01: gasto de 100 (saldo 1000 vira 900, consumo +100), entrada de 2500 em setembro, estorno pelo fluxo normal e replay do estornado")
    void deveFecharAIntegridadeFinanceiraPorHttp() throws Exception {
        World w = world();
        BigDecimal netWorthBefore = netWorth(w);

        Reply expense = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"100\",\"category\":\"Alimentação\",\"method\":\"PIX\"}");
        assertThat(expense.status()).isEqualTo(201);
        assertThat(expense.body().get("method").asString()).isEqualTo("PIX");
        assertThat(balance(w)).isEqualByComparingTo("900.00");
        assertThat(netWorth(w).subtract(netWorthBefore)).isEqualByComparingTo("-100.00");
        String expenseId = expense.body().get("id").asString();
        JsonNode stored = get(w.bearer(), "/v1/transactions/" + expenseId);
        assertThat(new BigDecimal(stored.get("impact").get("cash").get("amount").asString())).isEqualByComparingTo("-100.00");
        assertThat(stored.get("impact").get("consumption")).hasSize(1);
        assertThat(new BigDecimal(stored.get("impact").get("consumption").get(0).get("amount").get("amount").asString())).isEqualByComparingTo("100.00");
        JsonNode entries = get(w.bearer(), "/v1/entries?competence=2026-10");
        assertThat(new BigDecimal(entries.get("totals").get("total").get("amount").asString())).isEqualByComparingTo("100.00");
        assertThat(entries.get("totals").get("byCategory").get(0).get("category").get("name").asString()).isEqualTo("Alimentação");
        JsonNode listed = get(w.bearer(), "/v1/transactions?source=QUICK_SHORTCUT");
        assertThat(new BigDecimal(listed.get("totals").get("cashFlow").get("out").get("amount").asString())).isEqualByComparingTo("100.00");

        Reply income = send(w, key(), "{\"kind\":\"INCOME\",\"amount\":\"2500,00\",\"category\":\"Salário\",\"date\":\"2026-09-30\"}");
        assertThat(income.status()).isEqualTo(201);
        assertThat(income.body().get("message").asString()).isEqualTo("Entrada de R$ 2.500,00 em Salário (Carteira) registrada.");
        assertThat(balance(w)).isEqualByComparingTo("3400.00");
        assertThat(netWorth(w).subtract(netWorthBefore)).isEqualByComparingTo("2400.00");
        JsonNode incomeStored = get(w.bearer(), "/v1/transactions/" + income.body().get("id").asString());
        assertThat(incomeStored.get("date").asString()).isEqualTo("2026-09-30");
        assertThat(new BigDecimal(get(w.bearer(), "/v1/summaries/2026-09").get("income").get("amount").asString())).isEqualByComparingTo("2500.00");
        assertThat(new BigDecimal(get(w.bearer(), "/v1/summaries/2026-10").get("income").get("amount").asString())).isEqualByComparingTo("0.00");
        assertThat(incomeStored.get("impact").get("consumption")).isEmpty();
        JsonNode octoberEntries = get(w.bearer(), "/v1/entries?competence=2026-10");
        assertThat(new BigDecimal(octoberEntries.get("totals").get("total").get("amount").asString())).isEqualByComparingTo("100.00");

        post(w.bearer(), "/v1/transactions/" + expenseId + "/void", "{\"reason\":\"engano\"}", null, 200);
        assertThat(balance(w)).isEqualByComparingTo("3500.00");
        assertThat(netWorth(w).subtract(netWorthBefore)).isEqualByComparingTo("2500.00");
        JsonNode afterVoid = get(w.bearer(), "/v1/entries?competence=2026-10");
        assertThat(new BigDecimal(afterVoid.get("totals").get("total").get("amount").asString())).isEqualByComparingTo("0.00");
        assertThat(count("transactions", w.userId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from ledger_postings where user_id = ?", Long.class, w.userId())).isGreaterThan(2);

        Reply replay = send(w, "replay-estorno", "{\"kind\":\"EXPENSE\",\"amount\":\"10\"}");
        post(w.bearer(), "/v1/transactions/" + replay.body().get("id").asString() + "/void", "{\"reason\":\"engano\"}", null, 200);
        Reply again = send(w, "replay-estorno", "{\"kind\":\"EXPENSE\",\"amount\":\"10\"}");
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.body().get("id").asString()).isEqualTo(replay.body().get("id").asString());
        assertThat(again.body().get("status").asString()).isEqualTo("VOIDED");
        assertThat(again.body().get("message").asString()).contains("estornado");
        assertThat(balance(w)).isEqualByComparingTo("3500.00");
    }

    @Test
    @DisplayName("o token não estorna, não lista e não lê lançamentos: 401 nas outras rotas")
    void deveManterOTokenSoNoLancamento() throws Exception {
        World w = world();
        String id = send(w, key(), body("EXPENSE", "\"5\"")).body().get("id").asString();
        String bearer = request(w, null, "{}").buildRequest(new org.springframework.mock.web.MockServletContext())
                .getHeader(HttpHeaders.AUTHORIZATION);

        for (MockHttpServletRequestBuilder builder : List.of(
                MockMvcRequestBuilders.post("/v1/transactions/" + id + "/void").content("{\"reason\":\"x\"}").contentType(MediaType.APPLICATION_JSON),
                MockMvcRequestBuilders.get("/v1/transactions/" + id), MockMvcRequestBuilders.get("/v1/accounts"))) {
            assertThat(mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, bearer)).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(balance(w)).isEqualByComparingTo("995.00");
    }

    @Test
    @DisplayName("tabela do parser por HTTP (texto): aceitos viram o valor, ambíguos e inválidos viram 422 com code e detail em português")
    void deveAplicarATabelaDoParserComTexto() throws Exception {
        World w = world();
        List<String> failures = new ArrayList<>();
        String[][] accepted = {{"35", "35.00"}, {"35,5", "35.50"}, {"35,50", "35.50"}, {"1,5", "1.50"}, {"1.234,56", "1234.56"},
                {"1.234.567,89", "1234567.89"}, {"R$ 35,00", "35.00"}, {"35.50", "35.50"}, {"35.5", "35.50"}};
        for (String[] row : accepted) {
            Reply reply = send(w, key(), body("EXPENSE", "\"" + row[0] + "\""));
            if (reply.status() != 201 || !row[1].equals(reply.body().get("amount").get("amount").asString())) {
                failures.add(row[0] + " -> " + reply.status());
            }
        }
        for (String ambiguous : List.of("1.234", "35.500", "1.234.567")) {
            expect422(w, "\"" + ambiguous + "\"", "QUICK_AMOUNT_AMBIGUOUS", "Use vírgula nos centavos, por exemplo 1.234,00.", failures);
        }
        for (String invalid : List.of("10,005", "1,234,56", "12.34,56", "0", "0,00", "-5", "   ", "abc", "1.000.000.000,00")) {
            expect422(w, "\"" + invalid + "\"", "QUICK_AMOUNT_INVALID", "Não entendi o valor. Digite algo como 35,50.", failures);
        }
        assertThat(failures).isEmpty();
        assertThat(count("transactions", w.userId())).isEqualTo(accepted.length);
    }

    @Test
    @DisplayName("número JSON lido pelo texto cru: 27.6 e 27.60 valem; 27.605, 0, -5, 1e3, 2.5e-1, 10e-1, 2.5E1 e 27.6e0 são 422 QUICK_AMOUNT_INVALID")
    void deveLerONumeroJsonPeloTextoCru() throws Exception {
        World w = world();
        List<String> failures = new ArrayList<>();
        for (String[] row : new String[][] {{"27.6", "27.60"}, {"27.60", "27.60"}, {"35", "35.00"}, {"0.01", "0.01"}, {"27.600", "27.60"}}) {
            Reply reply = send(w, key(), body("EXPENSE", row[0]));
            if (reply.status() != 201 || !row[1].equals(reply.body().get("amount").get("amount").asString())) {
                failures.add(row[0] + " -> " + reply.status());
            }
        }
        for (String invalid : List.of("27.605", "0", "0.0", "-5", "1e3", "1E+3", "2.5E1", "2.5e-1", "10e-1", "27.6e0", "27.600000000000001")) {
            expect422(w, invalid, "QUICK_AMOUNT_INVALID", "Não entendi o valor. Digite algo como 35,50.", failures);
        }
        assertThat(failures).isEmpty();
        assertThat(count("transactions", w.userId())).isEqualTo(5);
        assertThat(idempotencyRows(w.userId())).isEqualTo(5);
    }

    private void expect422(World w, String amountLiteral, String code, String detail, List<String> failures) throws Exception {
        Reply reply = send(w, key(), body("EXPENSE", amountLiteral));
        if (reply.status() != 422 || !code.equals(reply.code()) || !detail.equals(reply.detail())) {
            failures.add(amountLiteral + " -> " + reply.status() + " " + (reply.body() == null ? "" : reply.body()));
        }
    }

    @Test
    @DisplayName("400 VALIDATION_FAILED na borda, com detail em português e nada gravado")
    void deveRecusarCorposInvalidosComBadRequest() throws Exception {
        World w = world();
        List<String> bodies = new ArrayList<>(List.of(
                body("TRANSFER", "\"1\""), body("INVESTMENT", "\"1\""), body("LOAN_GIVEN", "\"1\""), body("CARD_PURCHASE", "\"1\""),
                body("expense", "\"1\""), body("X", "\"1\""), "{\"kind\":1,\"amount\":\"1\"}", "{\"kind\":null,\"amount\":\"1\"}",
                "{\"amount\":\"1\"}", "{\"kind\":\"EXPENSE\"}", "{\"kind\":\"EXPENSE\",\"amount\":null}",
                body("EXPENSE", "true"), body("EXPENSE", "{}"), body("EXPENSE", "[]"), body("EXPENSE", "[1]"), body("EXPENSE", "\"\""),
                body("EXPENSE", "\"" + "1".repeat(41) + "\""), body("EXPENSE", "1".repeat(41)),
                "{\"kind\":\"INCOME\",\"amount\":\"1\",\"method\":\"PIX\"}", "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"method\":\"CREDIT\"}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"method\":\"pix\"}", "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"method\":1}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"account\":\"" + w.accountId() + "\"}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"accountId\":\"" + w.accountId() + "\"}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"userId\":\"" + w.userId() + "\"}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"tokenId\":\"" + w.tokenId() + "\"}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"extra\":1}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"date\":\"2026-1-1\"}", "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"date\":\"21/10/2026\"}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"date\":\"2026-02-30\"}", "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"date\":20261021}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"date\":\"2026-10-21T10:00:00Z\"}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"description\":\"" + "a".repeat(201) + "\"}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"description\":\"a\\u0000b\"}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"description\":\"a\\nb\"}", "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"description\":5}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"category\":\"\"}", "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"category\":\"" + "c".repeat(61) + "\"}",
                "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"category\":\"a\\u0007b\"}", "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"category\":7}",
                "{", "[]", "\"x\"", "null", "", "{\"kind\":\"EXPENSE\",\"amount\":\"1\"}x"));
        List<String> failures = new ArrayList<>();
        for (String json : bodies) {
            Reply reply = send(w, key(), json);
            if (reply.status() != 400 || !"VALIDATION_FAILED".equals(reply.code()) || !VALIDATION_DETAIL.equals(reply.detail())
                    || !reply.result().getResponse().getContentType().startsWith("application/problem+json")) {
                failures.add(json + " -> " + reply.status() + " " + reply.body());
            }
        }
        assertThat(failures).isEmpty();
        assertThat(count("transactions", w.userId())).isZero();
        assertThat(idempotencyRows(w.userId())).isZero();
        assertThat(count("quick_entry_audit", w.userId())).isZero();
    }

    @Test
    @DisplayName("a recusa não repete texto livre: nome de campo fora do padrão seguro vira body; categoria e descrição digitadas não voltam")
    void naoDeveRepetirTextoLivre() throws Exception {
        World w = world();

        Reply unknown = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"segredoDoUsuario!\":1}");
        Reply category = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"category\":\"segredo-categoria\"}");
        Reply description = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"description\":\"segredo\\u0000\"}");

        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.body().toString()).doesNotContain("segredo");
        assertThat(category.status()).isEqualTo(422);
        assertThat(category.code()).isEqualTo("QUICK_CATEGORY_NOT_FOUND");
        assertThat(category.detail()).isEqualTo("Não achei a categoria enviada. Escolha outra ou deixe em branco.");
        assertThat(category.body().toString()).doesNotContain("segredo");
        assertThat(description.body().toString()).doesNotContain("segredo");
    }

    @Test
    @DisplayName("campo desconhecido devolve o erro UNKNOWN_FIELD com o nome do campo, como no contrato")
    void deveNomearOCampoDesconhecidoSeSeguro() throws Exception {
        World w = world();

        Reply reply = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"account\":\"" + w.accountId() + "\"}");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body().get("errors").get(0).get("field").asString()).isEqualTo("account");
        assertThat(reply.body().get("errors").get(0).get("code").asString()).isEqualTo("UNKNOWN_FIELD");
        assertThat(reply.body().get("errors").get(0).get("message").asString()).isEqualTo("Campo desconhecido.");
        assertThat(reply.body().toString()).doesNotContain(w.accountId().toString());
    }

    @Test
    @DisplayName("Idempotency-Key ausente, curta, longa ou que sobra curta/longa depois de limpar: 400; UUID e texto limpável de 8 a 64 caracteres passam")
    void deveValidarAChaveDeIdempotencia() throws Exception {
        World w = world();
        String valid = body("EXPENSE", "\"1\"");

        for (String bad : new String[] {null, "", "curta", "a".repeat(65), "a b c d e f g", "12/34/56 7", "a".repeat(201)}) {
            Reply reply = send(w, bad, valid);
            assertThat(reply.status()).as("chave %s", bad).isEqualTo(400);
            assertThat(reply.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(reply.detail()).isEqualTo(VALIDATION_DETAIL);
        }
        assertThat(count("transactions", w.userId())).isZero();
        for (String good : new String[] {UUID.randomUUID().toString(), "a".repeat(8), "B".repeat(64), "20261021123045", "abc_DEF-123", "tem espaco aqui", "com.ponto.aqui", "acentuação1", "12345678;"}) {
            assertThat(send(w, good, valid).status()).as("chave %s", good).isEqualTo(201);
        }
        assertThat(count("transactions", w.userId())).isEqualTo(9);
    }

    @Test
    @DisplayName("chave suja do iOS é normalizada: cria, replay com a mesma chave suja e com a limpa não duplica, e a mensagem de erro não ecoa o valor")
    void deveTolerarAChaveSuja() throws Exception {
        World w = world();
        String json = body("EXPENSE", "\"7\"");

        Reply first = send(w, "21/10/2026 17:11", json);
        Reply replay = send(w, "21/10/2026\u00A017:11\u200F", json);
        Reply clean = send(w, "211020261711", json);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(clean.status()).isEqualTo(201);
        assertThat(replay.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        assertThat(clean.body().get("id").asString()).isEqualTo(first.body().get("id").asString());
        assertThat(count("transactions", w.userId())).isEqualTo(1);

        Reply tooShort = send(w, "12/34 56", json);
        assertThat(tooShort.status()).isEqualTo(400);
        assertThat(tooShort.body().toString()).contains("chegaram 8 caracteres, 2 inválidos descartados; sobraram 6 e o mínimo é 8")
                .doesNotContain("12/34 56").doesNotContain("123456");
    }

    @Test
    @DisplayName("mesma chave no mesmo minuto com corpo diferente: 409 IDEMPOTENCY_KEY_REUSED, o lançamento novo não é engolido como replay")
    void deveConflitarMesmaChaveComCorpoDiferente() throws Exception {
        World w = world();

        Reply first = send(w, "211020261711", body("EXPENSE", "\"7\""));
        Reply second = send(w, "21/10/2026 17:11", body("EXPENSE", "\"9\""));

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(count("transactions", w.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("userId, conta e token vêm do token: o lançamento cai na conta do token do usuário do token, nunca em outra")
    void deveUsarSoOQueVemDoToken() throws Exception {
        World mine = world();
        World other = world();

        Reply reply = send(mine, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"10\",\"category\":\"Alimentação\"}");

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.body().get("account").get("id").asString()).isEqualTo(mine.accountId().toString());
        assertThat(reply.body().get("category").get("id").asString()).isEqualTo(mine.food().toString());
        assertThat(count("transactions", mine.userId())).isEqualTo(1);
        assertThat(count("transactions", other.userId())).isZero();
        assertThat(balance(other)).isEqualByComparingTo("1000.00");
        assertThat(send(other, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"20\",\"category\":\"Alimentação\"}").body().get("category").get("id").asString())
                .isEqualTo(other.food().toString());
    }

    @Test
    @DisplayName("422 de regra de negócio com code e detail em português: categoria, data, categoria padrão ausente e conta do token arquivada")
    void deveMapearOs422DeNegocio() throws Exception {
        World w = world();

        Reply category = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"category\":\"Inexistente\"}");
        Reply future = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"date\":\"2026-10-22\"}");
        Reply old = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"date\":\"2025-10-19\"}");
        Reply edge = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"date\":\"2025-10-20\"}");
        Reply wrongKind = send(w, key(), "{\"kind\":\"INCOME\",\"amount\":\"1\",\"category\":\"Alimentação\"}");

        assertThat(category.status()).isEqualTo(422);
        assertThat(category.code()).isEqualTo("QUICK_CATEGORY_NOT_FOUND");
        assertThat(future.code()).isEqualTo("QUICK_DATE_OUT_OF_RANGE");
        assertThat(future.detail()).isEqualTo("A data não pode ser futura nem anterior a 366 dias.");
        assertThat(old.status()).isEqualTo(422);
        assertThat(old.code()).isEqualTo("QUICK_DATE_OUT_OF_RANGE");
        assertThat(edge.status()).isEqualTo(201);
        assertThat(wrongKind.code()).isEqualTo("QUICK_CATEGORY_NOT_FOUND");

        committed(() -> jdbc.update("update categories set archived = true where id = ?", w.otherExpense()));
        Reply noDefault = send(w, key(), body("EXPENSE", "\"1\""));
        assertThat(noDefault.status()).isEqualTo(422);
        assertThat(noDefault.code()).isEqualTo("DEFAULT_CATEGORY_UNAVAILABLE");
        assertThat(noDefault.detail()).isEqualTo("A categoria padrão não está disponível. Informe uma categoria no Atalho.");
        assertThat(send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"category\":\"Lazer\"}").status()).isEqualTo(201);

        committed(() -> jdbc.update("update accounts set status = 'ARCHIVED' where id = ?", w.accountId()));
        Reply archived = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"category\":\"Lazer\"}");
        assertThat(archived.status()).isEqualTo(422);
        assertThat(archived.code()).isEqualTo("QUICK_ACCOUNT_NOT_ALLOWED");
        assertThat(archived.detail()).isEqualTo("A conta deste token não está mais ativa. Gere um novo token em Ajustes, Atalhos.");
        assertThat(count("transactions", w.userId())).isEqualTo(2);
    }

    @Test
    @DisplayName("sem teto configurado o endpoint nunca devolve QUICK_LIMIT_REACHED: valor até o limite técnico e muitos lançamentos passam")
    void naoDeveLimitarPorPadrao() throws Exception {
        World w = world();

        assertThat(send(w, key(), body("EXPENSE", "\"999.999.999,99\"")).status()).isEqualTo(201);
        for (int i = 0; i < 25; i++) {
            assertThat(send(w, key(), body("EXPENSE", "\"9.000,00\"")).status()).isEqualTo(201);
        }
        assertThat(count("transactions", w.userId())).isEqualTo(26);
    }

    @Test
    @DisplayName("respostas de erro não trazem stack, classe interna nem SQL")
    void naoDeveVazarDadoInterno() throws Exception {
        World w = world();
        List<Reply> replies = List.of(send(w, key(), "{"), send(w, key(), body("EXPENSE", "\"abc\"")), send(w, key(), body("TRANSFER", "\"1\"")),
                send(w, null, body("EXPENSE", "\"1\"")));

        for (Reply reply : replies) {
            String text = reply.body().toString();
            assertThat(text).doesNotContain("Exception", "com.moneylog", "tools.jackson", "SELECT", "select ", "at java");
            assertThat(Set.of(400, 422)).contains(reply.status());
        }
    }

    private static final List<String> JWT_URLS = List.of("/v1/quick-entries", "/v1/quick%2Dentries", "/v1/quick%2dentries", "/v1/quick-entries/",
            "/v1/quick-entries;x=1", "/v1/quick%2Dentries/", "/v1/%71uick-entries", "/v1/quick-entr%69es");

    private MvcResult sendJwt(World w, String url) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(java.net.URI.create(url)).header(HttpHeaders.AUTHORIZATION, w.bearer())
                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body("EXPENSE", "\"10\""))).andReturn();
    }

    @Test
    @DisplayName("JWT de sessão em /v1/quick-entries e em todas as grafias (%2D, %71, barra, ;): nunca 2xx/5xx (401, 403 ou 404), nada é gravado")
    void naoDeveGravarComJwtDeSessao() throws Exception {
        World w = world();
        List<String> wrong = new ArrayList<>();
        for (String url : JWT_URLS) {
            int status = sendJwt(w, url).getResponse().getStatus();
            if (status != 401 && status != 400 && status != 403 && status != 404) {
                wrong.add(url + " -> " + status);
            }
        }
        assertThat(wrong).isEmpty();
        assertThat(count("transactions", w.userId())).isZero();
        assertThat(balance(w)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("JWT de sessão nunca chega ao controller real do lançamento rápido, em nenhuma grafia da rota")
    void naoDeveAlcancarOControllerComJwtDeSessao() throws Exception {
        World w = world();
        List<String> reached = new ArrayList<>();
        for (String url : JWT_URLS) {
            MvcResult result = sendJwt(w, url);
            if (result.getHandler() != null && result.getHandler().toString().contains("QuickEntryController")) {
                reached.add(url + " -> " + result.getResponse().getStatus());
            }
        }
        assertThat(reached).isEmpty();
    }

    @Test
    @DisplayName("nome de campo desconhecido dentro do padrão seguro é ecoado em errors[].field (como no contrato); o valor nunca")
    void deveEcoarSoONomeSeguroDoCampo() throws Exception {
        World w = world();

        Reply reply = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"campoExtra\":\"valor-secreto\"}");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.body().get("errors").get(0).get("field").asString()).isEqualTo("campoExtra");
        assertThat(reply.body().toString()).doesNotContain("valor-secreto");
    }

    @Test
    @DisplayName("erros 400 e 422 também saem com Cache-Control no-store")
    void deveMarcarOsErrosComNoStore() throws Exception {
        World w = world();

        Reply invalid = send(w, key(), "{");
        Reply rule = send(w, key(), body("EXPENSE", "\"abc\""));

        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.result().getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(rule.status()).isEqualTo(422);
        assertThat(rule.result().getResponse().getHeader("Cache-Control")).contains("no-store");
    }

    @Test
    @DisplayName("limites de entrada: número com mais de 1000 dígitos é 400, corpo acima de 1 MiB é 413 PAYLOAD_TOO_LARGE e Content-Type de texto é 415; nada gravado")
    void deveLimitarOTamanhoDaEntrada() throws Exception {
        World w = world();

        Reply hugeNumber = send(w, key(), body("EXPENSE", "9".repeat(1100)));
        assertThat(hugeNumber.status()).isEqualTo(400);
        assertThat(hugeNumber.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(hugeNumber.detail()).isEqualTo(VALIDATION_DETAIL);

        Reply hugeBody = send(w, key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\",\"description\":\"" + "a".repeat(1_100_000) + "\"}");
        assertThat(hugeBody.status()).isEqualTo(413);
        assertThat(hugeBody.code()).isEqualTo("PAYLOAD_TOO_LARGE");

        MvcResult text = mockMvc.perform(request(w, key(), body("EXPENSE", "\"1\"")).contentType(MediaType.TEXT_PLAIN)).andReturn();
        assertThat(text.getResponse().getStatus()).isEqualTo(415);

        assertThat(count("transactions", w.userId())).isZero();
        assertThat(idempotencyRows(w.userId())).isZero();
    }
}
