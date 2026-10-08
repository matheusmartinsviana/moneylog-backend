package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Alocação de itens em faturas com lançamento tardio (D1 §2.5.3, S-14):
 * <ol>
 *   <li>pega a fatura do calendário;</li>
 *   <li>{@code FUTURE}/{@code OPEN}: o item entra nela;</li>
 *   <li>{@code CLOSED}/{@code OVERDUE} (fechada e não paga): entra nela mesmo, com {@code lateEntry};</li>
 *   <li>{@code PAID}: entra na fatura corrente, com {@code lateEntry}; exceção: fatura fechada, ainda no prazo e sem nenhum
 *       movimento (emitida pelo banco e ainda não carregada) recebe o item como lançamento tardio.</li>
 * </ol>
 * Nas parcelas, cada uma segue a anterior (k-ésima fatura depois da 1ª) e a mesma regra vale para cada parcela: uma
 * parcela nunca cai em fatura paga (S-50).
 *
 * <p>Não existe alocação "só pelo calendário": toda alocação passa pelo status das faturas (A2), senão uma compra
 * tardia (recorrência realizada com atraso, por exemplo) cairia numa fatura {@code PAID}.
 */
public final class InvoiceAllocator {

    private InvoiceAllocator() {
    }

    /** Fatura de destino de um item datado e se é lançamento tardio. */
    public record Allocation(InvoicePeriod invoice, boolean lateEntry) {
    }

    public static Allocation targetInvoiceFor(LocalDate date, InvoiceChain chain, LocalDate today) {
        return allocate(chain.calendar().periodFor(date), chain, today);
    }

    /** Alocação de cada parcela de uma compra. */
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
