package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/**
 * Consumo do mês por categoria (redesign, S-286): o mesmo {@link EntryReport} da lente {@link EntryLens#CONSUMPTION} de
 * {@code GET /v1/entries} (por isso {@code total} = {@code entries.totals.total} = {@code month.consumption} do dashboard, por construção).
 *
 * <ul>
 *   <li>{@code total} = consumo líquido do mês (cabeçalho; o reembolso de outro mês pode deixar uma categoria negativa);</li>
 *   <li>{@code base} = Σ das categorias com valor líquido &gt; 0 (itens + {@code other}): é o divisor do {@code share}, para a soma dos
 *       percentuais nunca passar de 100% quando há categoria negativa;</li>
 *   <li>{@code items} = as {@code limit} maiores categorias positivas, em ordem decrescente (empate por nome, depois id);</li>
 *   <li>{@code other} = soma das demais categorias positivas (não soma de novo o que já está nos itens), {@code null} se não houver;</li>
 *   <li>{@code share} = valor ÷ {@code base} com 4 casas ({@code HALF_UP}, o {@code ratio} do {@link MonthlySummary}), arredondado aqui: a
 *       soma dos {@code share} pode ficar até {@code (itens + 1) × 0,00005} longe de 1 e ninguém a corrige.</li>
 * </ul>
 */
public record SpendingByCategory(Competence competence, Money total, Money base, List<Item> items, Other other) {

    public record Item(UUID categoryId, Money amount, BigDecimal share) {
    }

    public record Other(Money amount, BigDecimal share, int categories) {
    }

    /**
     * @param consumption lente de consumo da competência ({@link EntryReport#of(FinancialRecords, Ledger, EntryLens, Competence)})
     * @param limit       de 1 a 10 itens antes de {@code other}
     * @param nameOf      nome da categoria, só para o desempate
     */
    public static SpendingByCategory of(EntryReport consumption, int limit, Function<UUID, String> nameOf) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit precisa ser ao menos 1");
        }
        Comparator<Map.Entry<UUID, Money>> order = Comparator.<Map.Entry<UUID, Money>, Money>comparing(Map.Entry::getValue).reversed()
                .thenComparing(e -> String.valueOf(nameOf.apply(e.getKey())).toLowerCase(java.util.Locale.ROOT))
                .thenComparing(Map.Entry::getKey);
        List<Map.Entry<UUID, Money>> positives = consumption.byCategory().entrySet().stream()
                .filter(e -> e.getValue().isPositive()).sorted(order).toList();
        Money zero = Money.zero(CurrencyCode.BRL);
        Money base = positives.stream().map(Map.Entry::getValue).reduce(zero, Money::add);
        List<Item> items = new ArrayList<>();
        for (Map.Entry<UUID, Money> e : positives.subList(0, Math.min(limit, positives.size()))) {
            items.add(new Item(e.getKey(), e.getValue(), share(e.getValue(), base)));
        }
        List<Map.Entry<UUID, Money>> rest = positives.subList(items.size(), positives.size());
        Other other = null;
        if (!rest.isEmpty()) {
            Money amount = rest.stream().map(Map.Entry::getValue).reduce(zero, Money::add);
            other = new Other(amount, share(amount, base), rest.size());
        }
        return new SpendingByCategory(consumption.competence(), consumption.total(), base, List.copyOf(items), other);
    }

    private static BigDecimal share(Money amount, Money base) {
        return base.isPositive() ? MonthlySummary.ratio(amount, base) : null;
    }
}
