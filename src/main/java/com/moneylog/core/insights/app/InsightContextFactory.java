package com.moneylog.core.insights.app;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.app.goals.GoalViews;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.goal.app.gateways.GoalSnapshotGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

/**
 * Monta o {@link InsightContext} de uma requisição fazendo <b>uma</b> leitura do razão ({@code FinancialRecordsGateway.load}, que já traz as
 * metas), <b>uma</b> de recorrências (reaproveitada pelo cálculo das metas) e, só se alguma meta for calculada, <b>uma</b> consulta de
 * instantâneos. Proibido ler por insight (ADR-008, ADR-019).
 */
public final class InsightContextFactory {

    private final FinancialRecordsGateway records;
    private final CommitmentSourcesGateway sources;
    private final CategoryGateway categories;
    private final GoalSnapshotGateway snapshots;
    private final TimeProvider time;

    public InsightContextFactory(FinancialRecordsGateway records, CommitmentSourcesGateway sources, CategoryGateway categories,
                                 GoalSnapshotGateway snapshots, TimeProvider time) {
        this.records = records;
        this.sources = sources;
        this.categories = categories;
        this.snapshots = snapshots;
        this.time = time;
    }

    public InsightContext create(UUID userId) {
        FinancialRecords loaded = records.load(userId);
        Collection<RecurringTransaction> recurrences = sources.recurrences(userId);
        Map<UUID, String> names = categories.list(userId, null, true).stream().collect(Collectors.toMap(Category::id, Category::name));
        // as metas já vêm em loaded.goals(); o modelo de leitura reaproveita as recorrências desta requisição e não grava instantâneo
        GoalReadModel model = new GoalReadModel(u -> recurrences, snapshots, time);
        return new InsightContext(userId, time.today(), loaded, recurrences, names, () -> goalDetails(userId, loaded, model));
    }

    private static List<GoalDetails> goalDetails(UUID userId, FinancialRecords loaded, GoalReadModel model) {
        List<Versioned<Goal>> versioned = loaded.goals().stream()
                .map(g -> new Versioned<>(g, 0L, Instant.EPOCH, Instant.EPOCH)).toList();
        if (versioned.isEmpty()) {
            return List.of();
        }
        GoalViews.Context context = model.context(userId, versioned, loaded, false);
        return versioned.stream().map(v -> GoalViews.of(v, context)).toList();
    }
}
