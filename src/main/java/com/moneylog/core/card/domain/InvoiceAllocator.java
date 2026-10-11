package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class InvoiceAllocator {

    private InvoiceAllocator() {
    }

    public record Allocation(InvoicePeriod invoice, boolean lateEntry) {
    }

    public static Allocation targetInvoiceFor(LocalDate date, InvoiceChain chain, LocalDate today) {
        return allocate(chain.calendar().periodFor(date), chain, today);
    }

    public static List<Allocation> forInstallments(LocalDate purchaseDate, int count, InvoiceChain chain, LocalDate today) {
        InvoiceCalendar calendar = chain.calendar();
        InvoicePeriod base = calendar.periodFor(purchaseDate);
        List<Allocation> result = new ArrayList<>(count);
        InvoicePeriod previous = null;
        for (int k = 0; k < count; k++) {
            InvoicePeriod candidate = calendar.plusCycles(base, k);
            if (previous != null && !candidate.closingDate().isAfter(previous.closingDate())) {
                candidate = calendar.next(previous);
            }
            Allocation allocation = allocate(candidate, chain, today);
            result.add(allocation);
            previous = allocation.invoice();
        }
        return List.copyOf(result);
    }

    private static Allocation allocate(InvoicePeriod period, InvoiceChain chain, LocalDate today) {
        if (chain.isUntouchedIssued(period.closingDate(), today)) {
            return new Allocation(period, true);
        }
        return switch (chain.statusOf(period.closingDate(), today)) {
            case FUTURE, OPEN -> new Allocation(period, false);
            case CLOSED, OVERDUE -> new Allocation(period, true);
            case PAID -> new Allocation(chain.calendar().periodFor(today), true);
        };
    }
}
