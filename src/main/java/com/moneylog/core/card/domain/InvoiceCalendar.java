package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.card.exception.CardExceptions.CardFieldRequiredException;

/**
 * Calendário de faturas de um cartão (D1 §2.5.1). Há exatamente um fechamento por mês: o do dia de fechamento da
 * regra vigente naquele mês, com clamp. Como as regras são por mês (S-42), as datas de fechamento são sempre
 * estritamente crescentes, uma por mês.
 *
 * <ol>
 *   <li>A compra pertence à fatura cujo fechamento é o primeiro <b>estritamente posterior</b> à data (S-12).</li>
 *   <li>Vencimento no mesmo mês do fechamento se o dia de vencimento &gt; dia de fechamento; senão, no mês seguinte.
 *       Se o clamp levar o vencimento para o dia do fechamento (ou antes), ele vai para o dia de vencimento do mês
 *       seguinte, também com clamp (S-77). Assim o vencimento é sempre <b>estritamente</b> posterior ao fechamento
 *       ({@code ck_credit_card_invoices_due_after_closing}).</li>
 *   <li>Referência = mês do vencimento (rótulo, S-13).</li>
 * </ol>
 */
public final class InvoiceCalendar {

    private final List<BillingRule> rules;

    public InvoiceCalendar(List<BillingRule> rules) {
        Objects.requireNonNull(rules, "rules");
        if (rules.isEmpty()) {
            throw new CardFieldRequiredException("billingRules");
        }
        this.rules = rules.stream().sorted(Comparator.comparing(BillingRule::effectiveFrom)).toList();
    }

    /** Fatura a que pertence um item datado de {@code date} (regra 2 do D1 §2.5.1). */
    public InvoicePeriod periodFor(LocalDate date) {
        YearMonth month = YearMonth.from(date);
        return date.isBefore(closingIn(month)) ? periodClosingIn(month) : periodClosingIn(month.plusMonths(1));
    }

    /** Fatura que fecha no mês dado. */
    public InvoicePeriod periodClosingIn(YearMonth month) {
        BillingRule rule = ruleFor(month);
        LocalDate closing = rule.closingDay().applyTo(month);
        LocalDate due = rule.dueDay().applyTo(rule.dueInSameMonth() ? month : month.plusMonths(1));
        if (!due.isAfter(closing)) {
            // S-77: clamp no fim do mês (ex.: fecha 30, vence 31 em fevereiro) → vencimento no mês seguinte
            due = rule.dueDay().applyTo(month.plusMonths(1));
        }
        return new InvoicePeriod(closingIn(month.minusMonths(1)), closing, due, YearMonth.from(due));
    }

    /** A fatura {@code k} ciclos depois de {@code period} (k = 0 devolve a própria, recalculada pela regra vigente). */
    public InvoicePeriod plusCycles(InvoicePeriod period, int k) {
        return periodClosingIn(period.closingMonth().plusMonths(k));
    }

    public InvoicePeriod next(InvoicePeriod period) {
        return plusCycles(period, 1);
    }

    /** {@code true} se {@code date} é exatamente a data de fechamento de uma fatura deste calendário. */
    public boolean isClosingDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return closingIn(YearMonth.from(date)).equals(date);
    }

    public BillingRule ruleFor(YearMonth month) {
        BillingRule current = rules.getFirst();
        for (BillingRule rule : rules) {
            if (!rule.effectiveFrom().isAfter(month)) {
                current = rule;
            }
        }
        return current;
    }

    public List<BillingRule> rules() {
        return rules;
    }

    private LocalDate closingIn(YearMonth month) {
        return ruleFor(month).closingDay().applyTo(month);
    }
}
