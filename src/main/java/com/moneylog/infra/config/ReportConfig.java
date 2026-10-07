package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.reports.usecase.GetIncomeExpenseSeriesUseCase;
import com.moneylog.core.finance.app.reports.usecase.GetIncomeExpenseSeriesUseCaseImpl;
import com.moneylog.core.finance.app.reports.usecase.GetSpendingByCategoryUseCase;
import com.moneylog.core.finance.app.reports.usecase.GetSpendingByCategoryUseCaseImpl;
import com.moneylog.core.sk.time.TimeProvider;

/**
 * Ligação dos relatórios do redesign (T184, T185): leituras puras, cada uma num instantâneo só ({@code REPEATABLE_READ} somente leitura,
 * ADR-008): o razão e as categorias saem do mesmo estado do banco. Sem travas de escrita.
 */
@Configuration(proxyBeanMethods = false)
public class ReportConfig {

    @Bean
    GetIncomeExpenseSeriesUseCase getIncomeExpenseSeriesUseCase(FinancialRecordsGateway records, TimeProvider time,
                                                                PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetIncomeExpenseSeriesUseCase.class,
                new GetIncomeExpenseSeriesUseCaseImpl(records, time), tm);
    }

    @Bean
    GetSpendingByCategoryUseCase getSpendingByCategoryUseCase(FinancialRecordsGateway records, CategoryGateway categories, TimeProvider time,
                                                              PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetSpendingByCategoryUseCase.class,
                new GetSpendingByCategoryUseCaseImpl(records, categories, time), tm);
    }
}
