package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceItem;
import com.moneylog.core.card.domain.InvoiceItemKind;
import com.moneylog.core.recurrence.domain.Occurrence;
import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.PurchaseShares;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionNature;
import com.moneylog.core.transaction.domain.TransactionType;

public record OwnerOutflow(Money cardsMine, Money cardsGross, Money subscriptionsMine, Money offCardFixed, Money offCardVariable,
                           List<MethodAmount> byMethod, Money realEstateMine, Money companyReimbursable, boolean realEstateUndefined) {

    private static final Money ZERO = Money.zero(CurrencyCode.BRL);

    public record MethodAmount(PaymentMethod method, Money amount) {
    }

    public OwnerOutflow {
        byMethod = List.copyOf(byMethod);
    }

    public Money total() {
        return cardsMine.add(offCardFixed).add(offCardVariable).add(realEstateMine);
    }

    public static OwnerOutflow compose(OwnerSources sources, Competence month, LocalDate asOf) {
        Accumulator acc = new Accumulator(sources, month, asOf);
        acc.invoices();
        acc.cardOccurrences();
        acc.offCardTransactions();
        acc.offCardOccurrences();
        acc.realEstate();
        return acc.result();
    }

    private static final class Accumulator {

        private final OwnerSources sources;
        private final Competence month;
        private final LocalDate asOf;
        private final boolean past;
        private final Set<String> seenInvoices = new HashSet<>();
        private final Set<String> seenInstallments = new HashSet<>();
        private final Set<String> seenOccurrences = new HashSet<>();
        private final Set<UUID> seenTransactions = new HashSet<>();
        private final Set<String> seenItems = new HashSet<>();
        private final Map<PaymentMethod, Money> byMethod = new EnumMap<>(PaymentMethod.class);
        private Money cardsMine = ZERO;
        private Money cardsGross = ZERO;
        private Money subscriptionsMine = ZERO;
        private Money fixed = ZERO;
        private Money variable = ZERO;
        private Money realEstateMine = ZERO;
        private Money company = ZERO;
        private boolean undefined;

        Accumulator(OwnerSources sources, Competence month, LocalDate asOf) {
            this.sources = sources;
            this.month = month;
            this.asOf = asOf;
            this.past = month.isBefore(Competence.from(asOf));
        }

        void invoices() {
            for (CreditCard card : sources.records().cards()) {
                for (CreditCardInvoice invoice : sources.chain(card).invoices()) {
                    if (Competence.from(invoice.dueDate()).equals(month) && seenInvoices.add(card.id() + "/" + invoice.closingDate())) {
                        invoice(invoice);
                    }
                }
            }
        }

        private void invoice(CreditCardInvoice invoice) {
            Money total = invoice.total();
            Money base = past ? total : invoice.outstanding();
            if (!base.isPositive()) {
                return;
            }
            long mine = 0;
            long subscriptions = 0;
            long reimbursable = 0;
            for (InvoiceItem item : invoice.items()) {
                if (!item.active()) {
                    continue;
                }
                if (item.kind() != InvoiceItemKind.INSTALLMENT) {
                    mine -= item.amount().cents();
                    continue;
                }
                if (!seenInstallments.add(item.transactionId() + "/" + item.installmentId())) {
                    continue;
                }
                Transaction tx = sources.transaction(item.transactionId());
                RecurringTransaction origin = tx == null ? null : sources.recurrence(tx.origin().recurrenceId());
                if (tx != null && tx.origin().recurrenceId() != null) {
                    seenOccurrences.add(OwnerSources.occurrenceKey(tx.origin().recurrenceId(), tx.origin().occurrenceDate()));
                }
                if (isCompanySubscription(origin)) {
                    reimbursable += item.amount().cents();
                    continue;
                }
                long itemMine = mineOf(tx == null ? null : tx.purchaseShares(), item.amount()).cents();
                mine += itemMine;
                if (origin != null && origin.isSubscription()) {
                    subscriptions += itemMine;
                }
            }
            cardsGross = cardsGross.add(base);
            cardsMine = cardsMine.add(Money.ofCents(Math.max(0, scale(mine, base, total)), CurrencyCode.BRL));
            subscriptionsMine = subscriptionsMine.add(Money.ofCents(Math.max(0, scale(subscriptions, base, total)), CurrencyCode.BRL));
            company = company.add(Money.ofCents(Math.max(0, scale(reimbursable, base, total)), CurrencyCode.BRL));
        }

        void cardOccurrences() {
            if (past) {
                return;
            }
            for (RecurringTransaction recurrence : sources.recurrences()) {
                CreditCard card = sources.card(recurrence.cardId());
                if (card == null || !recurrence.isActive()) {
                    continue;
                }
                for (Occurrence occurrence : recurrence.occurrences(recurrence.schedule().startDate(), month.lastDay())) {
                    if (sources.realized(recurrence.id(), occurrence.date()) != null
                            || !seenOccurrences.add(OwnerSources.occurrenceKey(recurrence.id(), occurrence.date()))) {
                        continue;
                    }
                    if (!Competence.from(card.calendar().periodFor(occurrence.date()).dueDate()).equals(month)) {
                        continue;
                    }
                    cardsGross = cardsGross.add(occurrence.amount());
                    if (isCompanySubscription(recurrence)) {
                        company = company.add(occurrence.amount());
                        continue;
                    }
                    Money mine = mineOf(recurrence.purchaseShares(), occurrence.amount());
                    cardsMine = cardsMine.add(mine);
                    if (recurrence.isSubscription()) {
                        subscriptionsMine = subscriptionsMine.add(mine);
                    }
                }
            }
        }

        void offCardTransactions() {
            for (Transaction tx : sources.records().transactions()) {
                if (!tx.isActive() || tx.type() != TransactionType.EXPENSE || tx.cardId() != null
                        || !Competence.from(tx.date()).equals(month) || (!past && !tx.date().isAfter(asOf))
                        || !seenTransactions.add(tx.id())) {
                    continue;
                }
                offCard(TransactionNature.forExpense(tx.nature()), tx.paymentMethod(), tx.amount());
            }
        }

        void offCardOccurrences() {
            if (past) {
                return;
            }
            for (RecurringTransaction recurrence : sources.recurrences()) {
                if (recurrence.type() != TransactionType.EXPENSE || recurrence.cardId() != null || !recurrence.isActive()) {
                    continue;
                }
                for (Occurrence occurrence : recurrence.occurrences(month.firstDay(), month.lastDay())) {
                    if (sources.realized(recurrence.id(), occurrence.date()) != null
                            || !seenOccurrences.add(OwnerSources.occurrenceKey(recurrence.id(), occurrence.date()))) {
                        continue;
                    }
                    TransactionNature nature = recurrence.nature() == null ? TransactionNature.FIXED : recurrence.nature();
                    offCard(nature, recurrence.paymentMethod(), occurrence.amount());
                }
            }
        }

        void realEstate() {
            if (past) {
                return;
            }
            for (RealEstateItem item : sources.realEstate()) {
                if (Competence.from(item.dueDate()).equals(month) && seenItems.add(item.key())) {
                    realEstateMine = realEstateMine.add(item.mine());
                    undefined |= !item.sharesDefined();
                }
            }
        }

        OwnerOutflow result() {
            List<MethodAmount> methods = byMethod.entrySet().stream().map(e -> new MethodAmount(e.getKey(), e.getValue())).toList();
            return new OwnerOutflow(cardsMine, cardsGross, subscriptionsMine, fixed, variable, methods, realEstateMine, company, undefined);
        }

        private void offCard(TransactionNature nature, PaymentMethod method, Money amount) {
            if (nature == TransactionNature.FIXED) {
                fixed = fixed.add(amount);
            } else {
                variable = variable.add(amount);
            }
            byMethod.merge(method == null ? PaymentMethod.OTHER : method, amount, Money::add);
        }

        private static boolean isCompanySubscription(RecurringTransaction recurrence) {
            return recurrence != null && recurrence.isSubscription() && recurrence.traits().scope() == RecurrenceScope.COMPANY;
        }

        private static Money mineOf(PurchaseShares shares, Money amount) {
            return shares == null ? amount : shares.split(amount).mine();
        }

        private static long scale(long value, Money base, Money total) {
            if (base.equals(total)) {
                return value;
            }
            return total.isPositive() ? Math.floorDiv(Math.multiplyExact(value, base.cents()), total.cents()) : 0;
        }
    }
}
