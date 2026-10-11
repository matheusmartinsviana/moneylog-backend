package com.moneylog.core.card.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.card.exception.CardExceptions.InvalidInstallmentCountException;
import com.moneylog.core.card.exception.CardExceptions.InvalidInstallmentException;
import com.moneylog.core.card.exception.CardExceptions.NoInstallmentsToCancelException;
import com.moneylog.core.sk.money.Money;

public final class InstallmentPlan {

    public static final int MIN_INSTALLMENTS = 1;
    public static final int MAX_INSTALLMENTS = 36;

    private InstallmentPlan() {
    }

    public static void validateCount(int count) {
        if (count < MIN_INSTALLMENTS || count > MAX_INSTALLMENTS) {
            throw new InvalidInstallmentCountException(count);
        }
    }

    public static List<TransactionInstallment> generate(UUID transactionId, Money amount, int count, LocalDate purchaseDate,
                                                        List<InvoiceAllocator.Allocation> allocations) {
        validateCount(count);
        if (allocations.size() != count) {
            throw new InvalidInstallmentException("uma alocação por parcela (" + allocations.size() + " para " + count + ").");
        }
        List<Money> parts = amount.allocate(count);
        List<TransactionInstallment> installments = new ArrayList<>(count);
        for (int k = 1; k <= count; k++) {
            InvoiceAllocator.Allocation allocation = allocations.get(k - 1);
            installments.add(new TransactionInstallment(TransactionInstallment.idFor(transactionId, k), transactionId, k, count,
                    parts.get(k - 1), purchaseDate.plusMonths(k - 1L), allocation.invoice().closingDate(),
                    allocation.lateEntry(), null));
        }
        return List.copyOf(installments);
    }

    public static List<TransactionInstallment> generateOngoing(UUID transactionId, Money installmentAmount, int total, int remaining,
                                                               LocalDate firstDate, List<InvoiceAllocator.Allocation> allocations) {
        validateCount(total);
        if (remaining < 1 || remaining > total) {
            throw new com.moneylog.core.card.exception.CardExceptions.OngoingInvalidCountException(total, remaining);
        }
        if (allocations.size() != remaining) {
            throw new InvalidInstallmentException("uma alocação por parcela (" + allocations.size() + " para " + remaining + ").");
        }
        int firstNumber = total - remaining + 1;
        List<TransactionInstallment> installments = new ArrayList<>(remaining);
        for (int i = 0; i < remaining; i++) {
            InvoiceAllocator.Allocation allocation = allocations.get(i);
            installments.add(new TransactionInstallment(TransactionInstallment.idFor(transactionId, firstNumber + i), transactionId,
                    firstNumber + i, total, installmentAmount, firstDate.plusMonths(i), allocation.invoice().closingDate(),
                    allocation.lateEntry(), null));
        }
        return List.copyOf(installments);
    }

    public static List<TransactionInstallment> cancelRemaining(List<TransactionInstallment> installments, InvoiceChain chain,
                                                               LocalDate today, Instant now) {
        List<TransactionInstallment> result = installments.stream()
                .map(i -> !i.isCanceled() && !chain.statusOf(i.invoiceClosingDate(), today).isClosed() ? i.cancel(now) : i)
                .toList();
        if (result.equals(installments)) {
            throw new NoInstallmentsToCancelException();
        }
        return result;
    }
}
