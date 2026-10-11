package com.moneylog.core.finance.app.dashboard.usecase;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.finance.app.dashboard.UpcomingView;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.Bucket;
import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.finance.domain.CommitmentKind;
import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;

public class GetUpcomingUseCaseImpl implements GetUpcomingUseCase {

    private final FinancialRecordsGateway records;
    private final CommitmentSourcesGateway sources;
    private final TimeProvider time;

    public GetUpcomingUseCaseImpl(FinancialRecordsGateway records, CommitmentSourcesGateway sources, TimeProvider time) {
        this.records = records;
        this.sources = sources;
        this.time = time;
    }

    @Override
    public UpcomingView handle(GetUpcomingQuery query) {
        LocalDate today = time.today();
        LocalDate from = query.from() == null ? today : query.from();
        LocalDate to = query.to() == null ? from.plusDays(DEFAULT_WINDOW_DAYS) : query.to();
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from depois de to");
        }
        FinancialRecords loaded = records.load(query.userId());
        CommitmentSchedule schedule = CommitmentSchedule.of(loaded, sources.recurrences(query.userId()), today);
        List<Commitment> items = schedule.items().stream()
                .filter(c -> c.bucket() == Bucket.OVERDUE || (!c.dueDate().isBefore(from) && !c.dueDate().isAfter(to))).toList();
        Money out = total(items, FlowDirection.OUT);
        Money in = total(items, FlowDirection.IN);
        long overdueOccurrences = items.stream().filter(GetUpcomingUseCaseImpl::isOverdueOccurrence).count();
        boolean truncated = overdueOccurrences > MAX_OVERDUE_OCCURRENCES;
        if (truncated) {
            long toDrop = overdueOccurrences - MAX_OVERDUE_OCCURRENCES;
            long[] dropped = {0};
            items = items.stream().filter(c -> !isOverdueOccurrence(c) || dropped[0]++ >= toDrop).toList();
        }
        return new UpcomingView(items, schedule.undated(), out, in, truncated);
    }

    private static boolean isOverdueOccurrence(Commitment c) {
        return c.kind() == CommitmentKind.RECURRENCE && c.bucket() == Bucket.OVERDUE;
    }

    private static Money total(List<Commitment> items, FlowDirection direction) {
        return Money.sum(items.stream().filter(c -> c.direction() == direction).map(Commitment::amount).toList(), CurrencyCode.BRL);
    }
}
