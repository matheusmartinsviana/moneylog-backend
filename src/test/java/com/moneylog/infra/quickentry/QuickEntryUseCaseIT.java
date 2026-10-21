package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.quickentry.app.CreateQuickEntryCommand;
import com.moneylog.core.quickentry.app.CreateQuickEntryResult;
import com.moneylog.core.quickentry.domain.QuickAmountInput;
import com.moneylog.core.quickentry.domain.QuickEntryKind;
import com.moneylog.core.quickentry.exception.QuickEntryErrorCodes;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;
import com.moneylog.util.ContractValidation;

@DisplayName("Lançamento rápido: caso de uso com banco real (T5, PR 01 e idempotência)")
class QuickEntryUseCaseIT extends QuickEntryItBase {

    @Test
    @DisplayName("R$ 100 de gasto em Alimentação: caixa -100, consumo +100, patrimônio -100, saldo 900; as três visões concordam")
    void deveFecharOCasoDoGasto() throws Exception {
        World w = world();
        BigDecimal netWorthBefore = netWorth(w);

        CreateQuickEntryResult result = quick.handle(expense(w, "20261021120000", "100", "Alimentação", null));

        UUID id = result.details().transaction().id();
        assertThat(result.message()).isEqualTo("Gasto de R$ 100,00 em Alimentação (Carteira) registrado.");
        assertThat(balance(w)).isEqualByComparingTo("900.00");
        assertThat(netWorth(w).subtract(netWorthBefore)).isEqualByComparingTo("-100.00");

        JsonNode transaction = get(w.bearer(), "/v1/transactions/" + id);
        assertThat(transaction.get("source").asString()).isEqualTo("QUICK_SHORTCUT");
        assertThat(transaction.get("category").get("id").asString()).isEqualTo(w.food().toString());
        assertThat(new BigDecimal(transaction.get("impact").get("cash").get("amount").asString())).isEqualByComparingTo("-100.00");
        assertThat(new BigDecimal(transaction.get("impact").get("netWorth").get("amount").asString())).isEqualByComparingTo("-100.00");
        assertThat(transaction.get("impact").get("consumption")).hasSize(1);
        assertThat(new BigDecimal(transaction.get("impact").get("consumption").get(0).get("amount").get("amount").asString())).isEqualByComparingTo("100.00");

        JsonNode entries = get(w.bearer(), "/v1/entries?competence=2026-10");
        assertThat(new BigDecimal(entries.get("totals").get("total").get("amount").asString())).isEqualByComparingTo("100.00");
        assertThat(entries.get("totals").get("byCategory").get(0).get("category").get("name").asString()).isEqualTo("Alimentação");
        JsonNode listed = get(w.bearer(), "/v1/transactions");
        assertThat(new BigDecimal(listed.get("totals").get("cashFlow").get("out").get("amount").asString())).isEqualByComparingTo("100.00");
        assertThat(count("quick_entry_audit", w.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select token_id from quick_entry_audit where user_id = ?", UUID.class, w.userId())).isEqualTo(w.tokenId());
        assertThat(jdbc.queryForObject("select ip from quick_entry_audit where user_id = ?", String.class, w.userId())).isEqualTo("203.0.113.0");
    }

    @Test
    @DisplayName("entrada de 2500,00 em Salário: caixa +2500 e receita na competência da date; sem consumo e sem patrimônio duplicado")
    void deveFecharOCasoDaEntrada() throws Exception {
        World w = world();
        BigDecimal netWorthBefore = netWorth(w);

        CreateQuickEntryResult result = quick.handle(income(w, "20261021120001", "2500,00", "Salário"));

        assertThat(result.message()).isEqualTo("Entrada de R$ 2.500,00 em Salário (Carteira) registrada.");
        assertThat(balance(w)).isEqualByComparingTo("3500.00");
        assertThat(netWorth(w).subtract(netWorthBefore)).isEqualByComparingTo("2500.00");
        JsonNode transaction = get(w.bearer(), "/v1/transactions/" + result.details().transaction().id());
        assertThat(new BigDecimal(transaction.get("impact").get("cash").get("amount").asString())).isEqualByComparingTo("2500.00");
        assertThat(transaction.get("impact").get("consumption")).isEmpty();
        assertThat(transaction.get("date").asString()).startsWith("2026-10");
        assertThat(result.details().transaction().effectiveIncomeCompetence().toString()).isEqualTo("2026-10");
        JsonNode entries = get(w.bearer(), "/v1/entries?competence=2026-10");
        assertThat(new BigDecimal(entries.get("totals").get("total").get("amount").asString())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("gasto maior que o saldo tem o mesmo resultado de POST /v1/transactions na mesma conta")
    void deveSeguirAMesmaRegraDoLancamentoComum() throws Exception {
        World w = world();
        World twin = world();

        quick.handle(expense(w, "20261021120002", "5.000,00", "FOOD", null));
        post(twin.bearer(), "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"5000.00\",\"date\":\"2026-10-21\",\"accountId\":\"" + twin.accountId()
                + "\",\"categoryId\":\"" + twin.food() + "\",\"method\":\"OTHER\"}", UUID.randomUUID().toString(), 201);

        assertThat(balance(w)).isEqualByComparingTo(balance(twin)).isEqualByComparingTo("-4000.00");
    }

    @Test
    @DisplayName("estorno pelo fluxo normal devolve saldo e consumo; replay devolve o mesmo id com VOIDED e mensagem de estornado, sem recriar")
    void deveEstornarEFazerReplayComoVoided() throws Exception {
        World w = world();
        CreateQuickEntryResult created = quick.handle(expense(w, "20261021120003", "100", "FOOD", null));
        UUID id = created.details().transaction().id();

        post(w.bearer(), "/v1/transactions/" + id + "/void", "{\"reason\":\"teste\"}", null, 200);

        assertThat(balance(w)).isEqualByComparingTo("1000.00");
        assertThat(new BigDecimal(get(w.bearer(), "/v1/entries?competence=2026-10").get("totals").get("total").get("amount").asString()))
                .isEqualByComparingTo("0.00");
        CreateQuickEntryResult replay = quick.handle(expense(w, "20261021120003", "100", "FOOD", null));
        assertThat(replay.details().transaction().id()).isEqualTo(id);
        assertThat(replay.details().transaction().status().name()).isEqualTo("VOIDED");
        assertThat(replay.message()).isEqualTo("Gasto de R$ 100,00 em Alimentação (Carteira) registrado e depois estornado.");
        assertThat(count("transactions", w.userId())).isEqualTo(1);
        assertThat(count("quick_entry_audit", w.userId())).isEqualTo(1);
        assertThat(balance(w)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("filtro source: QUICK_SHORTCUT traz só o do canal; MANUAL traz só o manual; sem filtro traz os dois; conforme o contrato")
    void deveFiltrarPorOrigem() throws Exception {
        World w = world();
        CreateQuickEntryResult created = quick.handle(expense(w, "20261021120004", "10", "FOOD", null));
        post(w.bearer(), "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"20.00\",\"date\":\"2026-10-21\",\"accountId\":\"" + w.accountId()
                + "\",\"categoryId\":\"" + w.food() + "\",\"method\":\"PIX\"}", UUID.randomUUID().toString(), 201);

        mockMvc.perform(MockMvcRequestBuilders.get("/v1/transactions?source=QUICK_SHORTCUT").header(HttpHeaders.AUTHORIZATION, w.bearer()))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms());
        JsonNode quickOnly = get(w.bearer(), "/v1/transactions?source=QUICK_SHORTCUT");
        assertThat(quickOnly.get("items")).hasSize(1);
        assertThat(quickOnly.get("items").get(0).get("id").asString()).isEqualTo(created.details().transaction().id().toString());
        assertThat(new BigDecimal(quickOnly.get("totals").get("cashFlow").get("out").get("amount").asString())).isEqualByComparingTo("10.00");
        JsonNode manual = get(w.bearer(), "/v1/transactions?source=MANUAL");
        assertThat(manual.get("items")).hasSize(1);
        assertThat(manual.get("items").get(0).get("source").asString()).isEqualTo("MANUAL");
        assertThat(get(w.bearer(), "/v1/transactions").get("items")).hasSize(2);
    }

    @Test
    @DisplayName("20 envios em paralelo com a mesma chave: uma movimentação, uma auditoria, uma chave, mesmo id e mesma mensagem")
    void deveSerializarEnviosEmParalelo() throws Exception {
        World w = world();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CreateQuickEntryResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return quick.handle(expense(w, "paralela-0001", "27,60", "FOOD", null));
            }));
        }
        start.countDown();
        List<CreateQuickEntryResult> results = new ArrayList<>();
        for (Future<CreateQuickEntryResult> future : futures) {
            results.add(future.get());
        }
        pool.shutdown();

        assertThat(results).extracting(r -> r.details().transaction().id()).containsOnly(results.get(0).details().transaction().id());
        assertThat(results).extracting(CreateQuickEntryResult::message).containsOnly(results.get(0).message());
        assertThat(count("transactions", w.userId())).isEqualTo(1);
        assertThat(count("quick_entry_audit", w.userId())).isEqualTo(1);
        assertThat(idempotencyRows(w.userId())).isEqualTo(1);
        assertThat(balance(w)).isEqualByComparingTo("972.40");
    }

    @Test
    @DisplayName("sem date, reenviado depois da meia-noite do fuso do usuário: é replay do mesmo id, não 409")
    void deveSerReplayDepoisDaMeiaNoite() throws Exception {
        World w = world();
        clock.set(Instant.parse("2026-10-21T02:30:00Z"));
        CreateQuickEntryResult first = quick.handle(expense(w, "meia-noite-01", "10", "FOOD", null));
        assertThat(first.details().transaction().date()).isEqualTo(LocalDate.of(2026, 10, 20));

        clock.set(Instant.parse("2026-10-21T15:00:00Z"));
        CreateQuickEntryResult replay = quick.handle(expense(w, "meia-noite-01", "10", "FOOD", null));

        assertThat(replay.details().transaction().id()).isEqualTo(first.details().transaction().id());
        assertThat(count("transactions", w.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("date omitida usa o fuso do usuário gravado em users.time_zone")
    void deveUsarOFusoDoUsuario() throws Exception {
        World auckland = world("Pacific/Auckland");
        clock.set(Instant.parse("2026-10-21T20:00:00Z"));

        CreateQuickEntryResult result = quick.handle(expense(auckland, "fuso-nz-0001", "10", "FOOD", null));

        assertThat(result.details().transaction().date()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThatThrownBy(() -> quick.handle(expense(auckland, "fuso-nz-0002", "10", "FOOD", LocalDate.of(2026, 10, 23))))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_DATE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("mesma chave com input diferente: 409 e nada gravado a mais")
    void deveRecusarChaveReutilizada() throws Exception {
        World w = world();
        quick.handle(expense(w, "reuso-chave-01", "10", "FOOD", null));

        assertThatThrownBy(() -> quick.handle(expense(w, "reuso-chave-01", "11", "FOOD", null))).isInstanceOf(IdempotencyKeyReusedException.class)
                .hasFieldOrPropertyWithValue("code", "IDEMPOTENCY_KEY_REUSED");
        assertThat(count("transactions", w.userId())).isEqualTo(1);
        assertThat(count("quick_entry_audit", w.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("chave não-UUID deduplica mesmo com outro token do mesmo usuário; UUID também é aceito")
    void deveDeduplicarComOutroToken() throws Exception {
        World w = world();
        UUID otherToken = newToken(w.userId(), w.accountId());
        CreateQuickEntryResult first = quick.handle(expense(w, "chave-opaca-01", "10", "FOOD", null));
        CreateQuickEntryCommand again = new CreateQuickEntryCommand(w.userId(), otherToken, w.accountId(), "chave-opaca-01", QuickEntryKind.EXPENSE,
                new QuickAmountInput.Text("10"), "FOOD", "Almoço", null, null, "198.51.100.9");

        CreateQuickEntryResult replay = quick.handle(again);

        assertThat(replay.details().transaction().id()).isEqualTo(first.details().transaction().id());
        assertThat(count("quick_entry_audit", w.userId())).isEqualTo(1);
        String uuidKey = UUID.randomUUID().toString();
        assertThat(quick.handle(expense(w, uuidKey, "5", "FOOD", null)).details().transaction().id())
                .isEqualTo(quick.handle(expense(w, uuidKey, "5", "FOOD", null)).details().transaction().id());
    }

    @Test
    @DisplayName("erro de negócio não memoriza a chave: reenviada corrigida, passa")
    void deveAceitarAChaveDepoisDeErro() throws Exception {
        World w = world();

        assertThatThrownBy(() -> quick.handle(expense(w, "corrige-0001", "10", "Viagem", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_CATEGORY_NOT_FOUND);
        assertThat(idempotencyRows(w.userId())).isZero();
        assertThat(count("transactions", w.userId())).isZero();

        assertThat(quick.handle(expense(w, "corrige-0001", "10", "Lazer", null)).details().category().id()).isEqualTo(w.leisure());
        assertThat(idempotencyRows(w.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("rollback conjunto: se a auditoria falha, o lançamento, o ledger e a chave de idempotência também voltam")
    void deveDesfazerTudoSeAAuditoriaFalha() throws Exception {
        World w = world();
        CreateQuickEntryCommand withUnknownToken = new CreateQuickEntryCommand(w.userId(), UUID.randomUUID(), w.accountId(), "rollback-0001",
                QuickEntryKind.EXPENSE, new QuickAmountInput.Text("100"), "FOOD", null, null, null, null);

        assertThatThrownBy(() -> quick.handle(withUnknownToken)).isInstanceOf(RuntimeException.class);

        assertThat(count("transactions", w.userId())).isZero();
        assertThat(count("quick_entry_audit", w.userId())).isZero();
        assertThat(idempotencyRows(w.userId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_postings where user_id = ? and transaction_id is not null", Long.class, w.userId())).isZero();
        assertThat(balance(w)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("category omitida cai em Outros/Outras receitas e a resposta traz a categoria aplicada")
    void deveAplicarACategoriaPadrao() throws Exception {
        World w = world();

        CreateQuickEntryResult expense = quick.handle(expense(w, "padrao-0001", "12,90", null, null));
        CreateQuickEntryResult income = quick.handle(income(w, "padrao-0002", "100", null));

        assertThat(expense.details().category().id()).isEqualTo(w.otherExpense());
        assertThat(expense.message()).isEqualTo("Gasto de R$ 12,90 em Outros (Carteira) registrado.");
        assertThat(income.details().category().id()).isEqualTo(w.otherIncome());
    }

    @Test
    @DisplayName("sem a categoria padrão: DEFAULT_CATEGORY_UNAVAILABLE e nada gravado")
    void deveFalharSemACategoriaPadrao() throws Exception {
        World w = world();
        committed(() -> jdbc.update("update categories set archived = true where id = ?", w.otherExpense()));

        assertThatThrownBy(() -> quick.handle(expense(w, "padrao-0003", "12,90", null, null)))
                .hasFieldOrPropertyWithValue("code", "DEFAULT_CATEGORY_UNAVAILABLE");
        assertThat(count("transactions", w.userId())).isZero();
        assertThat(idempotencyRows(w.userId())).isZero();
    }

    @Test
    @DisplayName("categoria arquivada ou de outro usuário: QUICK_CATEGORY_NOT_FOUND")
    void deveRecusarCategoriaArquivadaOuAlheia() throws Exception {
        World w = world();
        World other = world();
        committed(() -> jdbc.update("update categories set archived = true where id = ?", w.leisure()));

        assertThatThrownBy(() -> quick.handle(expense(w, "alheia-00001", "10", "Lazer", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_CATEGORY_NOT_FOUND);
        assertThat(other.leisure()).isNotEqualTo(w.leisure());
        assertThat(quick.handle(expense(other, "alheia-00002", "10", "Lazer", null)).details().category().id()).isEqualTo(other.leisure());
    }

    @Test
    @DisplayName("dois usuários com contas e categorias de mesmo nome: cada um cai só nos próprios dados; conta alheia no comando é 404")
    void deveIsolarUsuarios() throws Exception {
        World a = world();
        World b = world();

        CreateQuickEntryResult ra = quick.handle(expense(a, "isolamento-01", "10", "Alimentação", null));
        CreateQuickEntryResult rb = quick.handle(expense(b, "isolamento-01", "20", "Alimentação", null));

        assertThat(ra.details().transaction().accountId()).isEqualTo(a.accountId());
        assertThat(rb.details().transaction().accountId()).isEqualTo(b.accountId());
        assertThat(ra.details().category().id()).isEqualTo(a.food());
        assertThat(rb.details().category().id()).isEqualTo(b.food());
        assertThat(balance(a)).isEqualByComparingTo("990.00");
        assertThat(balance(b)).isEqualByComparingTo("980.00");
        CreateQuickEntryCommand crossed = new CreateQuickEntryCommand(a.userId(), a.tokenId(), b.accountId(), "isolamento-02", QuickEntryKind.EXPENSE,
                new QuickAmountInput.Text("1"), "FOOD", null, null, null, null);
        assertThatThrownBy(() -> quick.handle(crossed)).isInstanceOf(AccountNotFoundException.class);
        assertThat(balance(b)).isEqualByComparingTo("980.00");
    }

    @Test
    @DisplayName("conta do token arquivada depois: QUICK_ACCOUNT_NOT_ALLOWED e nada gravado")
    void deveRecusarContaArquivada() throws Exception {
        World w = world();
        committed(() -> jdbc.update("update accounts set status = 'ARCHIVED' where id = ?", w.accountId()));

        assertThatThrownBy(() -> quick.handle(expense(w, "arquivada-001", "10", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_ACCOUNT_NOT_ALLOWED);
        assertThat(count("transactions", w.userId())).isZero();
    }

    @Test
    @DisplayName("sem nenhum teto configurado (padrão): muitos lançamentos e valor até o limite técnico são aceitos; date variando não importa")
    void deveAceitarSemTetos() throws Exception {
        World w = world();
        for (int i = 0; i < 40; i++) {
            quick.handle(expense(w, "sem-teto-%04d".formatted(i), "999.999,00", "FOOD", LocalDate.of(2026, 10, 21).minusDays(i * 9L)));
        }
        quick.handle(expense(w, "sem-teto-max1", "999.999.999,99", "FOOD", null));

        assertThat(count("transactions", w.userId())).isEqualTo(41);
        assertThat(count("quick_entry_audit", w.userId())).isEqualTo(41);
    }

    @Test
    @DisplayName("valor acima do limite técnico, ambíguo ou ilegível: erro do parser e nada gravado")
    void deveRecusarValoresRuins() throws Exception {
        World w = world();

        assertThatThrownBy(() -> quick.handle(expense(w, "ruim-0000001", "1.000.000.000,00", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_AMOUNT_INVALID);
        assertThatThrownBy(() -> quick.handle(expense(w, "ruim-0000002", "1.234", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_AMOUNT_AMBIGUOUS);
        assertThat(count("transactions", w.userId())).isZero();
        assertThat(idempotencyRows(w.userId())).isZero();
    }

    @Test
    @DisplayName("amount como número JSON lido exato: 27.6 vira 27,60")
    void deveLerNumeroJsonExato() throws Exception {
        World w = world();
        CreateQuickEntryCommand command = new CreateQuickEntryCommand(w.userId(), w.tokenId(), w.accountId(), "numero-000001", QuickEntryKind.EXPENSE,
                new QuickAmountInput.JsonNumber("27.6"), "FOOD", null, null, null, null);

        CreateQuickEntryResult result = quick.handle(command);

        assertThat(result.details().transaction().amount().toPlainString()).isEqualTo("27.60");
        assertThat(balance(w)).isEqualByComparingTo("972.40");
    }
}
