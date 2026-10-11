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

public record BillingChange(UUID cardId, YearMonth effectiveFrom, InvoiceCalendar before, InvoiceCalendar after) {

    public BillingChange {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }

    public boolean affects(LocalDate closingDate) {
        return !YearMonth.from(closingDate).isBefore(effectiveFrom);
    }

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

    public LocalDate reallocateDated(LocalDate closingDate, LocalDate itemDate) {
        LocalDate old = requireOldClosing(closingDate);
        return affects(old) ? byDate(Objects.requireNonNull(itemDate, "itemDate"), old).closingDate() : old;
    }

    public LocalDate keepPayment(LocalDate closingDate) {
        LocalDate old = requireOldClosing(closingDate);
        if (affects(old)) {
            throw new InvoiceChainInconsistentException("pagamento na fatura FUTURE de " + old + ".");
        }
        return old;
    }

    private InvoicePeriod byDate(LocalDate date, LocalDate old) {
        InvoicePeriod period = after.periodFor(date);
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
