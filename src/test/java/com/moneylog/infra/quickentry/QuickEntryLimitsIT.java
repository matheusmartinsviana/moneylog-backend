package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.moneylog.core.quickentry.app.CreateQuickEntryResult;
import com.moneylog.core.quickentry.exception.QuickEntryErrorCodes;

@TestPropertySource(properties = {
        "moneylog.quick-entry.max-amount=1000.00",
        "moneylog.quick-entry.max-entries-per-day=5",
        "moneylog.quick-entry.max-expense-per-day=1500.00",
        "moneylog.quick-entry.max-income-per-day=800.00"})
@DisplayName("Lançamento rápido com tetos ligados (T5, Q7)")
class QuickEntryLimitsIT extends QuickEntryItBase {

    @Test
    @DisplayName("teto por lançamento: no teto passa, um centavo acima é QUICK_LIMIT_REACHED e nada é gravado")
    void deveAplicarOTetoPorLancamento() throws Exception {
        World w = world();

        quick.handle(expense(w, "limite-valor-1", "1.000,00", "FOOD", null));
        assertThatThrownBy(() -> quick.handle(expense(w, "limite-valor-2", "1.000,01", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED);
        assertThat(count("transactions", w.userId())).isEqualTo(1);
        assertThat(idempotencyRows(w.userId())).isEqualTo(1);
    }

    @Test
    @DisplayName("teto de quantidade conta pelo instante de criação, não pela date: 5 lançamentos com date variando esgotam o dia")
    void deveContarPeloInstanteDeCriacao() throws Exception {
        World w = world();
        for (int i = 0; i < 5; i++) {
            quick.handle(expense(w, "limite-qtd-%02d".formatted(i), "1,00", "FOOD", LocalDate.of(2026, 10, 21).minusDays(i * 70L)));
        }

        assertThatThrownBy(() -> quick.handle(expense(w, "limite-qtd-99", "1,00", "FOOD", LocalDate.of(2026, 10, 21).minusDays(300))))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED).hasMessageContaining("5 lançamentos");
        assertThat(count("transactions", w.userId())).isEqualTo(5);
    }

    @Test
    @DisplayName("o dia vira à meia-noite do fuso do usuário: no dia seguinte o contador zera; estornado libera a vaga")
    void deveVirarODiaNoFusoDoUsuario() throws Exception {
        World w = world();
        clock.set(Instant.parse("2026-10-21T02:50:00Z"));
        List<CreateQuickEntryResult> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(quick.handle(expense(w, "limite-dia-%02d".formatted(i), "1,00", "FOOD", null)));
        }
        assertThatThrownBy(() -> quick.handle(expense(w, "limite-dia-99", "1,00", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED);

        post(w.bearer(), "/v1/transactions/" + created.get(0).details().transaction().id() + "/void", "{\"reason\":\"teste\"}", null, 200);
        quick.handle(expense(w, "limite-dia-98", "1,00", "FOOD", null));

        clock.set(Instant.parse("2026-10-21T03:10:00Z"));
        for (int i = 0; i < 5; i++) {
            quick.handle(expense(w, "limite-novo-%02d".formatted(i), "1,00", "FOOD", null));
        }
        assertThat(count("transactions", w.userId())).isEqualTo(5 + 1 + 5);
    }

    @Test
    @DisplayName("teto de valor do dia é separado por tipo: gastos não bloqueiam entradas")
    void deveSepararGastoEEntrada() throws Exception {
        World w = world();
        quick.handle(expense(w, "limite-tipo-01", "1.000,00", "FOOD", null));
        quick.handle(expense(w, "limite-tipo-02", "500,00", "FOOD", null));

        assertThatThrownBy(() -> quick.handle(expense(w, "limite-tipo-03", "0,01", "FOOD", null)))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED).hasMessageContaining("gastos");
        quick.handle(income(w, "limite-tipo-04", "800,00", "Salário"));
        assertThatThrownBy(() -> quick.handle(income(w, "limite-tipo-05", "0,01", "Salário")))
                .hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED).hasMessageContaining("entradas");
    }

    @Test
    @DisplayName("requisições paralelas perto do teto são serializadas por usuário: nunca passam de 5 lançamentos nem de R$ 1.500,00")
    void deveSerializarEmParaleloComTeto() throws Exception {
        World w = world();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "paralelo-lim-%02d".formatted(i);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    quick.handle(expense(w, key, "400,00", "FOOD", null));
                    return true;
                } catch (RuntimeException e) {
                    assertThat(e).hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED);
                    return false;
                }
            }));
        }
        start.countDown();
        long accepted = 0;
        for (Future<Boolean> future : futures) {
            try {
                accepted += future.get() ? 1 : 0;
            } catch (ExecutionException e) {
                throw e;
            }
        }
        pool.shutdown();

        assertThat(accepted).isEqualTo(3);
        assertThat(count("transactions", w.userId())).isEqualTo(3);
        assertThat(count("quick_entry_audit", w.userId())).isEqualTo(3);
        assertThat(balance(w)).isEqualByComparingTo("-200.00");
    }

    @Test
    @DisplayName("requisições paralelas perto do teto de quantidade: nunca passam de 5 lançamentos")
    void deveSerializarAQuantidadeEmParalelo() throws Exception {
        World w = world();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "paralelo-qtd-%02d".formatted(i);
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    quick.handle(expense(w, key, "1,00", "FOOD", null));
                    return true;
                } catch (RuntimeException e) {
                    assertThat(e).hasFieldOrPropertyWithValue("code", QuickEntryErrorCodes.QUICK_LIMIT_REACHED);
                    return false;
                }
            }));
        }
        start.countDown();
        long accepted = 0;
        for (Future<Boolean> future : futures) {
            accepted += future.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(accepted).isEqualTo(5);
        assertThat(count("transactions", w.userId())).isEqualTo(5);
        assertThat(count("quick_entry_audit", w.userId())).isEqualTo(5);
    }
}
