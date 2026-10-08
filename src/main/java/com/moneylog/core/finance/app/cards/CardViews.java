package com.moneylog.core.finance.app.cards;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.card.domain.AvailableLimit;
import com.moneylog.core.card.domain.AvailableLimitCalculator;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceItemKind;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Monta {@link CardDetails} dos {@link FinancialRecords} carregados uma vez (S-79): o limite sai do livro {@code CARD}
 * (a mesma conta do dashboard) e a fatura corrente e os comprometidos saem da cadeia de faturas
 * ({@code CardInvoices.chainFor}, a única forma de montá-la, S-135).
 */
public final class CardViews {

    private CardViews() {
    }

    public static CardDetails of(Persisted<CreditCard> persisted, FinancialRecords records, LocalDate today) {
        return of(persisted, records, postingsByCard(records), today);
    }

    /** Vários cartões do mesmo carregamento: o razão é percorrido uma vez só. */
    public static List<CardDetails> ofAll(List<Persisted<CreditCard>> cards, FinancialRecords records, LocalDate today) {
        Map<UUID, List<Posting>> postings = postingsByCard(records);
        return cards.stream().map(card -> of(card, records, postings, today)).toList();
    }

    /** A cadeia de faturas do cartão sobre as movimentações carregadas (todas, ativas e inativas). */
    public static InvoiceChain chainOf(CreditCard card, FinancialRecords records) {
        List<Transaction> ofCard = records.transactions().stream().filter(t -> card.id().equals(t.cardId())).toList();
        return CardInvoices.chainFor(card, ofCard);
    }

    private static CardDetails of(Persisted<CreditCard> persisted, FinancialRecords records, Map<UUID, List<Posting>> postings,
                                  LocalDate today) {
        CreditCard card = persisted.value();
        InvoiceChain chain = chainOf(card, records);
        AvailableLimit limit = AvailableLimitCalculator.calculate(card, postings.getOrDefault(card.id(), List.of()));
        Money zero = Money.zero(CurrencyCode.BRL);
        Money totalCommitted = zero;
        Money committedFuture = zero;
        boolean hasPending = false;
        for (CreditCardInvoice invoice : chain.invoices()) {
            InvoiceStatus status = invoice.statusAt(today);
            if (status == InvoiceStatus.FUTURE) {
                committedFuture = committedFuture.add(Money.sum(invoice.items().stream()
                        .filter(item -> item.active() && item.kind() == InvoiceItemKind.INSTALLMENT)
                        .map(item -> item.amount()).toList(), CurrencyCode.BRL));
            } else if (status != InvoiceStatus.PAID) {
                totalCommitted = totalCommitted.add(invoice.outstanding());
            }
            if (status != InvoiceStatus.PAID && (invoice.outstanding().isPositive() || invoice.items().stream().anyMatch(i -> i.active()))) {
                hasPending = true;
            }
        }
        Money creditBalance = chain.creditBalance();
        if (creditBalance.isPositive() || limit.creditBalance().isPositive()) {
            hasPending = true;
        }
        boolean archivable = card.status() == CardStatus.ACTIVE && !hasPending;
        return new CardDetails(card, persisted.createdAt(), persisted.updatedAt(), limit, creditBalance,
                InvoiceView.of(card.id(), chain.current(today), today), totalCommitted, committedFuture,
                usedRatio(card.limit(), limit.available()), archivable);
    }

    /** {@code (limite − disponível) / limite}, limitada a 0..1, com 4 casas (só para a barra; E3-12). */
    static BigDecimal usedRatio(Money limit, Money available) {
        if (limit == null || available == null) {
            return null; // sem limite definido não há razão de uso (sem divisão por zero)
        }
        long used = limit.cents() - available.cents();
        if (used <= 0 || limit.cents() <= 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        if (used >= limit.cents()) {
            return BigDecimal.ONE.setScale(4);
        }
        return BigDecimal.valueOf(used).divide(BigDecimal.valueOf(limit.cents()), 4, RoundingMode.HALF_UP);
    }

    private static Map<UUID, List<Posting>> postingsByCard(FinancialRecords records) {
        return records.ledger().entries().stream().map(LedgerEntry::posting).filter(p -> p.bookType() == BookType.CARD)
                .collect(Collectors.groupingBy(p -> p.book().id()));
    }
}
