package com.moneylog.infra.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.finance.app.export.ExportBackupUseCase;
import com.moneylog.core.finance.app.export.ExportBackupUseCaseImpl;
import com.moneylog.core.finance.app.export.ExportTransactionsCsvUseCase;
import com.moneylog.core.finance.app.export.ExportTransactionsCsvUseCaseImpl;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.projection.GetProjectionUseCase;
import com.moneylog.core.finance.app.projection.GetProjectionUseCaseImpl;
import com.moneylog.core.finance.app.summary.GetMonthlySummaryUseCase;
import com.moneylog.core.finance.app.summary.GetMonthlySummaryUseCaseImpl;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.export.controller.ExportRateLimiter;

/**
 * Ligação das leituras da Etapa 5 (T95 a T97): resumo mensal, projeções e exportação. Todas só leem, em um instantâneo único, o mesmo
 * carregamento dos registros do dashboard (S-79).
 */
@Configuration(proxyBeanMethods = false)
public class SummaryConfig {

    @Bean
    GetMonthlySummaryUseCase getMonthlySummaryUseCase(FinancialRecordsGateway records, CategoryGateway categories, TimeProvider time,
                                                      PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetMonthlySummaryUseCase.class,
                new GetMonthlySummaryUseCaseImpl(records, categories, time), tm);
    }

    @Bean
    GetProjectionUseCase getProjectionUseCase(FinancialRecordsGateway records, CommitmentSourcesGateway sources, TimeProvider time,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetProjectionUseCase.class, new GetProjectionUseCaseImpl(records, sources, time), tm);
    }

    @Bean
    ExportTransactionsCsvUseCase exportTransactionsCsvUseCase(FinancialRecordsGateway records, CategoryGateway categories,
                                                              PlatformTransactionManager tm,
                                                              @Value("${moneylog.export.max-transactions:" + ExportBackupUseCase.MAX_TRANSACTIONS + "}") int maxTransactions) {
        return TransactionalProxies.snapshotReadOnly(ExportTransactionsCsvUseCase.class,
                new ExportTransactionsCsvUseCaseImpl(records, categories, maxTransactions), tm);
    }

    @Bean
    ExportBackupUseCase exportBackupUseCase(FinancialRecordsGateway records, CategoryGateway categories, PersonGateway people,
                                            RecurrenceGateway recurrences, TimeProvider time, PlatformTransactionManager tm,
                                            @Value("${moneylog.export.max-transactions:" + ExportBackupUseCase.MAX_TRANSACTIONS + "}") int maxTransactions) {
        return TransactionalProxies.snapshotReadOnly(ExportBackupUseCase.class,
                new ExportBackupUseCaseImpl(records, categories, people, recurrences, time, maxTransactions), tm);
    }

    /** S-260: 6 pedidos de exportação por usuário por minuto (em memória, como o login). */
    @Bean
    ExportRateLimiter exportRateLimiter(Clock clock, @Value("${moneylog.export.rate-limit.max-requests:6}") int maxRequests,
                                        @Value("${moneylog.export.rate-limit.window:PT1M}") Duration window) {
        return new ExportRateLimiter(clock, maxRequests, window);
    }
}
