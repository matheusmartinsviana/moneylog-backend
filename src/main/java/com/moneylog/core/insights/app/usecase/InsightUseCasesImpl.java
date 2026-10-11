package com.moneylog.core.insights.app.usecase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.insights.app.InsightContext;
import com.moneylog.core.insights.app.InsightContextFactory;
import com.moneylog.core.insights.app.gateways.InsightDismissalGateway;
import com.moneylog.core.insights.app.usecase.InsightUseCases.DailyQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.DismissCommand;
import com.moneylog.core.insights.app.usecase.InsightUseCases.DismissInsightUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.GetDailyInsightsUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.GetInsightHistoryUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.GetMonthlyInsightsUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.HistoryMonth;
import com.moneylog.core.insights.app.usecase.InsightUseCases.HistoryQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.InsightsView;
import com.moneylog.core.insights.app.usecase.InsightUseCases.MonthlyQuery;
import com.moneylog.core.insights.app.usecase.InsightUseCases.RestoreCommand;
import com.moneylog.core.insights.app.usecase.InsightUseCases.RestoreInsightUseCase;
import com.moneylog.core.insights.domain.DailyInsights;
import com.moneylog.core.insights.domain.Dismissal;
import com.moneylog.core.insights.domain.InsightAggregator;
import com.moneylog.core.insights.domain.InsightIds;
import com.moneylog.core.insights.domain.InsightItem;
import com.moneylog.core.insights.domain.InsightSink;
import com.moneylog.core.insights.domain.MonthlyInsights;
import com.moneylog.core.insights.exception.InsightExceptions.InsightNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.TimeProvider;

public final class InsightUseCasesImpl {

    static final int RESOLVE_MONTHS = 12;

    private InsightUseCasesImpl() {
    }

    private static InsightsView page(List<InsightItem> all, int offset, int limit, InsightSink sink) {
        int from = Math.min(offset, all.size());
        int to = (int) Math.min(all.size(), (long) from + limit);
        return new InsightsView(all.subList(from, to), to < all.size(), sink.insufficient());
    }

    public static class GetMonthlyInsights implements GetMonthlyInsightsUseCase {
        private final InsightContextFactory contexts;
        private final InsightDismissalGateway dismissals;
        private final TimeProvider time;

        public GetMonthlyInsights(InsightContextFactory contexts, InsightDismissalGateway dismissals, TimeProvider time) {
            this.contexts = contexts;
            this.dismissals = dismissals;
            this.time = time;
        }

        @Override
        public InsightsView handle(MonthlyQuery q) {
            InsightContext context = contexts.create(q.userId());
            LocalDate today = time.today();
            Competence month = q.month() == null ? Competence.from(today) : q.month();
            InsightSink sink = new InsightSink();
            MonthlyInsights.compute(context.monthFacts(month), sink);
            List<InsightItem> all = InsightAggregator.aggregate(q.userId(), sink.insights(), dismissals.findActive(q.userId()), today,
                    q.includeDismissed());
            return page(all, q.offset(), q.limit(), sink);
        }
    }

    public static class GetDailyInsights implements GetDailyInsightsUseCase {
        private final InsightContextFactory contexts;
        private final InsightDismissalGateway dismissals;
        private final TimeProvider time;

        public GetDailyInsights(InsightContextFactory contexts, InsightDismissalGateway dismissals, TimeProvider time) {
            this.contexts = contexts;
            this.dismissals = dismissals;
            this.time = time;
        }

        @Override
        public InsightsView handle(DailyQuery q) {
            LocalDate today = time.today();
            LocalDate date = q.date() == null ? today : q.date();
            if (date.isAfter(today)) {
                throw new IllegalArgumentException("date depois de hoje");
            }
            InsightContext context = contexts.create(q.userId());
            InsightSink sink = new InsightSink();
            DailyInsights.compute(context.dayFacts(date), sink);
            List<InsightItem> all = InsightAggregator.aggregate(q.userId(), sink.insights(), dismissals.findActive(q.userId()), today,
                    q.includeDismissed());
            return page(all, q.offset(), q.limit(), sink);
        }
    }

    public static class GetInsightHistory implements GetInsightHistoryUseCase {
        private final InsightContextFactory contexts;
        private final InsightDismissalGateway dismissals;
        private final TimeProvider time;

