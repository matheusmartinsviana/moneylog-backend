package com.moneylog.infra.income;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("Competência de renda: renda de outro mês, caixa intacto (ADR-017)")
class IncomeCompetenceIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MutableClock clock;

    private UUID userId;
    private String bearer;
    private String nubank;
    private String salary;
    private String food;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "renda-" + userId + "@example.com"));
        bearer = auth.bearer(userId);
        nubank = body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"Conta\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"10000.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-09-01\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
        salary = category("Salário", CategoryKind.INCOME);
        food = category("Mercado", CategoryKind.EXPENSE);
    }

    private void committed(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    private MockHttpServletRequestBuilder post(String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private MockHttpServletRequestBuilder put(String url, String json) {
        return MockMvcRequestBuilders.put(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode json(String url) throws Exception {
        return body(mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private String category(String name, CategoryKind kind) {
        Category category = Category.create(UUID.randomUUID(), userId, name, kind);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        return category.id().toString();
    }

    private static String amount(JsonNode money) {
        return money.get("amount").asString();
    }

    private String incomeJson(String amount, String date, String description, String competenceField) {
        return "{\"type\":\"INCOME\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description + "\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + salary + "\",\"method\":\"PIX\"" + competenceField + "}";
    }

    private JsonNode createIncome(String amount, String date, String description, String competence) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        String field = competence == null ? "" : ",\"incomeCompetence\":\"" + competence + "\"";
        return body(mockMvc.perform(post("/v1/transactions", incomeJson(amount, date, description, field)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private String salarioDividido() throws Exception {
        JsonNode adiantamento = createIncome("1500.00", "2026-09-16", "Adiantamento", "2026-10");
        createIncome("2500.00", "2026-10-02", "Folha", null);
        clock.advance(Duration.ofSeconds(1));
        mockMvc.perform(post("/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"300.00\",\"date\":\"2026-09-20\",\"description\":\"Mercado\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());
        return adiantamento.get("id").asString();
    }

    @Test
    @DisplayName("POST devolve incomeCompetence; renda de outubro = 4.000,00 no resumo, no dashboard e nas entries; setembro = 0,00")
    void deveSomarAsDuasParcelasEmOutubro() throws Exception {
        JsonNode created = createIncome("1500.00", "2026-09-16", "Adiantamento", "2026-10");
        createIncome("2500.00", "2026-10-02", "Folha", null);

        assertThat(created.get("incomeCompetence").asString()).isEqualTo("2026-10");
        assertThat(created.get("date").asString()).isEqualTo("2026-09-16");
        assertThat(amount(json("/v1/summaries/2026-10").get("income"))).isEqualTo("4000.00");
        assertThat(amount(json("/v1/summaries/2026-09").get("income"))).isEqualTo("0.00");
        JsonNode month = json("/v1/dashboard/summary").get("month");
        assertThat(amount(month.get("income"))).isEqualTo("4000.00");
        JsonNode entries = json("/v1/entries?competence=2026-10&lens=INCOME");
        assertThat(amount(entries.get("totals").get("total"))).isEqualTo("4000.00").isEqualTo(amount(month.get("income")));
        assertThat(entries.get("items")).hasSize(2);
        for (JsonNode item : entries.get("items")) {
            assertThat(item.get("competence").asString()).isEqualTo("2026-10");
        }
        assertThat(entries.get("items").findValuesAsString("date")).containsExactlyInAnyOrder("2026-09-16", "2026-10-02");
        assertThat(json("/v1/entries?competence=2026-09&lens=INCOME").get("items")).isEmpty();
        assertThat(amount(json("/v1/entries?competence=2026-09&lens=INCOME").get("totals").get("total"))).isEqualTo("0.00");
        assertThat(amount(json("/v1/summaries/2026-10").get("comparison").get("previousMonth").get("income"))).isEqualTo("0.00");
    }

    @Test
    @DisplayName("o saldo, o caixa e o consumo não mudam: 16/09 entra 1.500,00 no caixa de setembro e a conta fecha em 13.700,00")
    void deveManterCaixaESaldo() throws Exception {
        salarioDividido();

        assertThat(amount(json("/v1/dashboard/summary").get("balances").get("inAccounts"))).isEqualTo("13700.00");
        assertThat(amount(json("/v1/accounts/" + nubank).get("currentBalance"))).isEqualTo("13700.00");
        JsonNode setembro = json("/v1/transactions?from=2026-09-01&to=2026-09-30").get("totals").get("cashFlow");
        assertThat(amount(setembro.get("in"))).isEqualTo("1500.00");
        assertThat(amount(setembro.get("out"))).isEqualTo("300.00");
        JsonNode outubro = json("/v1/transactions?from=2026-10-01&to=2026-10-31").get("totals").get("cashFlow");
        assertThat(amount(outubro.get("in"))).isEqualTo("2500.00");
        assertThat(amount(json("/v1/summaries/2026-09").get("expenses"))).isEqualTo("300.00");
        assertThat(amount(json("/v1/summaries/2026-10").get("expenses"))).isEqualTo("0.00");
        assertThat(amount(json("/v1/summaries/2026-10").get("net"))).isEqualTo("4000.00");
    }

    @Test
    @DisplayName("relatório entradas e saídas: M12 (por competência) mostra 4.000,00 em outubro; D30 (por data) mostra 1.500,00 em 16/09; a soma é a mesma")
    void deveSepararMesDeDiaNoRelatorio() throws Exception {
        salarioDividido();

        JsonNode meses = json("/v1/reports/income-expenses?period=M12");
        JsonNode dias = json("/v1/reports/income-expenses?period=D30");

        assertThat(meses.get("basis").asString()).isEqualTo("COMPETENCE");
        assertThat(dias.get("basis").asString()).isEqualTo("DATE");
        assertThat(amount(point(meses, "2026-10-01").get("income"))).isEqualTo("4000.00");
        assertThat(amount(point(meses, "2026-09-01").get("income"))).isEqualTo("0.00");
        assertThat(amount(point(dias, "2026-09-16").get("income"))).isEqualTo("1500.00");
        assertThat(amount(point(dias, "2026-10-02").get("income"))).isEqualTo("2500.00");
        assertThat(amount(meses.get("totals").get("income"))).isEqualTo(amount(dias.get("totals").get("income"))).isEqualTo("4000.00");
        assertThat(amount(point(meses, "2026-10-01").get("income"))).isEqualTo(amount(json("/v1/summaries/2026-10").get("income")));
    }

    private static JsonNode point(JsonNode series, String start) {
        for (JsonNode p : series.get("points")) {
            if (p.get("periodStart").asString().equals(start)) {
                return p;
            }
        }
        throw new AssertionError("sem ponto em " + start);
    }

    @Test
    @DisplayName("PUT define e limpa a competência (a soma de todos os meses não muda); versão desatualizada é 409")
    void deveEditarEDefinirELimpar() throws Exception {
        JsonNode created = createIncome("1500.00", "2026-09-16", "Adiantamento", null);
        String id = created.get("id").asString();
        assertThat(created.get("incomeCompetence").isNull()).isTrue();
        assertThat(amount(json("/v1/summaries/2026-09").get("income"))).isEqualTo("1500.00");

        JsonNode defined = body(mockMvc.perform(put("/v1/transactions/" + id, incomeJson("1500.00", "2026-09-16", "Adiantamento",
                ",\"incomeCompetence\":\"2026-10\",\"version\":0"))).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(defined.get("incomeCompetence").asString()).isEqualTo("2026-10");
        assertThat(defined.get("version").asLong()).isEqualTo(1L);
        assertThat(amount(json("/v1/summaries/2026-09").get("income"))).isEqualTo("0.00");
        assertThat(amount(json("/v1/summaries/2026-10").get("income"))).isEqualTo("1500.00");

        mockMvc.perform(put("/v1/transactions/" + id, incomeJson("1500.00", "2026-09-16", "Adiantamento", ",\"incomeCompetence\":\"2026-11\",\"version\":0")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        JsonNode cleared = body(mockMvc.perform(put("/v1/transactions/" + id, incomeJson("1500.00", "2026-09-16", "Adiantamento", ",\"version\":1")))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(cleared.get("incomeCompetence").isNull()).isTrue();
        assertThat(amount(json("/v1/summaries/2026-09").get("income"))).isEqualTo("1500.00");
        assertThat(amount(json("/v1/summaries/2026-10").get("income"))).isEqualTo("0.00");
        assertThat(json("/v1/transactions?from=2026-09-01&to=2026-09-30").get("items").get(0).has("incomeCompetence")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_postings WHERE transaction_id = ?::uuid AND active AND book_type = 'INCOME'",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT competence FROM ledger_postings WHERE transaction_id = ?::uuid AND active AND book_type = 'INCOME'",
                String.class, id)).isEqualTo("2026-09");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_revisions WHERE transaction_id = ?::uuid", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT snapshot->>'incomeCompetence' FROM transaction_revisions WHERE transaction_id = ?::uuid AND revision = 2",
                String.class, id)).isEqualTo("2026-10");
    }

    @Test
    @DisplayName("validações: formato inválido 400; fora da janela 422 INCOME_COMPETENCE_INVALID; despesa, transferência e ajuste não aceitam o campo (400)")
    void deveValidar() throws Exception {
        String key = UUID.randomUUID().toString();
        mockMvc.perform(post("/v1/transactions", incomeJson("10.00", "2026-10-02", "x", ",\"incomeCompetence\":\"2026-13\"")).header("Idempotency-Key", key))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/transactions", incomeJson("10.00", "2026-10-02", "x", ",\"incomeCompetence\":\"outubro\"")).header("Idempotency-Key", key))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/transactions", incomeJson("10.00", "2026-10-02", "x", ",\"incomeCompetence\":\"2101-01\"")).header("Idempotency-Key", key))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INCOME_COMPETENCE_INVALID"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/transactions", incomeJson("10.00", "2026-10-02", "x", ",\"incomeCompetence\":\"1999-12\"")).header("Idempotency-Key", key))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INCOME_COMPETENCE_INVALID"));
        mockMvc.perform(post("/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"10.00\",\"date\":\"2026-10-02\",\"accountId\":\"" + nubank
                + "\",\"incomeCompetence\":\"2026-11\"}").header("Idempotency-Key", key)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/transactions", "{\"type\":\"ADJUSTMENT\",\"amount\":\"10.00\",\"date\":\"2026-10-02\",\"accountId\":\"" + nubank
                + "\",\"direction\":\"INCREASE\",\"reason\":\"x\",\"incomeCompetence\":\"2026-11\"}").header("Idempotency-Key", key)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    @DisplayName("estornar a receita desativa o lançamento na competência dela: o mês some da renda e o caixa volta")
    void deveEstornar() throws Exception {
        String id = createIncome("1500.00", "2026-09-16", "Adiantamento", "2026-10").get("id").asString();

        mockMvc.perform(post("/v1/transactions/" + id + "/void", "{\"reason\":\"engano\"}")).andExpect(status().isOk());

        assertThat(amount(json("/v1/summaries/2026-10").get("income"))).isEqualTo("0.00");
        assertThat(amount(json("/v1/dashboard/summary").get("balances").get("inAccounts"))).isEqualTo("10000.00");
    }

    @Test
    @DisplayName("recorrência de receita com deslocamento 1: a ocorrência realizada de 16/09 é renda de outubro; já realizadas não mudam ao editar")
    void deveHerdarNaRecorrencia() throws Exception {
        JsonNode rec = body(mockMvc.perform(post("/v1/recurrences", "{\"type\":\"INCOME\",\"amount\":\"1500.00\",\"description\":\"Adiantamento\",\"categoryId\":\""
                + salary + "\",\"accountId\":\"" + nubank + "\",\"method\":\"PIX\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-09-16\","
                + "\"incomeCompetenceOffsetMonths\":1}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
        String id = rec.get("id").asString();
        assertThat(rec.get("incomeCompetenceOffsetMonths").asInt()).isEqualTo(1);

        JsonNode realized = body(mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/2026-09-16/realize", "{}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(realized.get("incomeCompetence").asString()).isEqualTo("2026-10");
        assertThat(realized.get("date").asString()).isEqualTo("2026-09-16");
        assertThat(amount(json("/v1/summaries/2026-10").get("income"))).isEqualTo("1500.00");
        assertThat(amount(json("/v1/summaries/2026-09").get("income"))).isEqualTo("0.00");

        JsonNode updated = body(mockMvc.perform(put("/v1/recurrences/" + id, "{\"amount\":\"1500.00\",\"description\":\"Adiantamento\",\"categoryId\":\""
                + salary + "\",\"accountId\":\"" + nubank + "\",\"method\":\"PIX\",\"incomeCompetenceOffsetMonths\":0,\"version\":" + rec.get("version").asLong() + "}"))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(updated.get("incomeCompetenceOffsetMonths").asInt()).isZero();
        clock.advance(Duration.ofDays(7));
        bearer = auth.bearer(userId);
        JsonNode second = body(mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/2026-10-16/realize", "{}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isCreated()).andReturn());
        assertThat(second.get("incomeCompetence").isNull()).isTrue();
        assertThat(amount(json("/v1/summaries/2026-10").get("income"))).isEqualTo("3000.00");
        mockMvc.perform(post("/v1/recurrences", "{\"type\":\"EXPENSE\",\"amount\":\"10.00\",\"categoryId\":\"" + food + "\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-10-01\","
                + "\"accountId\":\"" + nubank + "\",\"method\":\"PIX\",\"incomeCompetenceOffsetMonths\":1}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences", "{\"type\":\"INCOME\",\"amount\":\"10.00\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-10-01\","
                + "\"accountId\":\"" + nubank + "\",\"method\":\"PIX\",\"incomeCompetenceOffsetMonths\":2}")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("exportações: o CSV traz a competência da renda na 10ª coluna e o backup traz o campo e a competência do lançamento (restaurável)")
    void deveExportar() throws Exception {
        salarioDividido();

        String csv = mockMvc.perform(get("/v1/exports/transactions.csv?from=2026-09-01&to=2026-10-31")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("Status;Direção;Competência da renda\r\n").contains("Adiantamento;Salário;Conta;Pix;1500,00;Ativa;;2026-10\r\n")
                .contains("Folha;Salário;Conta;Pix;2500,00;Ativa;;\r\n");

        JsonNode backup = body(mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        JsonNode adiantamento = null;
        for (JsonNode t : backup.get("transactions")) {
            if ("Adiantamento".equals(t.get("description").asString())) {
                adiantamento = t;
            }
        }
        assertThat(adiantamento).isNotNull();
        assertThat(adiantamento.get("incomeCompetence").asString()).isEqualTo("2026-10");
        BigDecimal income2610 = BigDecimal.ZERO;
        for (JsonNode p : backup.get("ledgerPostings")) {
            if (p.get("bookType").asString().equals("INCOME") && p.get("active").asBoolean() && p.get("competence").asString().equals("2026-10")) {
                income2610 = income2610.subtract(new BigDecimal(amount(p.get("amount"))));
            }
        }
        assertThat(income2610).isEqualByComparingTo("4000.00");
    }

    @Test
    @DisplayName("busca devolve a competência da renda da transação")
    void deveDevolverNaBusca() throws Exception {
        createIncome("1500.00", "2026-09-16", "Adiantamento especial", "2026-10");

        JsonNode hits = json("/v1/search?q=Adiantamento").get("transactions");

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("incomeCompetence").asString()).isEqualTo("2026-10");
        assertThat(hits.get(0).get("date").asString()).isEqualTo("2026-09-16");
    }

    @Test
    @DisplayName("o banco guarda a regra: só o livro INCOME tem competência diferente do mês da data; janela e formato; despesa nunca")
    void deveGuardarARegraNoBanco() throws Exception {
        String id = createIncome("1500.00", "2026-09-16", "Adiantamento", "2026-10").get("id").asString();

        assertThat(jdbc.queryForObject("SELECT income_competence FROM transactions WHERE id = ?::uuid", String.class, id)).isEqualTo("2026-10");
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_postings SET competence = '2026-10' WHERE transaction_id = ?::uuid AND book_type = 'ACCOUNT'", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_postings SET competence = '2101-01' WHERE transaction_id = ?::uuid AND book_type = 'INCOME'", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE transactions SET income_competence = '2026-13' WHERE id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        String expense = body(mockMvc.perform(post("/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"10.00\",\"date\":\"2026-10-02\",\"description\":\"x\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        assertThatThrownBy(() -> jdbc.update("UPDATE transactions SET income_competence = '2026-10' WHERE id = ?::uuid", expense))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_postings SET competence = '2026-11' WHERE transaction_id = ?::uuid AND book_type = 'EXPENSE'", expense))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
