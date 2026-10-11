package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.finance.domain.DebtOverview.CardItem;
import com.moneylog.core.finance.domain.DebtOverview.Cards;
import com.moneylog.core.finance.domain.DebtOverview.Comparison;
import com.moneylog.core.finance.domain.DebtOverview.ComparisonReason;
import com.moneylog.core.finance.domain.DebtOverview.DebtGroup;
import com.moneylog.core.finance.domain.DebtOverview.DebtItem;
import com.moneylog.core.finance.domain.DebtOverview.Income;
import com.moneylog.core.finance.domain.DebtOverview.IncomeSource;
import com.moneylog.core.finance.domain.DebtOverview.Liquidity;
import com.moneylog.core.finance.domain.DebtOverview.Parts;
import com.moneylog.core.finance.domain.DebtOverview.PayoffReason;
import com.moneylog.core.finance.domain.DebtOverview.Plan;
import com.moneylog.core.finance.domain.DebtOverview.Point;
import com.moneylog.core.finance.domain.DebtOverview.Schedule;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionType;

public final class DebtOverviewCalculator {

    public static final int MIN_MONTHS = 1;
    public static final int MAX_MONTHS = 36;
    public static final int DEFAULT_MONTHS = 12;
    private static final int RATIO_SCALE = 4;
    private static final Money ZERO = Money.zero(CurrencyCode.BRL);

    private DebtOverviewCalculator() {
    }

    record Positions(Cards cards, DebtGroup people, DebtGroup other, Money receivables, boolean hasRecords, List<Obligation> obligations) {

        Money total() {
            return cards.total().add(people.total()).add(other.total());
        }
    }

    record Obligation(Part part, Money amount, LocalDate dueDate, boolean overdue) {
    }

    enum Part {
        CARDS,
        PEOPLE,
        OTHER
    }

    public static Money total(FinancialRecords records, LocalDate today) {
        return positions(records, Map.of(), today).total();
    }

    public static Parts parts(FinancialRecords records, LocalDate today) {
        Positions p = positions(records, Map.of(), today);
        return new Parts(p.cards().total(), p.people().total(), p.other().total());
    }

    public static DebtOverview overview(FinancialRecords records, Map<UUID, String> personNames, MonthlyEstimate recurrence,
                                        Money lastFullMonthIncome, Money inAccounts, Money available, LocalDate today, int months) {
        if (months < MIN_MONTHS || months > MAX_MONTHS) {
            throw new IllegalArgumentException("months fora de " + MIN_MONTHS + ".." + MAX_MONTHS + ": " + months);
        }
        Positions p = positions(records, personNames, today);
        Money total = p.total();
        Income income = income(recurrence.recurringIncome(), lastFullMonthIncome);
        return new DebtOverview(today, total, new Parts(p.cards().total(), p.people().total(), p.other().total()), p.cards(), p.people(),
                p.other(), p.receivables(), p.hasRecords(), recurrence.fixedCosts(), income, new Liquidity(inAccounts, available),
                comparison(total, recurrence.fixedCosts(), income, inAccounts), schedule(p, total, today, months));
    }

