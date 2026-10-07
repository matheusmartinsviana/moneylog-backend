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

/**
 * Lente de consumo e receita da competência (D1 §2.8, "Mês atual"):
 * <ul>
 *   <li>{@code income} = −Σ livro {@code INCOME} em M;</li>
 *   <li>{@code expensesPaid} = consumo vindo do tipo {@code EXPENSE}, com quebra por meio de pagamento;</li>
 *   <li>{@code credit} = consumo vindo de {@code CARD_PURCHASE} (por competência da parcela);</li>
 *   <li>{@code refunds} = Σ {@code REFUND} (conta e cartão), em valor positivo;</li>
 *   <li>{@code debtConsumption} = consumo que nasce de dívida {@code PAYABLE} sem movimentação com categoria ("um amigo pagou meu
 *       jantar", D1 §2.6.1, S-137): lançamento {@code EXPENSE} com origem {@code DEBT_RECOGNITION}, sem movimentação e sem caixa;</li>
 *   <li>{@code consumption} = Σ livro {@code EXPENSE} em M = expensesPaid + credit − refunds + debtConsumption (identidade testada);</li>
 *   <li>{@code investedNet}, {@code lent}, {@code received}: pela data da movimentação no mês;</li>
 *   <li>{@code net} = receitas − consumo (S-23).</li>
 * </ul>
 */
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
