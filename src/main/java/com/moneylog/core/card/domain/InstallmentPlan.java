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

/**
 * Parcelamento de uma compra no cartão (D1 §2.5.2): {@code N} parcelas {@code k/N} com {@code Money.allocate(N)}
 * (soma exata, resto na primeira, S-3). A parcela 1 cai na competência da compra; a parcela k tem data efetiva = data
 * da compra + (k−1) meses (clamp no fim do mês) e fica na fatura da parcela 1 + (k−1) ciclos.
 */
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

    /**
     * Gera as parcelas.
     *
     * @param allocations fatura e lançamento tardio de cada parcela ({@link InvoiceAllocator}), na ordem 1..N
     */
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

    /**
     * Cancelamento das parcelas restantes (D1 §2.5.2): as parcelas em faturas {@code FUTURE}/{@code OPEN} viram
     * {@code CANCELED}; as de faturas fechadas não mudam.
     */
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