    static Positions positions(FinancialRecords records, Map<UUID, String> personNames, LocalDate today) {
        List<Obligation> obligations = new ArrayList<>();
        List<CardItem> cardItems = new ArrayList<>();
        Money cardsTotal = ZERO;
        Money cardsInvoiced = ZERO;
        Money cardsFuture = ZERO;
        for (CreditCard card : records.cards()) {
            CardItem item = card(card, records, today, obligations);
            cardsTotal = cardsTotal.add(item.total());
            cardsInvoiced = cardsInvoiced.add(item.invoiced());
            cardsFuture = cardsFuture.add(item.future());
            if (item.total().isPositive() || !item.plans().isEmpty()) {
                cardItems.add(item);
            }
        }
        cardItems.sort(Comparator.comparing(CardItem::cardName, String.CASE_INSENSITIVE_ORDER).thenComparing(CardItem::cardId));

        List<DebtItem> people = new ArrayList<>();
        List<DebtItem> other = new ArrayList<>();
        Money peopleTotal = ZERO;
        Money otherTotal = ZERO;
        Money receivables = ZERO;
        for (Debt debt : records.debts()) {
            DebtStatus status = debt.status();
            if (status != DebtStatus.OPEN && status != DebtStatus.PARTIALLY_SETTLED) {
                continue;
            }
            if (debt.direction() == DebtDirection.RECEIVABLE) {
                receivables = receivables.add(debt.outstanding());
                continue;
            }
            boolean free = debt.personId() == null;
            boolean overdue = debt.isOverdue(today);
            DebtItem item = new DebtItem(debt.id(), debt.personId(), free ? debt.creditorName() : personNames.getOrDefault(debt.personId(), ""),
                    debt.description(), debt.outstanding(), debt.expectedDate(), overdue);
            obligations.add(new Obligation(free ? Part.OTHER : Part.PEOPLE, debt.outstanding(), debt.expectedDate(), overdue));
            if (free) {
                other.add(item);
                otherTotal = otherTotal.add(item.outstanding());
            } else {
                people.add(item);
                peopleTotal = peopleTotal.add(item.outstanding());
            }
        }
        Comparator<DebtItem> byDate = Comparator.comparing(DebtItem::expectedDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DebtItem::debtId);
        people.sort(byDate);
        other.sort(byDate);
        boolean hasRecords = !records.cards().isEmpty() || !records.debts().isEmpty();
        return new Positions(new Cards(cardsTotal, cardsInvoiced, cardsFuture, cardItems), new DebtGroup(peopleTotal, people),
                new DebtGroup(otherTotal, other), receivables, hasRecords, obligations);
    }

    private static CardItem card(CreditCard card, FinancialRecords records, LocalDate today, List<Obligation> obligations) {
        List<Transaction> ofCard = records.transactions().stream().filter(t -> card.id().equals(t.cardId())).toList();
        InvoiceChain chain = CardInvoices.chainFor(card, ofCard);
        Money invoiced = ZERO;
        Money future = ZERO;
        for (CreditCardInvoice invoice : chain.invoices()) {
            InvoiceStatus status = invoice.statusAt(today);
            if (status == InvoiceStatus.PAID) {
                continue;
            }
            Money outstanding = invoice.outstanding();
            if (status == InvoiceStatus.FUTURE) {
                future = future.add(outstanding);
            } else {
                invoiced = invoiced.add(outstanding);
            }
            if (outstanding.isPositive()) {
                obligations.add(new Obligation(Part.CARDS, outstanding, invoice.dueDate(), status == InvoiceStatus.OVERDUE));
            }
        }
        Money total = invoiced.add(future);

        Money otherCharges = ZERO;
        List<Plan> plans = new ArrayList<>();
        for (Transaction purchase : ofCard) {
            if (purchase.type() != TransactionType.CARD_PURCHASE || !purchase.isActive()) {
                continue;
            }
            List<TransactionInstallment> pending = new ArrayList<>();
            for (TransactionInstallment installment : purchase.installments()) {
                if (!installment.isCanceled() && chain.invoice(installment.invoiceClosingDate()).statusAt(today) != InvoiceStatus.PAID) {
                    pending.add(installment);
                }
            }
            if (pending.isEmpty()) {
                continue;
            }
            Money sum = Money.sum(pending.stream().map(TransactionInstallment::amount).toList(), CurrencyCode.BRL);
            if (pending.getFirst().total() == 1) {
                otherCharges = otherCharges.add(sum);
                continue;
            }
            pending.sort(Comparator.comparingInt(TransactionInstallment::number));
            TransactionInstallment next = pending.getFirst();
            TransactionInstallment last = pending.getLast();
            plans.add(new Plan(purchase.id(), purchase.description(), purchase.origin().source() == TransactionSource.ONGOING, next.amount(),
                    next.total(), pending.size(), next.number(), sum, chain.invoice(next.invoiceClosingDate()).dueDate(),
                    chain.invoice(last.invoiceClosingDate()).dueDate()));
        }
        plans.sort(Comparator.comparing(Plan::nextDueDate).thenComparing(Plan::purchaseId));
        Money plansRemaining = Money.sum(plans.stream().map(Plan::remaining).toList(), CurrencyCode.BRL);
        Money adjustment = total.subtract(plansRemaining).subtract(otherCharges);
        return new CardItem(card.id(), card.name(), total, invoiced, future, otherCharges, adjustment, plans);
    }

