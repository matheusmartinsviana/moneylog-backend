package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.goal.app.gateways.GoalSnapshotGateway;
import com.moneylog.core.insights.app.InsightContextFactory;
import com.moneylog.core.insights.app.gateways.InsightDismissalGateway;
import com.moneylog.core.insights.app.usecase.InsightUseCases.DismissInsightUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.GetDailyInsightsUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.GetInsightHistoryUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.GetMonthlyInsightsUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCases.RestoreInsightUseCase;
import com.moneylog.core.insights.app.usecase.InsightUseCasesImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.insights.gateways.InsightDismissalGatewayImpl;

@Configuration(proxyBeanMethods = false)
public class InsightsConfig {

    @Bean
    InsightDismissalGateway insightDismissalGateway(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        return TransactionalProxies.required(InsightDismissalGateway.class, new InsightDismissalGatewayImpl(jdbc), tm);
    }

    @Bean
    InsightContextFactory insightContextFactory(FinancialRecordsGateway records, CommitmentSourcesGateway sources, CategoryGateway categories,
                                                GoalSnapshotGateway snapshots, TimeProvider time) {
        return new InsightContextFactory(records, sources, categories, snapshots, time);
    }

    @Bean
    GetMonthlyInsightsUseCase getMonthlyInsightsUseCase(InsightContextFactory contexts, InsightDismissalGateway dismissals, TimeProvider time,
                                                        PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetMonthlyInsightsUseCase.class,
                new InsightUseCasesImpl.GetMonthlyInsights(contexts, dismissals, time), tm);
    }

    @Bean
    GetDailyInsightsUseCase getDailyInsightsUseCase(InsightContextFactory contexts, InsightDismissalGateway dismissals, TimeProvider time,
                                                    PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetDailyInsightsUseCase.class,
                new InsightUseCasesImpl.GetDailyInsights(contexts, dismissals, time), tm);
    }

    @Bean
    GetInsightHistoryUseCase getInsightHistoryUseCase(InsightContextFactory contexts, InsightDismissalGateway dismissals, TimeProvider time,
                                                      PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetInsightHistoryUseCase.class,
                new InsightUseCasesImpl.GetInsightHistory(contexts, dismissals, time), tm);
    }

    @Bean
    DismissInsightUseCase dismissInsightUseCase(InsightContextFactory contexts, InsightDismissalGateway dismissals, IdGenerator ids,
                                                TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(DismissInsightUseCase.class,
                new InsightUseCasesImpl.DismissInsight(contexts, dismissals, ids, time), tm);
    }

    @Bean
    RestoreInsightUseCase restoreInsightUseCase(InsightContextFactory contexts, InsightDismissalGateway dismissals, TimeProvider time,
                                                PlatformTransactionManager tm) {
        return TransactionalProxies.required(RestoreInsightUseCase.class,
                new InsightUseCasesImpl.RestoreInsight(contexts, dismissals, time), tm);
    }
}
