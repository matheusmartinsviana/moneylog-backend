package com.moneylog.core.finance.app.debts.usecase;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.CardShareReceivables;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ReceivableAlreadyReceivedException;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ReceivableExpectedMonthInvalidException;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ReceivableNotFoundException;

public class SetReceivableExpectedMonthUseCaseImpl implements SetReceivableExpectedMonthUseCase {

    private final FinancialRecordsGateway records;
    private final ThirdPartyExpenseGateway marks;

    public SetReceivableExpectedMonthUseCaseImpl(FinancialRecordsGateway records, ThirdPartyExpenseGateway marks) {
        this.records = records;
        this.marks = marks;
    }

    @Override
    public Result handle(SetReceivableExpectedMonthCommand c) {
        Competence month = c.month();
        if (month != null && (!FinancialDates.isInWindow(month.firstDay()) || !FinancialDates.isInWindow(month.lastDay()))) {
            throw new ReceivableExpectedMonthInvalidException();
        }
        FinancialRecords loaded = records.load(c.userId());
        java.util.List<ThirdPartyReceipt> receipts = marks.findAllReceipts(c.userId());
        Map<CardShareReceivables.ShareKey, Competence> current = CardShareReceivables.expectedMonthsOf(marks.findExpectedMonths(c.userId()));
        CardShareReceivables.Part part = find(loaded, receipts, current, c).orElseThrow(ReceivableNotFoundException::new);
        if (!part.pending().isPositive()) {
            throw new ReceivableAlreadyReceivedException();
        }
        long version;
        Map<CardShareReceivables.ShareKey, Competence> updated = new HashMap<>(current);
        CardShareReceivables.ShareKey key = new CardShareReceivables.ShareKey(c.installmentId(), c.personId());
        if (month == null) {
            marks.deleteExpectedMonth(c.userId(), c.installmentId(), c.personId());
            updated.remove(key);
            version = 0;
        } else {
            ReceivableExpectedMonth saved = marks.saveExpectedMonth(c.userId(), c.transactionId(), c.installmentId(), c.personId(), month,
                    c.version());
            updated.put(key, month);
            version = saved.version();
        }
        CardShareReceivables.Part after = find(loaded, receipts, updated, c).orElseThrow(ReceivableNotFoundException::new);
        return new Result(c.transactionId(), c.personId(), after, version);
    }

    private Optional<CardShareReceivables.Part> find(FinancialRecords loaded, java.util.List<ThirdPartyReceipt> receipts,
                                                     Map<CardShareReceivables.ShareKey, Competence> expected, SetReceivableExpectedMonthCommand c) {
        return CardShareReceivables.lines(loaded, receipts, expected).stream()
                .filter(l -> l.transactionId().equals(c.transactionId()) && l.personId().equals(c.personId()))
                .flatMap(l -> l.parts().stream()).filter(p -> c.installmentId().equals(p.installmentId())).findFirst();
    }
}
