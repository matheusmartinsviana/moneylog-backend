package com.moneylog.core.finance.app.goals.usecase;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.GoalReserves;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.TimeProvider;

public class ListGoalsUseCaseImpl implements ListGoalsUseCase {

    private final GoalGateway goals;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public ListGoalsUseCaseImpl(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time) {
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
        LocalDate today = time.today();
        GoalReserves reserves = GoalReserves.at(loaded, CashPosition.at(loaded, loaded.ledger(), today));
        Map<UUID, String> names = GoalViews.accountNames(loaded);
        com.moneylog.core.sk.money.Money debtTotal = GoalViews.debtTotal(loaded, today);
        // quem filtra é o domínio (status derivado), não o SQL (S-150); são poucas metas por usuário
        List<GoalDetails> all = goals.findAll(query.userId()).stream()
                .map(goal -> GoalViews.of(goal, names, reserves, debtTotal, today))
                .filter(details -> query.status() == null || details.status() == query.status())
                .sorted(Comparator.comparing((GoalDetails d) -> d.status().ordinal())
                        .thenComparing(d -> d.goal().priority().ordinal())
                        .thenComparing(d -> d.goal().deadline(), Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(d -> d.goal().name().toLowerCase(java.util.Locale.ROOT))
                        .thenComparing(d -> d.goal().id()))
                .toList();
        int from = (int) Math.min(page.offset(), all.size());
        return Slice.fromOverfetch(all.subList(from, Math.min(all.size(), from + page.fetchSize())), page);
    }
}
