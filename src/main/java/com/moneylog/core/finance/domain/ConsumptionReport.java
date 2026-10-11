package com.moneylog.core.finance.domain;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

public record ConsumptionReport(Competence competence, Money income, Money expensesPaid, Map<PaymentMethod, Money> expensesByMethod,
                                Money credit, Money refunds, Money consumption, Money investedNet, Money lent, Money received,
                                Money net, Money debtConsumption) {

    public static ConsumptionReport of(FinancialRecords records, Ledger ledger, Competence competence) {
        Map<UUID, Transaction> transactions = records.transactionsById();
        Money zero = Money.zero(CurrencyCode.BRL);
        Map<PaymentMethod, Money> byMethod = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod method : PaymentMethod.values()) {
            byMethod.put(method, zero);
        }
        Money paid = zero;
        Money credit = zero;
        Money refunds = zero;
        Money consumption = zero;
        Money debtConsumption = zero;
        for (LedgerEntry e : ledger.inCompetence(BookType.EXPENSE, competence)) {
            Money amount = e.posting().amount();
            consumption = consumption.add(amount);
            Transaction tx = transactions.get(e.source().transactionId());
            TransactionType type = tx == null ? null : tx.type();
            if (type == TransactionType.EXPENSE) {
                paid = paid.add(amount);
                byMethod.merge(tx.paymentMethod(), amount, Money::add);
            } else if (type == TransactionType.CARD_PURCHASE) {
                credit = credit.add(amount);
            } else if (type == TransactionType.REFUND) {
                refunds = refunds.add(amount.negate());
            } else if (e.source().type() == PostingSourceType.DEBT_RECOGNITION) {
                debtConsumption = debtConsumption.add(amount);
            }
        }
        Money income = ledger.inCompetence(BookType.INCOME, competence).stream()
                .map(e -> e.posting().amount().negate()).reduce(zero, Money::add);
        Money investedNet = zero;
        Money lent = zero;
        Money received = zero;
        for (Transaction tx : records.transactions()) {
            if (!tx.isActive() || !competence.contains(tx.date())) {
                continue;
            }
            switch (tx.type()) {
                case INVESTMENT -> investedNet = tx.investmentDirection() == InvestmentDirection.CONTRIBUTION
                        ? investedNet.add(tx.amount()) : investedNet.subtract(tx.amount());
                case LOAN_GIVEN -> lent = lent.add(tx.amount());
                case MONEY_RECEIVED -> received = received.add(tx.amount());
                default -> { }
            }
        }
        return new ConsumptionReport(competence, income, paid, Map.copyOf(byMethod), credit, refunds, consumption, investedNet,
                lent, received, income.subtract(consumption), debtConsumption);
    }
}
