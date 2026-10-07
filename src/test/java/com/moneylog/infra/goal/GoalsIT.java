package com.moneylog.infra.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

/**
 * T93 e T94: metas com reserva <b>lógica</b> (S-147), pela API real, com o contrato validando toda requisição e resposta. Cenário padrão do
 * {@link E4ItBase} (Nubank 20.000,00, Roxinho, relógio 10/10/2026) e a meta "Reserva de emergência" (alvo 20.000,00, prazo 31/12/2027, HIGH).
 */
@DisplayName("Metas e reservas lógicas: API, persistência, travas, reservado e disponível (T93, T94)")
class GoalsIT extends E4ItBase {

    private static final String EMERGENCIA = "{\"name\":\"Reserva de emergência\",\"target\":\"20000.00\",\"deadline\":\"2027-12-31\","
            + "\"priority\":\"HIGH\",\"description\":\"Seis meses de custo fixo\"}";

    private JsonNode newGoal(String json) throws Exception {
        return createdBody(post("/v1/goals", json));
    }

    private JsonNode emergencia() throws Exception {
        return newGoal(EMERGENCIA);
    }

    private String reservationBody(String accountId, String amount, String date, String note) {
        return "{\"accountId\":\"" + accountId + "\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    private MockHttpServletRequestBuilder reservation(String goalId, String body, UUID key) {
        return post("/v1/goals/" + goalId + "/reservations", body).header("Idempotency-Key", key.toString());
    }

    private JsonNode reserve(String goalId, String accountId, String amount) throws Exception {
        return okBody(reservation(goalId, reservationBody(accountId, amount, "2026-10-10", null), UUID.randomUUID()));
    }

    private String account(String name, String type, String opening) throws Exception {
        return createdBody(post("/v1/accounts", "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"openingBalance\":{\"amount\":\"" + opening
                + "\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
    }

    private void purchasesOfTheStageThree() throws Exception {
        String tech = category("Tecnologia", CategoryKind.EXPENSE);
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1200.00\",\"date\":\"2026-10-10\",\"description\":\"Notebook\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + tech + "\",\"installments\":6}");
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1083.00\",\"date\":\"2026-10-10\",\"description\":\"Mercado\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + tech + "\",\"installments\":1}");
    }

    private String otherUserBearer() {
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        return auth.bearer(other);
    }

    // ---------------------------------------------------------------- T93

