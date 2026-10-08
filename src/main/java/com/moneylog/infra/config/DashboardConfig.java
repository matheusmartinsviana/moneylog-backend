package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.finance.app.dashboard.usecase.GetDashboardSummaryUseCase;
import com.moneylog.core.finance.app.dashboard.usecase.GetDashboardSummaryUseCaseImpl;
import com.moneylog.core.finance.app.dashboard.usecase.GetUpcomingUseCase;
import com.moneylog.core.finance.app.dashboard.usecase.GetUpcomingUseCaseImpl;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.finance.app.debts.usecase.GetDebtOverviewUseCase;
import com.moneylog.core.finance.app.debts.usecase.GetDebtOverviewUseCaseImpl;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.infra.dashboard.gateways.RecurrenceCommitmentSources;

/**
 * Ligação do dashboard (T29): casos de uso só de leitura sobre o carregamento único dos registros (S-79). As recorrências são a
 * fonte de compromissos persistida (T79); faturas e dívidas entram pelos próprios registros.
 */
@Configuration(proxyBeanMethods = false)
public class DashboardConfig {

    @Bean
    CommitmentSourcesGateway commitmentSourcesGateway(RecurrenceGateway recurrences) {
        return new RecurrenceCommitmentSources(recurrences);
    }

    @Bean
    GetDashboardSummaryUseCase getDashboardSummaryUseCase(FinancialRecordsGateway records, CommitmentSourcesGateway sources,
                                                          TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetDashboardSummaryUseCase.class, new GetDashboardSummaryUseCaseImpl(records, sources, time), tm);
    }

    @Bean
    GetUpcomingUseCase getUpcomingUseCase(FinancialRecordsGateway records, CommitmentSourcesGateway sources, TimeProvider time,
                                          PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetUpcomingUseCase.class, new GetUpcomingUseCaseImpl(records, sources, time), tm);
    }

    @Bean
    GetDebtOverviewUseCase getDebtOverviewUseCase(FinancialRecordsGateway records, CommitmentSourcesGateway sources, PersonGateway people,
                                                  TimeProvider time, PlatformTransactionManager tm,
                                                  com.moneylog.core.commitment.app.gateways.CommitmentGateway commitments) {
        return TransactionalProxies.snapshotReadOnly(GetDebtOverviewUseCase.class,
                new GetDebtOverviewUseCaseImpl(records, sources, people, time, commitments), tm);
    }
}
