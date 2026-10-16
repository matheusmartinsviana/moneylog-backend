package com.moneylog.infra.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("Visões da lista: registros e lançamentos por competência (T28)")
class TransactionListIT {

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
    @Autowired
    private LedgerWriteGateway ledgerWrite;

    private UUID userId;
    private String bearer;
    private String nubank;
    private String xp;
    private String xpInvest;
    private String food;
    private String transport;
    private String salary;
    private String pixId;
    private String debitId;
    private String transferId;
    private String refundId;
    private String voidedId;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "lista-" + userId + "@example.com"));
        bearer = auth.bearer(userId);
        nubank = account("Nubank", "CHECKING", "20000.00");
        xp = account("XP", "CHECKING", "0.00");
        xpInvest = account("XP Invest", "INVESTMENT", "0.00");
        food = category("Alimentação", CategoryKind.EXPENSE);
        transport = category("Transporte", CategoryKind.EXPENSE);
        salary = category("Salário", CategoryKind.INCOME);

        pixId = expense("100.00", "2026-10-10", "Mercado do bairro", food, "PIX");
        debitId = expense("40.00", "2026-10-11", "Uber", transport, "DEBIT");
        create("{\"type\":\"INCOME\",\"amount\":\"3000.00\",\"date\":\"2026-10-05\",\"description\":\"Salário\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + salary + "\",\"method\":\"TRANSFER\"}");
        transferId = create("{\"type\":\"TRANSFER\",\"amount\":\"1000.00\",\"date\":\"2026-10-10\",\"description\":\"Para a XP\",\"fromAccountId\":\""
                + nubank + "\",\"toAccountId\":\"" + xp + "\"}");
        create("{\"type\":\"INVESTMENT\",\"amount\":\"500.00\",\"date\":\"2026-10-15\",\"description\":\"CDB\",\"cashAccountId\":\"" + nubank
                + "\",\"investmentAccountId\":\"" + xpInvest + "\",\"direction\":\"CONTRIBUTION\"}");
        refundId = create("{\"type\":\"REFUND\",\"amount\":\"50.00\",\"date\":\"2026-10-10\",\"description\":\"Estorno do mercado\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + food + "\",\"refundOfTransactionId\":\"" + pixId + "\"}");
        voidedId = expense("30.00", "2026-10-12", "Lançada errado", food, "PIX");
        voidBySql(voidedId);
        expense("70.00", "2026-11-05", "Conta de novembro", food, "BOLETO");
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

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String account(String name, String type, String balance) throws Exception {
        return body(mockMvc.perform(post("/v1/accounts", "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"openingBalance\":{\"amount\":\""
                + balance + "\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private String category(String name, CategoryKind kind) {
        Category category = Category.create(UUID.randomUUID(), userId, name, kind);
        committed(() -> SqlFixtures.insertCategory(jdbc, category));
        return category.id().toString();
    }

    private String create(String json) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        return body(mockMvc.perform(post("/v1/transactions", json).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private String expense(String amount, String date, String description, String category, String method) throws Exception {
        return create("{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":\"" + description + "\",\"accountId\":\""
                + nubank + "\",\"categoryId\":\"" + category + "\",\"method\":\"" + method + "\"}");
    }

    private void voidBySql(String id) {
        committed(() -> {
            jdbc.update("update transactions set status = 'VOIDED', void_reason = 'engano', voided_at = now(), version = version + 1 where id = ?", UUID.fromString(id));
            jdbc.update("update ledger_postings set active = false, deactivated_at = now(), deactivation_reason = 'VOID' where transaction_id = ?", UUID.fromString(id));
        });
    }

    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get("/v1/transactions" + query));
    }

    private JsonNode listBody(String query) throws Exception {
        return body(list(query).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private JsonNode searchBody(String q) throws Exception {
        return body(mockMvc.perform(get("/v1/transactions" + OUTUBRO).param("q", q)).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
    }

    private static List<String> ids(JsonNode slice) {
        List<String> ids = new ArrayList<>();
        slice.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private static final String OUTUBRO = "?from=2026-10-01&to=2026-10-31";

    @Test
    @DisplayName("CA1: outubro traz as 6 movimentações ativas em date desc, created_at desc; totals.count 6 e cashFlow 3.050,00 / 640,00 / 2.410,00")
    void deveListarOMes() throws Exception {
        JsonNode result = listBody(OUTUBRO);

        assertThat(result.get("items")).hasSize(6);
        assertThat(result.get("hasNext").asBoolean()).isFalse();
        assertThat(result.get("items").get(0).get("type").asString()).isEqualTo("INVESTMENT");
        assertThat(ids(result).subList(1, 5)).containsExactly(debitId, refundId, transferId, pixId);
        assertThat(result.get("items").get(5).get("type").asString()).isEqualTo("INCOME");
        assertThat(ids(result)).doesNotContain(voidedId);
        assertThat(result.get("totals").get("count").asInt()).isEqualTo(6);
        assertThat(result.get("totals").get("cashFlow").get("in").get("amount").asString()).isEqualTo("3050.00");
        assertThat(result.get("totals").get("cashFlow").get("out").get("amount").asString()).isEqualTo("640.00");
        assertThat(result.get("totals").get("cashFlow").get("net").get("amount").asString()).isEqualTo("2410.00");
    }

    @Test
    @DisplayName("CA2: pageSize=2 traz 2 itens e hasNext, e os totals são os do filtro inteiro (iguais aos do CA1)")
    void devePaginarSemMudarOsTotais() throws Exception {
        JsonNode first = listBody(OUTUBRO + "&pageSize=2");
        JsonNode second = listBody(OUTUBRO + "&pageSize=2&page=2");
        JsonNode third = listBody(OUTUBRO + "&pageSize=2&page=3");

        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        assertThat(second.get("hasNext").asBoolean()).isTrue();
        assertThat(third.get("hasNext").asBoolean()).isFalse();
        assertThat(first.get("totals")).isEqualTo(listBody(OUTUBRO).get("totals"));
        assertThat(third.get("totals")).isEqualTo(first.get("totals"));
        List<String> all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        all.addAll(ids(third));
        assertThat(all).isEqualTo(ids(listBody(OUTUBRO)));
    }

    @Test
    @DisplayName("CA3: accountId=XP traz a transferência, count 1 e in 1.000,00; status=VOIDED traz só a estornada, count 0 e fluxo zerado")
    void deveFiltrarPorContaEStatus() throws Exception {
        JsonNode xpOnly = listBody(OUTUBRO + "&accountId=" + xp);
        assertThat(ids(xpOnly)).containsExactly(transferId);
        assertThat(xpOnly.get("totals").get("count").asInt()).isEqualTo(1);
        assertThat(xpOnly.get("totals").get("cashFlow").get("in").get("amount").asString()).isEqualTo("1000.00");
        assertThat(xpOnly.get("totals").get("cashFlow").get("out").get("amount").asString()).isEqualTo("0.00");

        JsonNode nubankOnly = listBody(OUTUBRO + "&accountId=" + nubank);
        assertThat(nubankOnly.get("items")).hasSize(6);
        assertThat(nubankOnly.get("totals").get("cashFlow").get("out").get("amount").asString()).isEqualTo("1640.00");

        JsonNode investOnly = listBody(OUTUBRO + "&accountId=" + xpInvest);
        assertThat(investOnly.get("totals").get("cashFlow").get("in").get("amount").asString()).isEqualTo("500.00");

        JsonNode voided = listBody(OUTUBRO + "&status=VOIDED");
        assertThat(ids(voided)).containsExactly(voidedId);
        assertThat(voided.get("items").get(0).get("status").asString()).isEqualTo("VOIDED");
        assertThat(voided.get("totals").get("count").asInt()).isZero();
        assertThat(voided.get("totals").get("cashFlow").get("in").get("amount").asString()).isEqualTo("0.00");
        assertThat(voided.get("totals").get("cashFlow").get("out").get("amount").asString()).isEqualTo("0.00");
    }

    @Test
    @DisplayName("CA3: q filtra a descrição sem diferenciar maiúsculas; type, categoryId e method restringem items e totals; items e count concordam")
    void deveFiltrarPorTextoTipoCategoriaEMetodo() throws Exception {
        JsonNode q = listBody(OUTUBRO + "&q=MERCADO");
        assertThat(ids(q)).containsExactly(refundId, pixId);
        assertThat(q.get("totals").get("count").asInt()).isEqualTo(2);
        assertThat(q.get("totals").get("cashFlow").get("in").get("amount").asString()).isEqualTo("50.00");
        assertThat(q.get("totals").get("cashFlow").get("out").get("amount").asString()).isEqualTo("100.00");
        assertThat(searchBody("100%").get("items")).isEmpty();
        assertThat(searchBody("a_c").get("items")).isEmpty();
        assertThat(searchBody("SALÁRIO").get("items")).hasSize(1);
        assertThat(searchBody("salario").get("items")).isEmpty();

        JsonNode expenses = listBody(OUTUBRO + "&type=EXPENSE");
        assertThat(ids(expenses)).containsExactly(debitId, pixId);
        assertThat(expenses.get("totals").get("count").asInt()).isEqualTo(2);
        assertThat(expenses.get("totals").get("cashFlow").get("out").get("amount").asString()).isEqualTo("140.00");

        JsonNode transportOnly = listBody(OUTUBRO + "&categoryId=" + transport);
        assertThat(ids(transportOnly)).containsExactly(debitId);
        assertThat(transportOnly.get("totals").get("count").asInt()).isEqualTo(1);

        JsonNode pix = listBody(OUTUBRO + "&method=PIX");
        assertThat(ids(pix)).containsExactly(pixId);
        assertThat(pix.get("totals").get("cashFlow").get("out").get("amount").asString()).isEqualTo("100.00");

        JsonNode everything = listBody("");
        assertThat(everything.get("totals").get("count").asInt()).isEqualTo(7);
        assertThat(everything.get("totals").get("cashFlow").get("out").get("amount").asString()).isEqualTo("710.00");
    }

    @Test
    @DisplayName("CA4: entries de CONSUMPTION em 2026-10: 3 linhas (reembolso negativo), total 90,00, byCategory Alimentação 50,00 / Transporte 40,00; INCOME 3.000,00 positivo")
    void deveListarLancamentosPorCompetencia() throws Exception {
        MvcResult consumo = mockMvc.perform(get("/v1/entries?competence=2026-10&lens=CONSUMPTION")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn();
        JsonNode body = body(consumo);

        assertThat(body.get("items")).hasSize(3);
        assertThat(body.get("hasNext").asBoolean()).isFalse();
        assertThat(body.get("totals").get("total").get("amount").asString()).isEqualTo("90.00");
        assertThat(body.get("totals").get("byCategory")).hasSize(2);
        assertThat(body.get("totals").get("byCategory").get(0).get("category").get("name").asString()).isEqualTo("Alimentação");
        assertThat(body.get("totals").get("byCategory").get(0).get("amount").get("amount").asString()).isEqualTo("50.00");
        assertThat(body.get("totals").get("byCategory").get(1).get("category").get("name").asString()).isEqualTo("Transporte");
        assertThat(body.get("totals").get("byCategory").get(1).get("amount").get("amount").asString()).isEqualTo("40.00");
        List<String> amounts = new ArrayList<>();
        body.get("items").forEach(item -> amounts.add(item.get("amount").get("amount").asString()));
        assertThat(amounts).containsExactlyInAnyOrder("100.00", "40.00", "-50.00");
        JsonNode refund = null;
        for (JsonNode item : body.get("items")) {
            if (item.get("transactionType").asString().equals("REFUND")) {
                refund = item;
            }
        }
        assertThat(refund).isNotNull();
        assertThat(refund.get("transactionId").asString()).isEqualTo(refundId);
        assertThat(refund.get("via").asString()).isEqualTo("ACCOUNT");
        assertThat(refund.get("accountId").asString()).isEqualTo(nubank);
        assertThat(refund.get("cardId").isNull()).isTrue();
        assertThat(refund.get("installment").isNull()).isTrue();
        assertThat(refund.get("competence").asString()).isEqualTo("2026-10");
        assertThat(refund.get("description").asString()).isEqualTo("Estorno do mercado");

        assertThat(body(mockMvc.perform(get("/v1/entries?competence=2026-10")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn()))
                .isEqualTo(body);

        JsonNode income = body(mockMvc.perform(get("/v1/entries?competence=2026-10&lens=INCOME")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(income.get("items")).hasSize(1);
        assertThat(income.get("items").get(0).get("amount").get("amount").asString()).isEqualTo("3000.00");
        assertThat(income.get("totals").get("total").get("amount").asString()).isEqualTo("3000.00");
    }

    @Test
    @DisplayName("CA4: filtros da visão por competência (categoria, conta, método) e paginação mantêm o total do filtro inteiro")
    void deveFiltrarEPaginarLancamentos() throws Exception {
        JsonNode transportOnly = body(mockMvc.perform(get("/v1/entries?competence=2026-10&categoryId=" + transport)).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(transportOnly.get("items")).hasSize(1);
        assertThat(transportOnly.get("totals").get("total").get("amount").asString()).isEqualTo("40.00");

        JsonNode pix = body(mockMvc.perform(get("/v1/entries?competence=2026-10&method=PIX")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(pix.get("totals").get("total").get("amount").asString()).isEqualTo("100.00");

        JsonNode xpAccount = body(mockMvc.perform(get("/v1/entries?competence=2026-10&accountId=" + xp)).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(xpAccount.get("items")).isEmpty();
        assertThat(xpAccount.get("totals").get("total").get("amount").asString()).isEqualTo("0.00");

        JsonNode page = body(mockMvc.perform(get("/v1/entries?competence=2026-10&pageSize=2")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("hasNext").asBoolean()).isTrue();
        assertThat(page.get("totals").get("total").get("amount").asString()).isEqualTo("90.00");
        assertThat(page.get("items").get(0).get("date").asString()).isEqualTo("2026-10-11");

        JsonNode november = body(mockMvc.perform(get("/v1/entries?competence=2026-11")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(november.get("totals").get("total").get("amount").asString()).isEqualTo("70.00");
    }

    @Test
    @DisplayName("CA5: depois de estornar a despesa de 40,00 ela some das duas visões e dos totais")
    void deveTirarAEstornadaDasDuasVisoes() throws Exception {
        voidBySql(debitId);

        JsonNode records = listBody(OUTUBRO);
        assertThat(ids(records)).doesNotContain(debitId);
        assertThat(records.get("totals").get("count").asInt()).isEqualTo(5);
        assertThat(records.get("totals").get("cashFlow").get("out").get("amount").asString()).isEqualTo("600.00");
        JsonNode entries = body(mockMvc.perform(get("/v1/entries?competence=2026-10")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(entries.get("items")).hasSize(2);
        assertThat(entries.get("totals").get("total").get("amount").asString()).isEqualTo("50.00");
    }

    @Test
    @DisplayName("CA6: from > to, competence inválida, lens desconhecida, q grande demais e paginação fora do limite são 400 VALIDATION_FAILED")
    void deveRecusarFiltrosInvalidos() throws Exception {
        list("?from=2026-11-01&to=2026-10-01").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("from")).andExpect(ContractValidation.responseOnly());
        list("?from=ontem").andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        list("?type=NOPE").andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        list("?q=" + "x".repeat(101)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(ContractValidation.responseOnly());
        list("?pageSize=51").andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/entries?competence=2026-13")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("competence")).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/entries?competence=2026-10&lens=X")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(get("/v1/entries")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("isolamento: cada usuário só vê (e soma) as próprias movimentações")
    void deveIsolarPorUsuario() throws Exception {
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@example.com"));

        mockMvc.perform(MockMvcRequestBuilders.get("/v1/transactions" + OUTUBRO).header(HttpHeaders.AUTHORIZATION, auth.bearer(other)))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totals.count").value(0)).andExpect(jsonPath("$.totals.cashFlow.in.amount").value("0.00"));
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/entries?competence=2026-10").header(HttpHeaders.AUTHORIZATION, auth.bearer(other)))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totals.total.amount").value("0.00"));
    }

    @Test
    @DisplayName("CA7: com 2.000 movimentações um mês responde rápido (medição registrada) e a consulta da página pode usar o índice por data")
    void deveResponderRapidoComMuitasMovimentacoes() {
        UUID account = UUID.fromString(nubank);
        UUID categoryId = UUID.fromString(food);
        Account nubankAccount = Account.restore(account, userId, "Nubank", com.moneylog.core.account.domain.AccountType.CHECKING, null,
                Money.of("20000.00"), LocalDate.of(2026, 10, 1), com.moneylog.core.account.domain.AccountStatus.ACTIVE, 0);
        Category foodCategory = Category.restore(categoryId, userId, "Alimentação", CategoryKind.EXPENSE, false, false, 0);
        List<Transaction> bulk = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            LocalDate date = LocalDate.of(2025, 11, 1).plusDays(i % 365);
            bulk.add(AccountTransactions.expense(TransactionHeader.of(UUID.randomUUID(), userId, date, "Compra " + i), Money.of("1.00"),
                    nubankAccount, foodCategory, PaymentMethod.PIX));
        }
        committed(() -> {
            bulk.forEach(t -> SqlFixtures.insertTransactionRow(jdbc, t));
            bulk.forEach(t -> ledgerWrite.append(userId, t.entries()));
        });

        long started = System.nanoTime();
        MvcResult result = null;
        try {
            result = mockMvc.perform(get("/v1/transactions?from=2026-03-01&to=2026-03-31&pageSize=50")).andExpect(status().isOk())
                    .andExpect(ContractValidation.conforms()).andReturn();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.println("T28 CA7: GET /v1/transactions de um mês com 2.000 movimentações (+ as do cenário) em " + elapsedMs + " ms");
        assertThat(elapsedMs).as("medição registrada, não é gate rígido: só um teto de sanidade").isLessThan(5000);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<String> plan = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("set local enable_seqscan = off");
            return jdbc.queryForList("""
                    explain select * from transactions where user_id = ? and status = 'ACTIVE' and transaction_date >= date '2026-03-01'
                      and transaction_date <= date '2026-03-31' order by transaction_date desc, created_at desc, id desc limit 51""",
                    String.class, userId);
        });
        assertThat(String.join("\n", plan)).contains("ix_transactions_user_date");
    }
}