    @Test
    @DisplayName("CA1: POST /v1/goals devolve 201 + Location com current 0,00 e progress 0.0000; a reserva de +8.500,00 na Nubank devolve 200 com a reserva, current 8.500,00, progress 0.4250 e monthlyNeeded 766,67")
    void deveCriarEReservar() throws Exception {
        MvcResult created = mockMvc.perform(post("/v1/goals", EMERGENCIA)).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.name").value("Reserva de emergência")).andExpect(jsonPath("$.target.amount").value("20000.00"))
                .andExpect(jsonPath("$.current.amount").value("0.00")).andExpect(jsonPath("$.progress").value("0.0000"))
                .andExpect(jsonPath("$.remaining.amount").value("20000.00")).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.deadline").value("2027-12-31")).andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.uncovered").value(false)).andExpect(jsonPath("$.reservations").isEmpty())
                .andExpect(jsonPath("$.version").value(0)).andReturn();
        String id = body(created).get("id").asString();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/v1/goals/" + id);

        mockMvc.perform(reservation(id, reservationBody(nubank, "8500.00", "2026-10-10", "primeiro aporte"), UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.current.amount").value("8500.00"))
                .andExpect(jsonPath("$.progress").value("0.4250")).andExpect(jsonPath("$.remaining.amount").value("11500.00"))
                .andExpect(jsonPath("$.monthlyNeeded.amount").value("766.67")).andExpect(jsonPath("$.reservations.length()").value(1))
                .andExpect(jsonPath("$.reservations[0].account.name").value("Nubank")).andExpect(jsonPath("$.reservations[0].account.id").value(nubank))
                .andExpect(jsonPath("$.reservations[0].amount.amount").value("8500.00")).andExpect(jsonPath("$.reservations[0].date").value("2026-10-10"))
                .andExpect(jsonPath("$.reservations[0].note").value("primeiro aporte")).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(okBody(get("/v1/goals/" + id)).get("current").get("amount").asString()).isEqualTo("8500.00");
    }

    @Test
    @DisplayName("CA2: a reserva não cria linha em transactions nem em ledger_postings e não muda o saldo, o consumo, a receita nem o patrimônio (garantia 1)")
    void naoDeveCriarMovimentacaoNemLancamento() throws Exception {
        String id = emergencia().get("id").asString();
        long transactions = count("transactions");
        long postings = count("ledger_postings");
        JsonNode before = summary();
        String balance = accountBalance();

        reserve(id, nubank, "1000.00");
        reserve(id, nubank, "-300.00");

        assertThat(count("transactions")).isEqualTo(transactions);
        assertThat(count("ledger_postings")).isEqualTo(postings);
        assertThat(ledgerTotal()).isZero();
        assertThat(accountBalance()).isEqualTo(balance).isEqualTo("20000.00");
        JsonNode after = summary();
        assertThat(after.get("month")).isEqualTo(before.get("month"));
        assertThat(after.get("netWorth")).isEqualTo(before.get("netWorth"));
        assertThat(after.get("balances").get("inAccounts")).isEqualTo(before.get("balances").get("inAccounts"));
        assertThat(after.get("committed")).isEqualTo(before.get("committed"));
        assertThat(after.get("projected")).isEqualTo(before.get("projected"));
        assertThat(money(after.get("balances"), "reserved")).isEqualTo("700.00");
        assertThat(money(after.get("balances"), "available")).isEqualTo("19300.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_reservations WHERE user_id = ?", Long.class, userId)).isEqualTo(2L);
    }

    @Test
    @DisplayName("CA3: Idempotency-Key repetida com o mesmo corpo não reserva de novo; com outro corpo é 409 IDEMPOTENCY_KEY_REUSED")
    void deveSerIdempotente() throws Exception {
        String id = emergencia().get("id").asString();
        UUID key = UUID.randomUUID();
        String body = reservationBody(nubank, "500.00", "2026-10-10", null);

        JsonNode first = okBody(reservation(id, body, key));
        JsonNode replay = okBody(reservation(id, body, key));
        mockMvc.perform(reservation(id, reservationBody(nubank, "501.00", "2026-10-10", null), key)).andExpect(status().isConflict())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(first.get("reservations")).hasSize(1);
        assertThat(replay.get("reservations")).hasSize(1);
        assertThat(replay.get("current").get("amount").asString()).isEqualTo("500.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_reservations WHERE user_id = ?", Long.class, userId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("CA3: criar a meta com a mesma Idempotency-Key devolve a mesma meta (201, uma linha)")
    void deveCriarComIdempotencia() throws Exception {
        UUID key = UUID.randomUUID();
        String first = body(mockMvc.perform(post("/v1/goals", EMERGENCIA).header("Idempotency-Key", key.toString())).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn()).get("id").asString();
        String second = body(mockMvc.perform(post("/v1/goals", EMERGENCIA).header("Idempotency-Key", key.toString())).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn()).get("id").asString();

        assertThat(second).isEqualTo(first);
        assertThat(count("goals")).isEqualTo(1);
        mockMvc.perform(post("/v1/goals", EMERGENCIA.replace("20000.00", "21000.00")).header("Idempotency-Key", key.toString()))
                .andExpect(status().isConflict()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("CA3: duas reservas simultâneas na mesma meta se serializam: ambas valem, current é a soma, a versão sobe 2 e nada é 500")
    void deveSerializarReservasConcorrentes() throws Exception {
        String id = emergencia().get("id").asString();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            String body = reservationBody(nubank, i + "00.00", "2026-10-10", null);
            UUID key = UUID.randomUUID();
            Callable<Integer> call = () -> {
                start.await();
                return mockMvc.perform(reservation(id, body, key)).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(call));
        }
        start.countDown();
        for (Future<Integer> result : results) {
            assertThat(result.get()).as("sem 500 e sem perder reserva").isEqualTo(200);
        }
        pool.shutdown();

        JsonNode goal = okBody(get("/v1/goals/" + id));
        assertThat(goal.get("current").get("amount").asString()).isEqualTo("2100.00");
        assertThat(goal.get("reservations")).hasSize(threads);
        assertThat(goal.get("version").asLong()).isEqualTo(threads);
        assertThat(count("transactions")).isZero();
    }

    @Test
    @DisplayName("CA4: reservar em conta arquivada → 422 ACCOUNT_ARCHIVED (a liberação passa); conta e meta de outro usuário → 404; a lista de outro usuário não vê a meta")
    void deveIsolarERecusar() throws Exception {
        String id = emergencia().get("id").asString();
        String xp = account("XP", "CHECKING", "0.00");
        reserve(id, xp, "100.00");
        // S-259: com reserva ativa positiva a conta não arquiva (a meta arquivada não reserva, então o caminho abaixo chega à conta arquivada com reserva)
        mockMvc.perform(post("/v1/accounts/" + xp + "/archive", "")).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_RESERVATIONS"));
        okBody(post("/v1/goals/" + id + "/archive", ""));
        mockMvc.perform(post("/v1/accounts/" + xp + "/archive", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms());
        okBody(post("/v1/goals/" + id + "/unarchive", ""));

        mockMvc.perform(reservation(id, reservationBody(xp, "1.00", "2026-10-10", null), UUID.randomUUID())).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED"));
        assertThat(reserve(id, xp, "-100.00").get("current").get("amount").asString()).isEqualTo("0.00");

        String other = otherUserBearer();
        String otherAccount = body(mockMvc.perform(postAs(other, "/v1/accounts", "{\"name\":\"Dele\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"10.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
        mockMvc.perform(reservation(id, reservationBody(otherAccount, "1.00", "2026-10-10", null), UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mockMvc.perform(getAs(other, "/v1/goals/" + id)).andExpect(status().isNotFound()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("GOAL_NOT_FOUND"));
        mockMvc.perform(postAs(other, "/v1/goals/" + id + "/reservations", reservationBody(otherAccount, "1.00", "2026-10-10", null))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isNotFound()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("GOAL_NOT_FOUND"));
        mockMvc.perform(putAs(other, "/v1/goals/" + id, "{\"name\":\"X\",\"target\":\"1.00\",\"priority\":\"LOW\",\"version\":0}")).andExpect(status().isNotFound())
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(postAs(other, "/v1/goals/" + id + "/archive", "")).andExpect(status().isNotFound()).andExpect(ContractValidation.conforms());
        assertThat(body(mockMvc.perform(getAs(other, "/v1/goals")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn()).get("items")).isEmpty();
        assertThat(count("goal_reservations")).isEqualTo(2);
    }

    @Test
    @DisplayName("A1 (S-259): conta com reserva ativa positiva (hoje ou futura) não arquiva: 422 ACCOUNT_HAS_RESERVATIONS, conta intacta e disponível igual; liberada, arquiva; desarquivar e reservar de novo volta a recusar")
    void naoDeveArquivarContaComReserva() throws Exception {
        String id = emergencia().get("id").asString();
        String xp = account("XP", "CHECKING", "0.00");
        okBody(reservation(id, reservationBody(xp, "100.00", "2026-10-10", null), UUID.randomUUID()));
        okBody(reservation(id, reservationBody(xp, "50.00", "2026-12-01", null), UUID.randomUUID()));
        JsonNode before = summary();

        mockMvc.perform(post("/v1/accounts/" + xp + "/archive", "")).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_RESERVATIONS")).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Reserva de emergência")));
        assertThat(okBody(get("/v1/accounts/" + xp)).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(summary().get("balances")).isEqualTo(before.get("balances"));

        // libera só a de hoje: a futura de 50,00 ainda impede (reserva ativa em qualquer data)
        okBody(reservation(id, reservationBody(xp, "-100.00", "2026-10-10", null), UUID.randomUUID()));
        mockMvc.perform(post("/v1/accounts/" + xp + "/archive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_RESERVATIONS"));
        okBody(reservation(id, reservationBody(xp, "-50.00", "2026-12-01", null), UUID.randomUUID()));
        mockMvc.perform(post("/v1/accounts/" + xp + "/archive", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        okBody(post("/v1/accounts/" + xp + "/unarchive", ""));
        okBody(reservation(id, reservationBody(xp, "10.00", "2026-10-10", null), UUID.randomUUID()));
        mockMvc.perform(post("/v1/accounts/" + xp + "/archive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_RESERVATIONS"));
        assertThat(count("transactions")).isZero();
    }

    @Test
    @DisplayName("CA5: GET ?status filtra pelo status derivado (ACTIVE, ACHIEVED, ARCHIVED); a lista pagina com hasNext")
    void deveFiltrarPeloStatusDerivado() throws Exception {
        String big = emergencia().get("id").asString();
        String small = newGoal("{\"name\":\"Curta\",\"target\":\"100.00\",\"priority\":\"LOW\"}").get("id").asString();
        String archived = newGoal("{\"name\":\"Arquivada\",\"target\":\"100.00\",\"priority\":\"MEDIUM\"}").get("id").asString();
        reserve(small, nubank, "100.00");
        okBody(post("/v1/goals/" + archived + "/archive", ""));

        assertThat(names(okBody(get("/v1/goals?status=ACHIEVED")))).containsExactly("Curta");
        assertThat(names(okBody(get("/v1/goals?status=ACTIVE")))).containsExactly("Reserva de emergência");
        assertThat(names(okBody(get("/v1/goals?status=ARCHIVED")))).containsExactly("Arquivada");
        assertThat(names(okBody(get("/v1/goals")))).containsExactly("Reserva de emergência", "Curta", "Arquivada");
        JsonNode firstPage = okBody(get("/v1/goals?pageSize=2"));
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("hasNext").asBoolean()).isTrue();
        assertThat(okBody(get("/v1/goals?pageSize=2&page=2")).get("hasNext").asBoolean()).isFalse();
        assertThat(okBody(get("/v1/goals/" + small)).get("status").asString()).isEqualTo("ACHIEVED");
        mockMvc.perform(get("/v1/goals?status=FOO")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        assertThat(big).isNotBlank();
    }

    private static List<String> names(JsonNode slice) {
        List<String> names = new ArrayList<>();
        slice.get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    @Test
    @DisplayName("CA6: PUT com a version atual edita; version velha → 409 VERSION_CONFLICT; meta arquivada → 422 GOAL_ARCHIVED; arquivar, desarquivar e os erros de estado")
    void deveEditarComVersaoEArquivar() throws Exception {
        String id = emergencia().get("id").asString();
        reserve(id, nubank, "1000.00");

        JsonNode edited = okBody(put("/v1/goals/" + id, "{\"name\":\"Viagem\",\"target\":\"5000.00\",\"priority\":\"LOW\",\"description\":null,\"deadline\":null,\"version\":1}"));
        assertThat(edited.get("name").asString()).isEqualTo("Viagem");
        assertThat(edited.get("version").asLong()).isEqualTo(2);
        assertThat(edited.get("monthlyNeeded").isNull()).isTrue();
        assertThat(edited.get("current").get("amount").asString()).as("as reservas não mudam").isEqualTo("1000.00");
        mockMvc.perform(put("/v1/goals/" + id, "{\"name\":\"Outra\",\"target\":\"5000.00\",\"priority\":\"LOW\",\"version\":1}")).andExpect(status().isConflict())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        assertThat(okBody(post("/v1/goals/" + id + "/archive", "")).get("status").asString()).isEqualTo("ARCHIVED");
        mockMvc.perform(post("/v1/goals/" + id + "/archive", "")).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("GOAL_ALREADY_ARCHIVED"));
        mockMvc.perform(put("/v1/goals/" + id, "{\"name\":\"X\",\"target\":\"1.00\",\"priority\":\"LOW\",\"version\":3}")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("GOAL_ARCHIVED"));
        mockMvc.perform(reservation(id, reservationBody(nubank, "1.00", "2026-10-10", null), UUID.randomUUID())).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("GOAL_ARCHIVED"));
        assertThat(okBody(post("/v1/goals/" + id + "/unarchive", "")).get("status").asString()).isEqualTo("ACTIVE");
        mockMvc.perform(post("/v1/goals/" + id + "/unarchive", "")).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("GOAL_NOT_ARCHIVED"));
    }

    @Test
    @DisplayName("regras da reserva: zero → 422 GOAL_RESERVATION_AMOUNT_ZERO; liberar mais do que o reservado → 422 GOAL_RELEASE_EXCEEDS_RESERVED; acima do teto → 422 AMOUNT_ABOVE_LIMIT; meta inexistente → 404")
    void deveAplicarAsRegrasDaReserva() throws Exception {
        String id = emergencia().get("id").asString();
        reserve(id, nubank, "100.00");

        mockMvc.perform(reservation(id, reservationBody(nubank, "0.00", "2026-10-10", null), UUID.randomUUID())).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("GOAL_RESERVATION_AMOUNT_ZERO"));
        mockMvc.perform(reservation(id, reservationBody(nubank, "-100.01", "2026-10-10", null), UUID.randomUUID())).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("GOAL_RELEASE_EXCEEDS_RESERVED"));
        mockMvc.perform(reservation(id, reservationBody(nubank, "9999999999999", "2026-10-10", null), UUID.randomUUID())).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("AMOUNT_ABOVE_LIMIT"));
        mockMvc.perform(reservation(UUID.randomUUID().toString(), reservationBody(nubank, "1.00", "2026-10-10", null), UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("GOAL_NOT_FOUND"));
        assertThat(okBody(get("/v1/goals/" + id)).get("current").get("amount").asString()).isEqualTo("100.00");
    }

    @Test
    @DisplayName("entrada inválida é 400: 3 casas, número JSON, campo desconhecido, header ausente, valor sem sinal válido e alvo zerado ou fora do formato")
    void deveValidarAEntrada() throws Exception {
        String id = emergencia().get("id").asString();
        UUID key = UUID.randomUUID();

        mockMvc.perform(reservation(id, reservationBody(nubank, "10.005", "2026-10-10", null), key)).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(reservation(id, "{\"accountId\":\"" + nubank + "\",\"amount\":10.5,\"date\":\"2026-10-10\"}", key)).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(reservation(id, "{\"accountId\":\"" + nubank + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"extra\":1}", key))
                .andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/goals/" + id + "/reservations", reservationBody(nubank, "10.00", "2026-10-10", null))).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(reservation(id, reservationBody(nubank, "--5", "2026-10-10", null), key)).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(reservation(id, "{\"amount\":\"10.00\",\"date\":\"2026-10-10\"}", key)).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(reservation(id, reservationBody(nubank, "10.00", "1999-01-01", null), key)).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/goals", "{\"name\":\"\",\"target\":\"1.00\",\"priority\":\"LOW\"}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/goals", "{\"name\":\"A\",\"target\":\"1.005\",\"priority\":\"LOW\"}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/goals", "{\"name\":\"A\",\"target\":\"1.00\",\"priority\":\"URGENT\"}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/goals", "{\"name\":\"A\",\"target\":\"0.00\",\"priority\":\"LOW\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("GOAL_TARGET_NOT_POSITIVE"));
        mockMvc.perform(put("/v1/goals/" + id, "{\"name\":\"A\",\"target\":\"1.00\",\"priority\":\"LOW\"}")).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("sem token as rotas de metas respondem 401")
    void deveExigirToken() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/goals")).andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/goals").contentType("application/json").content(EMERGENCIA))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- T94

    @Test
    @DisplayName("T94 CA1: Nubank 20.000, fatura de 1.283 vencendo 05/11 e reserva de 8.500 na Nubank: reserved 8.500,00 e available 10.217,00; sem meta, 18.717,00")
    void deveAbaterOReservadoDoDisponivel() throws Exception {
        purchasesOfTheStageThree();
        assertThat(money(summary().get("balances"), "available")).isEqualTo("18717.00");
        assertThat(money(summary().get("balances"), "reserved")).isEqualTo("0.00");
        String id = emergencia().get("id").asString();

        reserve(id, nubank, "8500.00");

        JsonNode balances = summary().get("balances");
        assertThat(money(balances, "reserved")).isEqualTo("8500.00");
        assertThat(money(balances, "available")).isEqualTo("10217.00");
        assertThat(money(balances, "committed30d")).isEqualTo("1283.00");
        assertThat(money(balances, "inAccounts")).isEqualTo("20000.00");
        reserve(id, nubank, "-8500.00");
        assertThat(money(summary().get("balances"), "available")).as("liberar devolve o disponível").isEqualTo("18717.00");
    }

    @Test
    @DisplayName("T94 CA2: reserva em conta INVESTMENT não muda o reservado do dashboard, mas conta no current da meta")
    void deveIgnorarReservaEmInvestimento() throws Exception {
        String invest = account("XP Invest", "INVESTMENT", "1000.00");
        String id = emergencia().get("id").asString();

        JsonNode goal = reserve(id, invest, "500.00");

        assertThat(goal.get("current").get("amount").asString()).isEqualTo("500.00");
        assertThat(money(summary().get("balances"), "reserved")).isEqualTo("0.00");
        assertThat(money(summary().get("balances"), "available")).isEqualTo("20000.00");
        JsonNode account = okBody(get("/v1/accounts/" + invest));
        assertThat(money(account, "reservedAmount")).isEqualTo("500.00");
        assertThat(account.get("uncovered").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("T94 CA3: reserva de 25.000 na Nubank com saldo de 20.000 é aceita: reserved 25.000, available negativo, Account.uncovered e Goal.uncovered true; nada é bloqueado")
    void deveAceitarESinalizarReservaDescoberta() throws Exception {
        String id = emergencia().get("id").asString();

        JsonNode goal = reserve(id, nubank, "25000.00");

        assertThat(goal.get("uncovered").asBoolean()).isTrue();
        assertThat(goal.get("status").asString()).isEqualTo("ACHIEVED");
        JsonNode balances = summary().get("balances");
        assertThat(money(balances, "reserved")).isEqualTo("25000.00");
        assertThat(money(balances, "available")).isEqualTo("-5000.00");
        JsonNode account = okBody(get("/v1/accounts/" + nubank));
        assertThat(account.get("uncovered").asBoolean()).isTrue();
        assertThat(money(account, "reservedAmount")).isEqualTo("25000.00");
        assertThat(money(account, "currentBalance")).isEqualTo("20000.00");
        assertThat(okBody(get("/v1/goals")).get("items").get(0).get("uncovered").asBoolean()).isTrue();
        assertThat(accountBalance()).isEqualTo("20000.00");
    }

    @Test
    @DisplayName("T94 CA4: com asOf no futuro o reservado usa as reservas com data até asOf")
    void deveUsarAsReservasAteAsOf() throws Exception {
        String id = emergencia().get("id").asString();
        okBody(reservation(id, reservationBody(nubank, "1000.00", "2026-10-10", null), UUID.randomUUID()));
        okBody(reservation(id, reservationBody(nubank, "200.00", "2026-11-20", null), UUID.randomUUID()));

        assertThat(money(okBody(get("/v1/dashboard/summary")).get("balances"), "reserved")).isEqualTo("1000.00");
        assertThat(money(okBody(get("/v1/dashboard/summary?asOf=2026-11-19")).get("balances"), "reserved")).isEqualTo("1000.00");
        JsonNode future = okBody(get("/v1/dashboard/summary?asOf=2026-11-20"));
        assertThat(money(future.get("balances"), "reserved")).isEqualTo("1200.00");
        assertThat(future.get("isProjection").asBoolean()).isTrue();
        assertThat(okBody(get("/v1/goals/" + id)).get("current").get("amount").asString()).as("o current de hoje não conta a reserva futura").isEqualTo("1000.00");
        assertThat(okBody(get("/v1/goals/" + id)).get("reservations")).hasSize(2);
    }

    @Test
    @DisplayName("T94 CA6: o saldo da conta (GET e lista) e o resumo concordam no reservado da conta; meta arquivada sai do reservado e desarquivar o devolve")
    void deveConcordarContaEResumo() throws Exception {
        String id = emergencia().get("id").asString();
        reserve(id, nubank, "8500.00");

        JsonNode account = okBody(get("/v1/accounts/" + nubank));
        JsonNode fromList = null;
        for (JsonNode item : okBody(get("/v1/accounts?pageSize=50")).get("items")) {
            if (item.get("id").asString().equals(nubank)) {
                fromList = item;
            }
        }

        assertThat(money(account, "reservedAmount")).isEqualTo(money(summary().get("balances"), "reserved")).isEqualTo("8500.00");
        assertThat(fromList).isNotNull();
        assertThat(money(fromList, "reservedAmount")).isEqualTo("8500.00");
        assertThat(fromList.get("uncovered").asBoolean()).isFalse();
        okBody(post("/v1/goals/" + id + "/archive", ""));
        assertThat(money(summary().get("balances"), "reserved")).isEqualTo("0.00");
        assertThat(money(okBody(get("/v1/accounts/" + nubank)), "reservedAmount")).isEqualTo("0.00");
        okBody(post("/v1/goals/" + id + "/unarchive", ""));
        assertThat(money(summary().get("balances"), "reserved")).isEqualTo("8500.00");
    }

    /** Reserva ou liberação do oráculo: meta, conta (índice), valor com sinal em centavos e data. */
    private record Entry(int goal, int account, long cents, java.time.LocalDate date) {
    }

    private static final java.time.LocalDate TODAY = java.time.LocalDate.of(2026, 10, 10);

    /** A regra S-247 do domínio, reescrita de forma independente: por meta e conta, o acumulado por data (reservas antes de liberações no dia) nunca fica negativo. */
    private static boolean releaseAllowed(List<Entry> entries, Entry release) {
        List<Entry> all = new ArrayList<>(entries.stream().filter(e -> e.goal == release.goal && e.account == release.account).toList());
        all.add(release);
        all.sort(java.util.Comparator.comparing(Entry::date).thenComparingInt(e -> e.cents < 0 ? 1 : 0));
        long running = 0;
        for (Entry e : all) {
            running += e.cents;
            if (running < 0) {
                return false;
            }
        }
        return true;
    }

    private static long net(List<Entry> entries, int goal, int account, java.time.LocalDate upTo) {
        return entries.stream().filter(e -> e.goal == goal && e.account == account && !e.date.isAfter(upTo)).mapToLong(Entry::cents).sum();
    }

    private static String cents(long cents) {
        return java.math.BigDecimal.valueOf(cents, 2).toPlainString();
    }

    @Test
    @DisplayName("T98 CA1 + M3 (propriedade): 400 operações aleatórias com datas passadas, de hoje e futuras (reservas, liberações acima do reservado, meta e conta arquivadas, conta INVESTMENT, despesas, receitas e transferências futuras) contra um oráculo independente: o razão fecha em zero, a reserva nunca move saldo, o que o oráculo recusa é recusado sem mudar o estado e reservado, disponível, projetado e uncovered batem")
    void deveManterORazaoEmZeroEm400OperacoesAleatorias() throws Exception {
        String[] accounts = {nubank, account("XP", "CHECKING", "0.00"), account("Parking", "CHECKING", "0.00"), account("Renda fixa", "INVESTMENT", "0.00")};
        boolean[] accountIsCash = {true, true, true, false};
        String[] goals = new String[3];
        boolean[] goalArchived = new boolean[3];
        for (int g = 0; g < goals.length; g++) {
            goals[g] = newGoal("{\"name\":\"Aleatória " + g + "\",\"target\":\"100000.00\",\"priority\":\"MEDIUM\"}").get("id").asString();
        }
        boolean parkingArchived = false;
        java.util.Random random = new java.util.Random(2026);
        List<Entry> entries = new ArrayList<>();
        long[] cash = {2_000_000L, 0L};
        List<long[]> cashMoves = new ArrayList<>(); // {conta (0 nubank, 1 xp), centavos com sinal, dia (offset de hoje), id da movimentação}
        long moveId = 0;
        long postingsExpected = count("ledger_postings");
        int rejected = 0;
        int accepted = 0;
        int futureMoves = 0;
        for (int i = 0; i < 400; i++) {
            int op = random.nextInt(100);
            long cents = 100 + random.nextInt(50_000);
            if (op < 35) { // reserva
                int g = random.nextInt(goals.length);
                int a = random.nextInt(accounts.length);
                java.time.LocalDate date = TODAY.plusDays(random.nextInt(41) - 20);
                String expectedCode = a == 2 && parkingArchived ? "ACCOUNT_ARCHIVED" : goalArchived[g] ? "GOAL_ARCHIVED" : null;
                JsonNode before = okBody(get("/v1/goals/" + goals[g]));
                MvcResult result = mockMvc.perform(reservation(goals[g], reservationBody(accounts[a], cents(cents), date.toString(), null), UUID.randomUUID())).andReturn();
                if (expectedCode == null) {
                    assertThat(result.getResponse().getStatus()).as("op %d reserva", i).isEqualTo(200);
                    entries.add(new Entry(g, a, cents, date));
                    accepted++;
                } else {
                    assertThat(result.getResponse().getStatus()).as("op %d reserva recusada", i).isEqualTo(422);
                    assertThat(body(result).get("code").asString()).isEqualTo(expectedCode);
                    assertThat(okBody(get("/v1/goals/" + goals[g])).get("version")).as("estado intacto").isEqualTo(before.get("version"));
                    rejected++;
                }
            } else if (op < 55) { // liberação, com valor às vezes acima do reservado
                int g = random.nextInt(goals.length);
                int a = random.nextInt(accounts.length);
                java.time.LocalDate date = TODAY.plusDays(random.nextInt(41) - 20);
                long reserved = Math.max(0, net(entries, g, a, java.time.LocalDate.MAX));
                long release = random.nextInt(3) == 0 ? cents : Math.max(1, Math.min(cents, reserved));
                Entry entry = new Entry(g, a, -release, date);
                String expectedCode = goalArchived[g] ? "GOAL_ARCHIVED" : !releaseAllowed(entries, entry) ? "GOAL_RELEASE_EXCEEDS_RESERVED" : null;
                JsonNode before = okBody(get("/v1/goals/" + goals[g]));
                MvcResult result = mockMvc.perform(reservation(goals[g], reservationBody(accounts[a], "-" + cents(release), date.toString(), null), UUID.randomUUID())).andReturn();
                if (expectedCode == null) {
                    assertThat(result.getResponse().getStatus()).as("op %d liberação", i).isEqualTo(200);
                    entries.add(entry);
                    accepted++;
                } else {
                    assertThat(result.getResponse().getStatus()).as("op %d liberação recusada", i).isEqualTo(422);
                    assertThat(body(result).get("code").asString()).isEqualTo(expectedCode);
                    assertThat(okBody(get("/v1/goals/" + goals[g])).get("version")).as("estado intacto").isEqualTo(before.get("version"));
                    rejected++;
                }
            } else if (op < 63) { // arquivar/desarquivar meta
                int g = random.nextInt(goals.length);
                okBody(post("/v1/goals/" + goals[g] + (goalArchived[g] ? "/unarchive" : "/archive"), ""));
                goalArchived[g] = !goalArchived[g];
            } else if (op < 71) { // arquivar/desarquivar a conta Parking (saldo sempre zero): só recusa com reserva ativa positiva
                if (parkingArchived) {
                    okBody(post("/v1/accounts/" + accounts[2] + "/unarchive", ""));
                    parkingArchived = false;
                } else {
                    boolean hasReserve = false;
                    for (int g = 0; g < goals.length; g++) {
                        hasReserve |= !goalArchived[g] && net(entries, g, 2, java.time.LocalDate.MAX) > 0;
                    }
                    MvcResult result = mockMvc.perform(post("/v1/accounts/" + accounts[2] + "/archive", "")).andExpect(ContractValidation.conforms()).andReturn();
                    if (hasReserve) {
                        assertThat(result.getResponse().getStatus()).as("op %d arquivar conta com reserva", i).isEqualTo(422);
                        assertThat(body(result).get("code").asString()).isEqualTo("ACCOUNT_HAS_RESERVATIONS");
                        rejected++;
                    } else {
                        assertThat(result.getResponse().getStatus()).as("op %d arquivar conta sem reserva", i).isEqualTo(200);
                        parkingArchived = true;
                        accepted++;
                    }
                }
            } else { // movimentação real, com data de 9 dias atrás a 45 dias à frente (S-258: as futuras são compromissos)
                int offset = random.nextInt(55) - 9;
                java.time.LocalDate date = TODAY.plusDays(offset);
                futureMoves += offset > 0 ? 1 : 0;
                int kind = random.nextInt(3);
                if (kind == 0) {
                    created("{\"type\":\"EXPENSE\",\"amount\":\"" + cents(cents) + "\",\"date\":\"" + date + "\",\"description\":\"Gasto " + i + "\",\"accountId\":\"" + nubank
                            + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}");
                    cashMoves.add(new long[] {0, -cents, offset, ++moveId});
                } else if (kind == 1) {
                    created("{\"type\":\"INCOME\",\"amount\":\"" + cents(cents) + "\",\"date\":\"" + date + "\",\"description\":\"Entrada " + i + "\",\"accountId\":\"" + nubank
                            + "\",\"categoryId\":\"" + salary + "\",\"method\":\"PIX\"}");
                    cashMoves.add(new long[] {0, cents, offset, ++moveId});
                } else {
                    created("{\"type\":\"TRANSFER\",\"amount\":\"" + cents(cents) + "\",\"date\":\"" + date + "\",\"description\":\"Transf " + i + "\",\"fromAccountId\":\"" + nubank
                            + "\",\"toAccountId\":\"" + accounts[1] + "\"}");
                    cashMoves.add(new long[] {0, -cents, offset, ++moveId});
                    cashMoves.add(new long[] {1, cents, offset, moveId});
                }
                postingsExpected += 2;
            }
            if (i % 20 == 19 || i == 399) {
                verifyRandomState(i, accounts, accountIsCash, goals, goalArchived, entries, cash, cashMoves);
            }
        }

        assertThat(ledgerTotal()).as("o razão fecha em zero").isZero();
        assertThat(count("ledger_postings")).as("a reserva não cria lançamento").isEqualTo(postingsExpected);
        assertThat(rejected).as("o teste recusou de verdade (variância)").isGreaterThan(20);
        assertThat(accepted).isGreaterThan(80);
        assertThat(futureMoves).as("houve movimentações futuras").isGreaterThan(20);
        assertThat(entries.stream().map(Entry::date).distinct().count()).as("datas variadas").isGreaterThan(15);
        assertThat(entries.stream().filter(e -> e.date.isAfter(TODAY)).count()).as("reservas futuras").isGreaterThan(10);
        assertThat(entries.stream().filter(e -> e.account == 3).count()).as("conta INVESTMENT").isGreaterThan(5);
    }

    private void verifyRandomState(int step, String[] accounts, boolean[] accountIsCash, String[] goals, boolean[] goalArchived, List<Entry> entries, long[] cash,
                                   List<long[]> cashMoves) throws Exception {
        long[] balance = {cash[0], cash[1]};
        for (long[] move : cashMoves) {
            if (move[2] <= 0) {
                balance[(int) move[0]] += move[1];
            }
        }
        // S-258: cada movimentação com data futura é UM compromisso pelo efeito líquido no caixa total (a transferência entre contas de caixa soma zero)
        java.util.Map<Long, long[]> futureById = new java.util.LinkedHashMap<>();
        for (long[] move : cashMoves) {
            if (move[2] > 0) {
                long[] net = futureById.computeIfAbsent(move[3], id -> new long[] {0, move[2]});
                net[0] += move[1];
            }
        }
        long futureOut30 = 0;
        long futureIn30 = 0;
        for (long[] net : futureById.values()) {
            if (net[1] <= 30) {
                if (net[0] < 0) {
                    futureOut30 += -net[0];
                } else {
                    futureIn30 += net[0];
                }
            }
        }
        long inAccounts = balance[0] + balance[1];
        long reservedCash = 0;
        for (int a = 0; a < accounts.length; a++) {
            long reserved = 0;
            for (int g = 0; g < goals.length; g++) {
                reserved += goalArchived[g] ? 0 : net(entries, g, a, TODAY);
            }
            JsonNode account = okBody(get("/v1/accounts/" + accounts[a]));
            assertThat(money(account, "reservedAmount")).as("passo %d: reservado da conta %d", step, a).isEqualTo(cents(reserved));
            if (a < 2) {
                assertThat(money(account, "currentBalance")).as("passo %d: saldo da conta %d", step, a).isEqualTo(cents(balance[a]));
                assertThat(account.get("uncovered").asBoolean()).as("passo %d: descoberta da conta %d", step, a).isEqualTo(reserved > balance[a]);
            } else {
                assertThat(account.get("uncovered").asBoolean()).as("passo %d: descoberta da conta %d", step, a).isEqualTo(reserved > 0);
            }
            if (accountIsCash[a]) {
                reservedCash += reserved;
            }
        }
        for (int g = 0; g < goals.length; g++) {
            JsonNode goal = okBody(get("/v1/goals/" + goals[g]));
            long current = 0;
            for (int a = 0; a < accounts.length; a++) {
                current += net(entries, g, a, TODAY);
            }
            assertThat(money(goal, "current")).as("passo %d: current da meta %d", step, g).isEqualTo(cents(current));
            assertThat(goal.get("reservations")).as("passo %d: reservas da meta %d", step, g).hasSize(countOf(entries, g));
        }
        JsonNode summary = summary();
        JsonNode balances = summary.get("balances");
        assertThat(money(balances, "inAccounts")).as("passo %d: em contas", step).isEqualTo(cents(inAccounts));
        assertThat(money(balances, "reserved")).as("passo %d: reservado", step).isEqualTo(cents(reservedCash));
        assertThat(money(summary.get("committed"), "d30")).as("passo %d: comprometido 30 dias (movimentações futuras, S-258)", step).isEqualTo(cents(futureOut30));
        assertThat(money(balances, "available")).as("passo %d: disponível", step).isEqualTo(cents(inAccounts - futureOut30 - reservedCash));
        assertThat(money(summary.get("projected"), "d30")).as("passo %d: projetado 30 dias", step).isEqualTo(cents(inAccounts + futureIn30 - futureOut30));
    }

    private static int countOf(List<Entry> entries, int goal) {
        return (int) entries.stream().filter(e -> e.goal == goal).count();
    }

    // ---------------------------------------------------------------- banco

    @Test
    @DisplayName("a migração V5.00: FK composta recusa reserva com conta de outro usuário; valor zero e moeda fora de BRL são recusados pelo banco")
    void deveReforcarAsRegrasNoBanco() throws Exception {
        String id = emergencia().get("id").asString();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "fk-" + other + "@moneylog.com"));
        UUID otherAccount = UUID.randomUUID();
        committed(() -> SqlFixtures.insertAccount(jdbc, com.moneylog.core.account.domain.Account.open(otherAccount, other, "Dele",
                com.moneylog.core.account.domain.AccountType.CHECKING, null, com.moneylog.core.sk.money.Money.of("1.00"), java.time.LocalDate.of(2026, 10, 1))));
        Timestamp now = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));
        String insert = "INSERT INTO goal_reservations (id, user_id, goal_id, account_id, amount_cents, currency, reserved_on, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), userId, UUID.fromString(id), otherAccount, 100L, "BRL", Date.valueOf("2026-10-10"), now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), other, UUID.fromString(id), otherAccount, 100L, "BRL", Date.valueOf("2026-10-10"), now, now))
                .as("meta de outro usuário").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), userId, UUID.fromString(id), UUID.fromString(nubank), 0L, "BRL", Date.valueOf("2026-10-10"), now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), userId, UUID.fromString(id), UUID.fromString(nubank), 100L, "USD", Date.valueOf("2026-10-10"), now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO goals (id, user_id, name, target_cents, currency, priority, archived, created_at, updated_at, version) VALUES (?, ?, ' ', 100, 'BRL', 'LOW', false, ?, ?, 0)",
                UUID.randomUUID(), userId, now, now)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO goals (id, user_id, name, target_cents, currency, priority, archived, created_at, updated_at, version) VALUES (?, ?, 'a', 0, 'BRL', 'LOW', false, ?, ?, 0)",
                UUID.randomUUID(), userId, now, now)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO goals (id, user_id, name, target_cents, currency, priority, archived, created_at, updated_at, version) VALUES (?, ?, 'a', 10, 'BRL', 'URGENT', false, ?, ?, 0)",
                UUID.randomUUID(), userId, now, now)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("goal_reservations")).isZero();
    }
}
