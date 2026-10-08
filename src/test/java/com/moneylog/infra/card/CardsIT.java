package com.moneylog.infra.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.FixedClockConfig;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

/**
 * T51: cartões (criar, consultar, editar detalhes, arquivar, desarquivar, listar), regras de fechamento gravadas junto e
 * isolamento por usuário. "Hoje" é 10/10/2026 (relógio fixo). Cada teste usa um usuário novo.
 */
@IntegrationTest
@Import(FixedClockConfig.class)
@DisplayName("Cartões (T51)")
class CardsIT {

    private static final String ROXINHO = "{\"name\":\"Roxinho\",\"institution\":\"Nubank\",\"limit\":{\"amount\":\"10000.00\","
            + "\"currency\":\"BRL\"},\"closingDay\":25,\"dueDay\":5%s}";

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

    private UUID userId;
    private String bearer;

    @BeforeEach
    void novoUsuario() {
        userId = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, userId, "cartao-" + userId + "@moneylog.com"));
        bearer = auth.bearer(userId);
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

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String roxinho(String extra) {
        return ROXINHO.formatted(extra);
    }

    private static String cardBody(String name, String institution, String limit, int closingDay, int dueDay) {
        return "{\"name\":\"" + name + "\"," + (institution == null ? "" : "\"institution\":\"" + institution + "\",")
                + "\"limit\":{\"amount\":\"" + limit + "\",\"currency\":\"BRL\"},\"closingDay\":" + closingDay + ",\"dueDay\":" + dueDay + "}";
    }

    private JsonNode createCard(String body) throws Exception {
        return json(mockMvc.perform(post("/v1/cards", body)).andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private String createNubank() throws Exception {
        return json(mockMvc.perform(post("/v1/accounts", "{\"name\":\"Nubank\",\"type\":\"CHECKING\",\"institution\":\"Nubank\","
                + "\"openingBalance\":{\"amount\":\"20000.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    // ---------------------------------------------------------------- CA1

    @Test
    @DisplayName("CA1: POST cria o Roxinho com os números calculados e uma regra de fechamento (mês da fatura corrente)")
    void deveCriarOCartao() throws Exception {
        String nubank = createNubank();

        MvcResult result = mockMvc.perform(post("/v1/cards", roxinho(",\"defaultPaymentAccountId\":\"" + nubank + "\"")))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.name").value("Roxinho")).andExpect(jsonPath("$.institution").value("Nubank"))
                .andExpect(jsonPath("$.limit.amount").value("10000.00")).andExpect(jsonPath("$.availableLimit.amount").value("10000.00"))
                .andExpect(jsonPath("$.overLimit").value(false)).andExpect(jsonPath("$.creditBalance.amount").value("0.00"))
                .andExpect(jsonPath("$.usedRatio").value(0.0)).andExpect(jsonPath("$.closingDay").value(25))
                .andExpect(jsonPath("$.dueDay").value(5)).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.defaultPaymentAccountId").value(nubank))
                .andExpect(jsonPath("$.totalCommitted.amount").value("0.00")).andExpect(jsonPath("$.committedFuture.amount").value("0.00"))
                .andExpect(jsonPath("$.archivable").value(true)).andExpect(jsonPath("$.currentInvoice.status").value("OPEN"))
                .andExpect(jsonPath("$.currentInvoice.referenceMonth").value("2026-11"))
                .andExpect(jsonPath("$.currentInvoice.periodStart").value("2026-09-25"))
                .andExpect(jsonPath("$.currentInvoice.closingDate").value("2026-10-25"))
                .andExpect(jsonPath("$.currentInvoice.dueDate").value("2026-11-05"))
                .andExpect(jsonPath("$.currentInvoice.total.amount").value("0.00"))
                .andExpect(jsonPath("$.currentInvoice.hasLateEntries").value(false)).andReturn();
        JsonNode card = json(result);
        UUID id = UUID.fromString(card.get("id").asString());

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/cards/" + id);
        assertThat(card.get("currentInvoice").get("id").asString())
                .isEqualTo(com.moneylog.core.card.domain.InvoiceIds.of(id, LocalDate.of(2026, 10, 25)).toString());
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT effective_from, closing_day, due_day, user_id FROM credit_card_billing_rules WHERE card_id = ?", id);
        assertThat(rules).hasSize(1);
        assertThat(rules.getFirst()).containsEntry("closing_day", 25).containsEntry("due_day", 5)
                .containsEntry("user_id", userId);
        assertThat(rules.getFirst().get("effective_from").toString().strip()).isEqualTo("2026-10");
        // a fatura corrente vazia não tem linha (ADR-006: materialização preguiçosa)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices WHERE card_id = ?", Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("sem instituição nem conta padrão também cria (campos opcionais)")
    void deveCriarSemOpcionais() throws Exception {
        mockMvc.perform(post("/v1/cards", cardBody("Sem banco", null, "500.00", 31, 10))).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.institution").doesNotExist())
                .andExpect(jsonPath("$.defaultPaymentAccountId").doesNotExist());
    }

    // ---------------------------------------------------------------- CA2

    @Test
    @DisplayName("CA2: dias fora de 1..31, limite zero ou acima do teto, nome vazio ou longo: 422 com o code da regra e nada gravado")
    void deveRecusarDadosInvalidos() throws Exception {
        record Bad(String body, String code) {
        }
        List<Bad> cases = List.of(
                new Bad(cardBody("X", null, "100.00", 0, 5), "CARD_INVALID_BILLING_DAY"),
                new Bad(cardBody("X", null, "100.00", 32, 5), "CARD_INVALID_BILLING_DAY"),
                new Bad(cardBody("X", null, "100.00", 25, 0), "CARD_INVALID_BILLING_DAY"),
                new Bad(cardBody("X", null, "0.00", 25, 5), "CARD_INVALID_LIMIT"),
                new Bad(cardBody("X", null, "-10.00", 25, 5), "CARD_INVALID_LIMIT"),
                new Bad(cardBody("X", null, "1000000000.00", 25, 5), "AMOUNT_ABOVE_LIMIT"),
                new Bad(cardBody("", null, "100.00", 25, 5), "CARD_NAME_REQUIRED"),
                new Bad(cardBody("   ", null, "100.00", 25, 5), "CARD_NAME_REQUIRED"),
                new Bad(cardBody("N".repeat(101), null, "100.00", 25, 5), "CARD_NAME_TOO_LONG"),
                new Bad(cardBody("X", "I".repeat(101), "100.00", 25, 5), "CARD_INSTITUTION_TOO_LONG"));
        for (Bad bad : cases) {
            mockMvc.perform(post("/v1/cards", bad.body())).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value(bad.code())).andExpect(ContractValidation.conforms());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_cards WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_billing_rules WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    @DisplayName("corpo sem campo obrigatório, com campo desconhecido, número de cartão ou texto de controle: 400")
    void deveValidarOCorpo() throws Exception {
        mockMvc.perform(post("/v1/cards", "{\"limit\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"closingDay\":25,\"dueDay\":5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/cards", "{\"name\":\"X\",\"closingDay\":25,\"dueDay\":5}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/cards", roxinho(",\"number\":\"4111111111111111\""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD")).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/cards", roxinho(",\"cvv\":\"123\""))).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/cards", cardBody("Rox\\u0007inho", null, "10.00", 25, 5))).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/cards", cardBody("X", null, "10.00", 25, 5).replace("\"closingDay\":25", "\"closingDay\":\"vinte\"")))
                .andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_cards WHERE user_id = ?", Integer.class, userId)).isZero();
    }

    // ---------------------------------------------------------------- CA3

    @Test
    @DisplayName("CA3: PUT muda nome, instituição, limite e conta padrão (versão +1); versão velha é 409; dias diferentes sem a confirmação da prévia são 422 CARD_BILLING_CHANGE_NOT_CONFIRMED (T58)")
    void deveEditarDetalhes() throws Exception {
        String nubank = createNubank();
        String id = createCard(roxinho("")).get("id").asString();
        String update = "{\"name\":\"Roxinho Ultra\",\"institution\":\"Nu\",\"limit\":{\"amount\":\"12000.00\",\"currency\":\"BRL\"},"
                + "\"closingDay\":25,\"dueDay\":5,\"defaultPaymentAccountId\":\"" + nubank + "\",\"version\":%d}";

        mockMvc.perform(put("/v1/cards/" + id, update.formatted(0))).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.name").value("Roxinho Ultra")).andExpect(jsonPath("$.institution").value("Nu"))
                .andExpect(jsonPath("$.limit.amount").value("12000.00")).andExpect(jsonPath("$.availableLimit.amount").value("12000.00"))
                .andExpect(jsonPath("$.defaultPaymentAccountId").value(nubank)).andExpect(jsonPath("$.version").value(1));
        assertThat(jdbc.queryForObject("SELECT limit_cents FROM credit_cards WHERE id = ?", Long.class, UUID.fromString(id))).isEqualTo(1_200_000L);

        mockMvc.perform(put("/v1/cards/" + id, update.formatted(0))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(ContractValidation.conforms());
        mockMvc.perform(put("/v1/cards/" + id, update.formatted(1).replace("\"closingDay\":25", "\"closingDay\":10")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CARD_BILLING_CHANGE_NOT_CONFIRMED"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(put("/v1/cards/" + id, update.formatted(1).replace("\"dueDay\":5", "\"dueDay\":28")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CARD_BILLING_CHANGE_NOT_CONFIRMED"));
        mockMvc.perform(get("/v1/cards/" + id)).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.closingDay").value(25));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_billing_rules WHERE card_id = ?", Integer.class, UUID.fromString(id)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CA3: conta padrão de outro usuário ou inexistente é 404 ACCOUNT_NOT_FOUND, arquivada é 422 ACCOUNT_ARCHIVED e não líquida é 422 TRANSACTION_ACCOUNT_NOT_CASH")
    void deveValidarAContaPadrao() throws Exception {
        UUID other = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        committed(() -> {
            SqlFixtures.insertUser(jdbc, other, "alheio-" + other + "@moneylog.com");
            SqlFixtures.insertAccount(jdbc, Account.open(foreign, other, "Alheia", AccountType.CHECKING, null, Money.of("1.00"),
                    LocalDate.of(2026, 10, 1)));
        });
        String archived = json(mockMvc.perform(post("/v1/accounts", "{\"name\":\"Velha\",\"type\":\"CHECKING\","
                + "\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        mockMvc.perform(post("/v1/accounts/" + archived + "/archive", "")).andExpect(status().isOk());
        String invest = json(mockMvc.perform(post("/v1/accounts", "{\"name\":\"CDB\",\"type\":\"INVESTMENT\","
                + "\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();

        mockMvc.perform(post("/v1/cards", roxinho(",\"defaultPaymentAccountId\":\"" + foreign + "\""))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/cards", roxinho(",\"defaultPaymentAccountId\":\"" + UUID.randomUUID() + "\"")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mockMvc.perform(post("/v1/cards", roxinho(",\"defaultPaymentAccountId\":\"" + archived + "\"")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/cards", roxinho(",\"defaultPaymentAccountId\":\"" + invest + "\"")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_ACCOUNT_NOT_CASH"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_cards WHERE user_id = ?", Integer.class, userId)).isZero();

        String id = createCard(roxinho("")).get("id").asString();
        mockMvc.perform(put("/v1/cards/" + id, "{\"name\":\"R\",\"limit\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"closingDay\":25,"
                + "\"dueDay\":5,\"defaultPaymentAccountId\":\"" + foreign + "\",\"version\":0}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND")).andExpect(ContractValidation.conforms());
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4: sem saldo, crédito e parcelas arquiva (ARCHIVED) e desarquiva (ACTIVE); arquivar de novo é 422 CARD_ALREADY_ARCHIVED; desarquivar ativo é 422 CARD_NOT_ARCHIVED")
    void deveArquivarEDesarquivar() throws Exception {
        String id = createCard(roxinho("")).get("id").asString();

        mockMvc.perform(post("/v1/cards/" + id + "/archive", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("ARCHIVED")).andExpect(jsonPath("$.archivable").value(false))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(post("/v1/cards/" + id + "/archive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_ALREADY_ARCHIVED")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/cards/" + id + "/unarchive", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.archivable").value(true))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/v1/cards/" + id + "/unarchive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_NOT_ARCHIVED")).andExpect(ContractValidation.conforms());
        assertThat(jdbc.queryForObject("SELECT status FROM credit_cards WHERE id = ?", String.class, UUID.fromString(id))).isEqualTo("ACTIVE");
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5: cartão de outro usuário é 404 CARD_NOT_FOUND em GET, PUT, archive e unarchive; a lista só traz os do usuário e ?status=ARCHIVED filtra")
    void deveIsolarPorUsuarioEFiltrarPorStatus() throws Exception {
        UUID other = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        committed(() -> {
            SqlFixtures.insertUser(jdbc, other, "alheio-" + other + "@moneylog.com");
            jdbc.update("INSERT INTO credit_cards (id, user_id, name, limit_cents, currency, status, created_at, updated_at) "
                    + "VALUES (?, ?, 'Alheio', 100000, 'BRL', 'ACTIVE', now(), now())", foreign, other);
            jdbc.update("INSERT INTO credit_card_billing_rules (id, user_id, card_id, effective_from, closing_day, due_day, created_at, updated_at) "
                    + "VALUES (gen_random_uuid(), ?, ?, '2026-10', 25, 5, now(), now())", other, foreign);
        });
        String mine = createCard(cardBody("Beta", "B", "100.00", 20, 1)).get("id").asString();
        String archived = createCard(cardBody("alfa", null, "100.00", 20, 1)).get("id").asString();
        mockMvc.perform(post("/v1/cards/" + archived + "/archive", "")).andExpect(status().isOk());

        mockMvc.perform(get("/v1/cards/" + foreign)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(put("/v1/cards/" + foreign, "{\"name\":\"X\",\"limit\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},\"closingDay\":25,"
                + "\"dueDay\":5,\"version\":0}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/cards/" + foreign + "/archive", "")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CARD_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/cards/" + foreign + "/unarchive", "")).andExpect(status().isNotFound())
                .andExpect(ContractValidation.conforms());

        assertThat(names(json(mockMvc.perform(get("/v1/cards")).andExpect(ContractValidation.conforms()).andReturn()))).containsExactly("Beta");
        assertThat(names(json(mockMvc.perform(get("/v1/cards?status=ACTIVE")).andExpect(ContractValidation.conforms()).andReturn())))
                .containsExactly("Beta");
        assertThat(names(json(mockMvc.perform(get("/v1/cards?status=ARCHIVED")).andExpect(ContractValidation.conforms()).andReturn())))
                .containsExactly("alfa");
        mockMvc.perform(get("/v1/cards/" + mine)).andExpect(status().isOk()).andExpect(ContractValidation.conforms());
        mockMvc.perform(get("/v1/cards?status=NOPE")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        assertThat(jdbc.queryForObject("SELECT name FROM credit_cards WHERE id = ?", String.class, foreign)).isEqualTo("Alheio");
    }

    @Test
    @DisplayName("lista ordena por nome sem diferenciar maiúsculas e pagina com hasNext")
    void devePaginarOrdenandoPorNome() throws Exception {
        createCard(cardBody("zeta", null, "100.00", 20, 1));
        createCard(cardBody("Alfa", null, "100.00", 20, 1));
        createCard(cardBody("beta", null, "100.00", 20, 1));

        assertThat(names(json(mockMvc.perform(get("/v1/cards")).andReturn()))).containsExactly("Alfa", "beta", "zeta");
        JsonNode page1 = json(mockMvc.perform(get("/v1/cards?page=1&pageSize=2")).andExpect(ContractValidation.conforms()).andReturn());
        JsonNode page2 = json(mockMvc.perform(get("/v1/cards?page=2&pageSize=2")).andExpect(ContractValidation.conforms()).andReturn());
        assertThat(names(page1)).containsExactly("Alfa", "beta");
        assertThat(page1.get("hasNext").asBoolean()).isTrue();
        assertThat(names(page2)).containsExactly("zeta");
        assertThat(page2.get("hasNext").asBoolean()).isFalse();
        mockMvc.perform(get("/v1/cards?pageSize=51")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6: credit_cards não tem coluna de número de cartão, CVV, bandeira ou dígitos")
    void naoDeveGuardarDadosSensiveis() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'credit_cards' ORDER BY column_name", String.class);

        assertThat(columns).containsExactlyInAnyOrder("id", "user_id", "name", "institution", "limit_cents", "currency",
                "default_payment_account_id", "status", "created_at", "updated_at", "version", "closing_confirmed");
        assertThat(columns.stream().map(String::toLowerCase).collect(Collectors.joining(","))).doesNotContain("number", "cvv", "brand",
                "digits", "pan");
    }

    @Test
    @DisplayName("sem token as rotas de cartão respondem 401")
    void deveExigirToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/cards")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.post("/v1/cards").contentType(MediaType.APPLICATION_JSON).content(roxinho("")))
                .andExpect(status().isUnauthorized());
    }

    private static List<String> names(JsonNode slice) {
        return slice.get("items").valueStream().map(card -> card.get("name").asString()).toList();
    }
}