        public GetInsightHistory(InsightContextFactory contexts, InsightDismissalGateway dismissals, TimeProvider time) {
            this.contexts = contexts;
            this.dismissals = dismissals;
            this.time = time;
        }

        @Override
        public List<HistoryMonth> handle(HistoryQuery q) {
            if (q.from().isAfter(q.to())) {
                throw new IllegalArgumentException("from depois de to");
            }
            InsightContext context = contexts.create(q.userId());
            LocalDate today = time.today();
            List<Dismissal> active = dismissals.findActive(q.userId());
            List<HistoryMonth> months = new ArrayList<>();
            for (Competence month = q.from(); !month.isAfter(q.to()); month = month.plusMonths(1)) {
                InsightSink sink = new InsightSink();
                MonthlyInsights.compute(context.monthFacts(month), sink);
                List<InsightItem> all = InsightAggregator.aggregate(q.userId(), sink.insights(), active, today, false);
                int to = Math.min(all.size(), q.limitPerMonth());
                months.add(new HistoryMonth(month, all.subList(0, to), to < all.size(), sink.insufficient()));
            }
            return months;
        }
    }

    private static InsightItem resolve(InsightContextFactory contexts, UUID userId, String insightId, LocalDate today, List<Dismissal> active) {
        String fingerprint = InsightIds.fingerprintOf(insightId);
        if (fingerprint == null) {
            throw new InsightNotFoundException(insightId);
        }
        InsightContext context = contexts.create(userId);
        InsightSink daily = new InsightSink();
        DailyInsights.compute(context.dayFacts(today), daily);
        for (InsightItem item : InsightAggregator.aggregate(userId, daily.insights(), active, today, true)) {
            if (item.id().equals(insightId)) {
                return item;
            }
        }
        Competence current = Competence.from(today);
        for (int back = 0; back < RESOLVE_MONTHS; back++) {
            InsightSink monthly = new InsightSink();
            MonthlyInsights.compute(context.monthFacts(current.minusMonths(back)), monthly);
            for (InsightItem item : InsightAggregator.aggregate(userId, monthly.insights(), active, today, true)) {
                if (item.id().equals(insightId)) {
                    return item;
                }
            }
        }
        throw new InsightNotFoundException(insightId);
    }

    public static class DismissInsight implements DismissInsightUseCase {
        private final InsightContextFactory contexts;
        private final InsightDismissalGateway dismissals;
        private final IdGenerator ids;
        private final TimeProvider time;

        public DismissInsight(InsightContextFactory contexts, InsightDismissalGateway dismissals, IdGenerator ids, TimeProvider time) {
            this.contexts = contexts;
            this.dismissals = dismissals;
            this.ids = ids;
            this.time = time;
        }

        @Override
        public InsightItem handle(DismissCommand c) {
            LocalDate today = time.today();
            List<Dismissal> active = dismissals.findActive(c.userId());
            InsightItem item = resolve(contexts, c.userId(), c.insightId(), today, active);
            String fingerprint = InsightIds.fingerprintOf(c.insightId());
            boolean alreadySameScope = active.stream().anyMatch(d -> d.scope() == c.scope() && d.hides(fingerprint, item.insight(), today));
            if (!alreadySameScope) {
                dismissals.insert(Dismissal.of(ids.next(), c.userId(), item.insight(), fingerprint, c.scope(), today, time.now()));
            }
            return new InsightItem(item.insight(), item.id(), true);
        }
    }

    public static class RestoreInsight implements RestoreInsightUseCase {
        private final InsightContextFactory contexts;
        private final InsightDismissalGateway dismissals;
        private final TimeProvider time;

        public RestoreInsight(InsightContextFactory contexts, InsightDismissalGateway dismissals, TimeProvider time) {
            this.contexts = contexts;
            this.dismissals = dismissals;
            this.time = time;
        }

        @Override
        public InsightItem handle(RestoreCommand c) {
            LocalDate today = time.today();
            List<Dismissal> active = dismissals.findActive(c.userId());
            InsightItem item = resolve(contexts, c.userId(), c.insightId(), today, active);
            String fingerprint = InsightIds.fingerprintOf(c.insightId());
            List<UUID> toRevoke = active.stream().filter(d -> d.hides(fingerprint, item.insight(), today)).map(Dismissal::id).toList();
            if (!toRevoke.isEmpty()) {
                dismissals.revoke(c.userId(), toRevoke, time.now());
            }
            return new InsightItem(item.insight(), item.id(), false);
        }
    }
}
