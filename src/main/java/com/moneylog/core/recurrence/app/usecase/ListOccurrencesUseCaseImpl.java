package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.recurrence.app.OccurrenceListing;
import com.moneylog.core.recurrence.app.OccurrenceView;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway.RealizedOccurrence;
import com.moneylog.core.recurrence.domain.OccurrenceStatus;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.TimeProvider;

public class ListOccurrencesUseCaseImpl implements ListOccurrencesUseCase {

    private final RecurrenceGateway recurrences;
    private final TimeProvider time;

    public ListOccurrencesUseCaseImpl(RecurrenceGateway recurrences, TimeProvider time) {
        this.recurrences = recurrences;
        this.time = time;
    }

    @Override
    public OccurrenceListing handle(ListOccurrencesQuery query) {
        LocalDate today = time.today();
        LocalDate from = query.from() == null ? LocalDate.MIN : query.from();
        LocalDate to = query.to() == null ? (query.from() == null ? today : query.from()).plusDays(DEFAULT_WINDOW_DAYS) : query.to();
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from depois de to");
        }
        boolean pending = query.status() == null || query.status() == OccurrenceStatus.PENDING;
        boolean realized = query.status() == null || query.status() == OccurrenceStatus.REALIZED;
        boolean skipped = query.status() == null || query.status() == OccurrenceStatus.SKIPPED;
        List<RecurringTransaction> all = recurrences.findAll(query.userId());
        Map<UUID, Map<LocalDate, RealizedOccurrence>> realizedDetails = !realized ? Map.of()
                : recurrences.realizedOccurrences(query.userId(), all.stream().map(RecurringTransaction::id).toList());
        List<OccurrenceView> views = new ArrayList<>();
        for (RecurringTransaction r : all) {
            if (pending) {
                r.occurrences(r.schedule().startDate().isAfter(from) ? r.schedule().startDate() : from, to)
                        .forEach(o -> views.add(OccurrenceView.pending(r, o.date(), today)));
            }
            if (realized) {
                Map<LocalDate, RealizedOccurrence> details = realizedDetails.getOrDefault(r.id(), Map.of());
                for (LocalDate date : r.realizedDates()) {
                    if (!date.isBefore(from) && !date.isAfter(to)) {
                        RealizedOccurrence detail = details.get(date);
                        views.add(OccurrenceView.realized(r, date, detail == null ? r.amount() : detail.amount(),
                                detail == null ? null : detail.transactionId()));
                    }
                }
            }
            if (skipped) {
                for (LocalDate date : r.skippedDates()) {
                    if (!date.isBefore(from) && !date.isAfter(to)) {
                        views.add(OccurrenceView.skipped(r, date));
                    }
                }
            }
        }
        Comparator<OccurrenceView> byDate = Comparator.comparing(OccurrenceView::date).thenComparing(v -> v.recurrence().id());
        boolean truncated = capOverdue(views, byDate);
        views.sort(byDate);
        int start = (int) Math.min(query.page().offset(), views.size());
        int end = (int) Math.min((long) start + query.page().fetchSize(), views.size());
        return OccurrenceListing.of(Slice.fromOverfetch(views.subList(start, end), query.page()), truncated);
    }

    private static boolean capOverdue(List<OccurrenceView> views, Comparator<OccurrenceView> byDate) {
        List<OccurrenceView> overdue = views.stream().filter(v -> v.status() == OccurrenceStatus.PENDING && v.overdue()).toList();
        if (overdue.size() <= MAX_OVERDUE_OCCURRENCES) {
            return false;
        }
        Set<OccurrenceView> dropped = Collections.newSetFromMap(new IdentityHashMap<>());
        overdue.stream().sorted(byDate).limit(overdue.size() - (long) MAX_OVERDUE_OCCURRENCES).forEach(dropped::add);
        views.removeIf(dropped::contains);
        return true;
    }
}