    private static Income income(Money recurringIncome, Money lastFullMonthIncome) {
        if (recurringIncome.isPositive()) {
            return new Income(recurringIncome, IncomeSource.RECURRING_ESTIMATE);
        }
        if (lastFullMonthIncome != null && lastFullMonthIncome.isPositive()) {
            return new Income(lastFullMonthIncome, IncomeSource.LAST_FULL_MONTH);
        }
        return new Income(null, IncomeSource.NONE);
    }

    private static Comparison comparison(Money total, Money fixedCosts, Income income, Money inAccounts) {
        Money debtMinusInAccounts = total.subtract(inAccounts);
        if (income.monthly() == null) {
            return new Comparison(null, null, debtMinusInAccounts, ComparisonReason.NO_INCOME);
        }
        return new Comparison(ratio(total, income.monthly()), ratio(fixedCosts, income.monthly()), debtMinusInAccounts, null);
    }

    static BigDecimal ratio(Money numerator, Money denominator) {
        return BigDecimal.valueOf(numerator.cents()).divide(BigDecimal.valueOf(denominator.cents()), RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static Schedule schedule(Positions p, Money total, LocalDate today, int months) {
        Competence first = Competence.from(today);
        Competence lastInHorizon = first.plusMonths(months - 1L);
        Map<Competence, Money[]> buckets = new LinkedHashMap<>();
        for (int i = 0; i < months; i++) {
            buckets.put(first.plusMonths(i), new Money[] {ZERO, ZERO, ZERO});
        }
        Money overdue = ZERO;
        Money undated = ZERO;
        Money beyond = ZERO;
        for (Obligation o : p.obligations()) {
            if (o.overdue()) {
                overdue = overdue.add(o.amount());
            } else if (o.dueDate() == null) {
                undated = undated.add(o.amount());
            } else {
                Competence competence = Competence.from(o.dueDate());
                if (competence.isAfter(lastInHorizon)) {
                    beyond = beyond.add(o.amount());
                } else {
                    Money[] bucket = buckets.get(competence.isBefore(first) ? first : competence);
                    bucket[o.part().ordinal()] = bucket[o.part().ordinal()].add(o.amount());
                }
            }
        }
        List<Point> points = new ArrayList<>();
        Money remaining = total.subtract(overdue);
        Competence lastWithValue = null;
        for (Map.Entry<Competence, Money[]> entry : buckets.entrySet()) {
            Money[] b = entry.getValue();
            Money pointTotal = b[0].add(b[1]).add(b[2]);
            remaining = remaining.subtract(pointTotal);
            points.add(new Point(entry.getKey(), b[0], b[1], b[2], pointTotal, remaining));
            if (pointTotal.isPositive()) {
                lastWithValue = entry.getKey();
            }
        }
        Competence payoff = null;
        PayoffReason reason = null;
        if (undated.isPositive()) {
            reason = PayoffReason.UNDATED_DEBTS;
        } else if (beyond.isPositive()) {
            reason = PayoffReason.BEYOND_HORIZON;
        } else if (total.isPositive()) {
            payoff = lastWithValue != null ? lastWithValue : first;
        }
        return new Schedule(months, points, overdue, undated, beyond, payoff, reason);
    }
}
