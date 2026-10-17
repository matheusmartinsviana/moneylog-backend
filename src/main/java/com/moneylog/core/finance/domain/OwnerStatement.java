package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;

public record OwnerStatement(LocalDate asOf, int incomeLagMonths, Money openingBalance, List<MonthColumn> months,
                             List<Competence> negativeMonths, List<Warning> warnings) {

    public static final int MIN_MONTHS = 1;
    public static final int MAX_MONTHS = 12;
    public static final int DEFAULT_MONTHS = 3;
    public static final String OPENING_BALANCE_NOT_PROJECTED = "OPENING_BALANCE_NOT_PROJECTED";
    public static final String INCOME_MISSING = "INCOME_MISSING";
    public static final String REAL_ESTATE_SHARE_UNDEFINED = "REAL_ESTATE_SHARE_UNDEFINED";

    public record Warning(String code, Competence competence) {
    }

    public record MonthColumn(Competence competence, OwnerIncome income, OwnerOutflow outflow, Money companyReimbursable, Money surplus,
                              Money endBalance, boolean negative, Money receivablesPending) {

        public Money fixedBase() {
            return outflow.offCardFixed().add(outflow.subscriptionsMine()).add(outflow.realEstateMine());
        }
    }

    public OwnerStatement {
        months = List.copyOf(months);
        negativeMonths = List.copyOf(negativeMonths);
        warnings = List.copyOf(warnings);
    }

    public static OwnerStatement of(FinancialRecords records, Collection<RecurringTransaction> recurrences,
                                    Collection<RealEstateItem> realEstate, OwnerIncomeInputs incomeInputs,
                                    List<ThirdPartyReceipt> receipts, Competence from, int months, LocalDate asOf, int incomeLagMonths,
                                    List<Warning> inputWarnings) {
        return of(records, recurrences, realEstate, incomeInputs, receipts, Map.of(), from, months, asOf, incomeLagMonths, inputWarnings);
    }

    public static OwnerStatement of(FinancialRecords records, Collection<RecurringTransaction> recurrences,
                                    Collection<RealEstateItem> realEstate, OwnerIncomeInputs incomeInputs,
                                    List<ThirdPartyReceipt> receipts, Map<CardShareReceivables.ShareKey, Competence> expectedMonths,
                                    Competence from, int months, LocalDate asOf, int incomeLagMonths, List<Warning> inputWarnings) {
        OwnerSources sources = new OwnerSources(records, recurrences, realEstate);
        Competence current = Competence.from(asOf);
        LocalDate openingDate = from.isBefore(current) ? from.firstDay().minusDays(1) : asOf;
        Money opening = CashPosition.at(records, records.ledger(), openingDate).inAccounts();
        Map<Competence, Money> pending = pendingReceivables(records, receipts, expectedMonths);
        Money zero = Money.zero(CurrencyCode.BRL);
        List<Warning> warnings = new ArrayList<>();
        Set<Warning> seen = new HashSet<>();
        if (from.isAfter(current)) {
            warnings.add(new Warning(OPENING_BALANCE_NOT_PROJECTED, from));
        }
        inputWarnings.stream().filter(seen::add).forEach(warnings::add);
        List<MonthColumn> columns = new ArrayList<>();
        List<Competence> negative = new ArrayList<>();
        Money running = opening;
        for (int i = 0; i < months; i++) {
            Competence month = from.plusMonths(i);
            OwnerIncome income = OwnerIncome.compose(sources, incomeInputs, month.minusMonths(incomeLagMonths), asOf);
            OwnerOutflow outflow = OwnerOutflow.compose(sources, month, asOf);
            Money surplus = income.total().subtract(outflow.total());
            if (month.equals(current) && i > 0) {
                running = CashPosition.at(records, records.ledger(), asOf).inAccounts();
            }
            Money alreadyInBalance = month.equals(current) ? income.alreadyReceived() : zero;
            running = running.add(surplus).subtract(alreadyInBalance);
            boolean isNegative = surplus.isNegative();
            if (isNegative) {
                negative.add(month);
            }
            if (income.items().isEmpty()) {
                warnings.add(new Warning(INCOME_MISSING, month));
            }
            if (outflow.realEstateUndefined()) {
                warnings.add(new Warning(REAL_ESTATE_SHARE_UNDEFINED, month));
            }
            columns.add(new MonthColumn(month, income, outflow, outflow.companyReimbursable(), surplus, running, isNegative,
                    pending.getOrDefault(month, zero)));
        }
        return new OwnerStatement(asOf, incomeLagMonths, opening, columns, negative, warnings);
    }

    private static Map<Competence, Money> pendingReceivables(FinancialRecords records, List<ThirdPartyReceipt> receipts,
                                                                  Map<CardShareReceivables.ShareKey, Competence> expectedMonths) {
        Map<Competence, Money> pending = new TreeMap<>();
        for (CardShareReceivables.PurchaseLine line : CardShareReceivables.lines(records, receipts, expectedMonths)) {
            line.expectedMonths().forEach((competence, amounts) -> pending.merge(competence, amounts.pending(), Money::add));
        }
        return pending;
    }
}
