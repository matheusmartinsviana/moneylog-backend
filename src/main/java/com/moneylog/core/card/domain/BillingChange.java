package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.card.exception.CardExceptions.InvoiceChainInconsistentException;

/**
 * Mudança de fechamento/vencimento de um cartão (D1 §2.5.1 regra 6, S-42) e a realocação, como função pura, dos itens
 * que estavam nas faturas afetadas. Afetadas = as que fecham a partir de {@code effectiveFrom} (todas {@code FUTURE}:
 * a corrente e as fechadas nunca mudam). As datas de fechamento antigas dessas faturas deixam de existir no calendário
 * novo, então todo item delas precisa ser realocado na mesma transação que grava a regra nova (C1).
 *
 * <ul>
 *   <li><b>Parcela:</b> "k-ésimo ciclo → k-ésimo ciclo". Cada parcela afetada fica no mesmo número de ciclos depois
 *       da parcela anterior (normalmente 1). Se a primeira parcela afetada é a parcela 1 (compra datada no futuro),
 *       ela é realocada pela regra 2 a partir da data da compra.</li>
 *   <li><b>Crédito datado</b> (reembolso no cartão): realocado pela regra 2 a partir da data dele.</li>
 *   <li><b>Pagamento:</b> nunca está numa fatura {@code FUTURE}; um pagamento afetado é inconsistência
 *       ({@link InvoiceChainInconsistentException}).</li>
 * </ul>
 * Itens das faturas não afetadas mantêm a data. Toda data antiga precisa existir no calendário de antes.
 *
 * @param cardId        cartão da mudança
 * @param effectiveFrom mês de fechamento a partir do qual a regra nova vale
 * @param before        calendário antes da mudança
 * @param after         calendário depois da mudança
 */
public record BillingChange(UUID cardId, YearMonth effectiveFrom, InvoiceCalendar before, InvoiceCalendar after) {

    public BillingChange {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }

    /** {@code true} se a fatura que fecha em {@code closingDate} (calendário antigo) muda de data. */
    public boolean affects(LocalDate closingDate) {
        return !YearMonth.from(closingDate).isBefore(effectiveFrom);
    }

    /**
     * Realoca as parcelas de uma compra (todas, inclusive as canceladas, para continuarem apontando para uma fatura
     * existente). Devolve a lista na ordem 1..N; parcelas não afetadas voltam iguais.
     */
    public List<TransactionInstallment> reallocateInstallments(List<TransactionInstallment> installments) {
        List<TransactionInstallment> ordered = installments.stream()
                .sorted(Comparator.comparingInt(TransactionInstallment::number)).toList();
        List<TransactionInstallment> result = new ArrayList<>(ordered.size());
        LocalDate previousOld = null;
        InvoicePeriod previousNew = null;
        for (TransactionInstallment installment : ordered) {
            LocalDate old = requireOldClosing(installment.invoiceClosingDate());
            InvoicePeriod target;
            if (!affects(old)) {
                target = after.periodClosingIn(YearMonth.from(old));
            } else if (previousNew == null) {
                target = byDate(installment.effectiveDate(), old);
            } else {
                long gap = ChronoUnit.MONTHS.between(YearMonth.from(previousOld), YearMonth.from(old));
                target = after.plusCycles(previousNew, (int) gap);
            }
            result.add(target.closingDate().equals(old) ? installment : installment.withInvoiceClosingDate(target.closingDate()));
            previousOld = old;
            previousNew = target;
        }
        return List.copyOf(result);
    }

    /** Nova fatura de um crédito datado (reembolso no cartão), pela regra 2; sem mudança se a fatura não é afetada. */
    public LocalDate reallocateDated(LocalDate closingDate, LocalDate itemDate) {
        LocalDate old = requireOldClosing(closingDate);
        return affects(old) ? byDate(Objects.requireNonNull(itemDate, "itemDate"), old).closingDate() : old;
    }

    /** Pagamento: só em fatura não afetada (corrente ou fechada); devolve a mesma data. */
    public LocalDate keepPayment(LocalDate closingDate) {
        LocalDate old = requireOldClosing(closingDate);
        if (affects(old)) {
            throw new InvoiceChainInconsistentException("pagamento na fatura FUTURE de " + old + ".");
        }
        return old;
    }

    private InvoicePeriod byDate(LocalDate date, LocalDate old) {
        InvoicePeriod period = after.periodFor(date);
        // um item afetado nunca volta para uma fatura que não muda; se a data não ajudar, mantém o mês do fechamento
        return affects(period.closingDate()) ? period : after.periodClosingIn(YearMonth.from(old));
    }

    private LocalDate requireOldClosing(LocalDate closingDate) {
        Objects.requireNonNull(closingDate, "closingDate");
        if (!before.isClosingDate(closingDate)) {
            throw new InvoiceChainInconsistentException(closingDate + " não é fechamento do calendário antes da mudança.");
        }
        return closingDate;
    }
}
