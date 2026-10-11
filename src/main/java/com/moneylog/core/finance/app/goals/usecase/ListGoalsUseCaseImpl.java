package com.moneylog.core.finance.app.goals.usecase;

import java.util.Comparator;
import java.util.List;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.TimeProvider;

public class ListGoalsUseCaseImpl implements ListGoalsUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;
    private final GoalReadModel model;

    public ListGoalsUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, GoalReadModel model) {
        this.model = model;
        this.goals = goals;
        this.records = records;
        this.time = time;
    }

    @Override
    public Slice<GoalDetails> handle(ListGoalsQuery query) {
        PageQuery page = query.page();
        if (page.isBeyondAnyData()) {
            return Slice.empty();
        }
        FinancialRecords loaded = records.load(query.userId());
        List<Versioned<Goal>> stored = goals.findAll(query.userId());
        GoalViews.Context context = model.context(query.userId(), stored, loaded, true);
        List<GoalDetails> all = stored.stream()
                .map(goal -> GoalViews.of(goal, context))
                .filter(details -> query.status() == null || details.status() == query.status())
                .filter(details -> query.featured() == null || details.goal().isFeatured() == query.featured())
                .sorted(Comparator.comparing((GoalDetails d) -> d.status().ordinal())
                        .thenComparing(d -> d.goal().priority().ordinal())
                        .thenComparing(d -> d.goal().deadline(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(d -> d.goal().name() == null ? "" : d.goal().name().toLowerCase(java.util.Locale.ROOT))
                        .thenComparing(d -> d.goal().id()))
                .toList();
        int from = (int) Math.min(page.offset(), all.size());
        return Slice.fromOverfetch(all.subList(from, Math.min(all.size(), from + page.fetchSize())), page);
    }
}
