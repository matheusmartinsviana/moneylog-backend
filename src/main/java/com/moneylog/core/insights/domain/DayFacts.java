package com.moneylog.core.insights.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

/**
 * Tudo que os insights do dia leem (função pura sobre este registro). {@code date} é o dia de referência no fuso do usuário; a aritmética é
 * sempre de {@code LocalDate} (nunca 24 h em milissegundos), então a virada de mês e o fuso não deslocam o dia.
 *
 * @param budget          base do orçamento do dia (a mesma do dinheiro livre mensal)
 * @param variableBefore  gasto variável de cada dia, do dia 1 até ontem
 * @param variableToday   gasto variável de hoje
 * @param dueWeek         saídas com vencimento de hoje a hoje + 7 (itens de primeiro nível: a parcela já está dentro da fatura)
 * @param events          fluxo conhecido dos próximos 30 dias (entradas só de recorrência e lançamento futuro; nunca a receber de terceiro)
 * @param todayEntries    lançamentos de consumo de hoje (sem parcela nem dívida)
 * @param categoryHistory valores positivos dos lançamentos por categoria nos 6 meses fechados
 * @param closings        faturas abertas e a data em que fecham
 */
public record DayFacts(LocalDate date, MonthFacts.Budget budget, List<Money> variableBefore, Money variableToday, Money cashBalance,
                       boolean hasCashAccounts, List<Due> dueWeek, List<CashEvent> events, List<TodayEntry> todayEntries,
                       Map<UUID, List<Money>> categoryHistory, Map<UUID, String> categoryNames, List<Closing> closings) {

    public DayFacts {
        Objects.requireNonNull(date, "date");
        variableBefore = List.copyOf(variableBefore);
        dueWeek = List.copyOf(dueWeek);
        events = List.copyOf(events);
        todayEntries = List.copyOf(todayEntries);
        categoryHistory = Map.copyOf(categoryHistory);
        categoryNames = Map.copyOf(categoryNames);
        closings = List.copyOf(closings);
    }

    /** Saída com vencimento na semana; {@code ref} identifica a fatura (para deduplicar com o fechamento). */
    public record Due(LocalDate dueDate, Money amount, String ref) {
    }

    /** Variação conhecida do saldo numa data (negativa = saída). Datas até hoje (vencidos, hoje) entram no dia zero. */
    public record CashEvent(LocalDate date, Money signedAmount) {
    }

    public record TodayEntry(String subjectId, UUID categoryId, Money amount) {
    }

    public record Closing(String ref, String cardName, LocalDate closingDate, Money partial) {
    }

    public int dayOfMonth() {
        return date.getDayOfMonth();
    }

    public int daysInMonth() {
        return date.lengthOfMonth();
    }
}
