package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.app.gateways.PayrollLoanGateway;
import com.moneylog.core.income.app.gateways.TaxTableGateway;
import com.moneylog.core.income.app.usecase.ArchiveIncomeProfileUseCase;
import com.moneylog.core.income.app.usecase.ArchiveIncomeProfileUseCaseImpl;
import com.moneylog.core.income.app.usecase.CreateIncomeProfileUseCase;
import com.moneylog.core.income.app.usecase.CreateIncomeProfileUseCaseImpl;
import com.moneylog.core.income.app.usecase.GenerateIncomeOccurrencesUseCase;
import com.moneylog.core.income.app.usecase.GenerateIncomeOccurrencesUseCaseImpl;
import com.moneylog.core.income.app.usecase.GetIncomeProfileUseCase;
import com.moneylog.core.income.app.usecase.GetIncomeProfileUseCaseImpl;
import com.moneylog.core.income.app.usecase.ListIncomeProfilesUseCase;
import com.moneylog.core.income.app.usecase.ListIncomeProfilesUseCaseImpl;
import com.moneylog.core.income.app.usecase.ListTaxTablesUseCase;
import com.moneylog.core.income.app.usecase.ListTaxTablesUseCaseImpl;
import com.moneylog.core.income.app.usecase.PreviewIncomeProfileUseCase;
import com.moneylog.core.income.app.usecase.PreviewIncomeProfileUseCaseImpl;
import com.moneylog.core.income.app.usecase.UpdateIncomeProfileUseCase;
import com.moneylog.core.income.app.usecase.UpdateIncomeProfileUseCaseImpl;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.infra.income.gateways.IncomeProfileGatewayImpl;
import com.moneylog.infra.income.gateways.PayrollLoanGatewayImpl;
import com.moneylog.infra.income.gateways.TaxTableGatewayImpl;
import com.moneylog.infra.income.persistence.IncomeDeductionRepositoryJpa;
import com.moneylog.infra.income.persistence.IncomeOccurrenceRepositoryJpa;
import com.moneylog.infra.income.persistence.IncomeProfileRepositoryJpa;

@Configuration(proxyBeanMethods = false)
public class IncomeConfig {

    @Bean
    IncomeProfileGateway incomeProfileGateway(IncomeProfileRepositoryJpa profiles, IncomeDeductionRepositoryJpa deductions,
                                              IncomeOccurrenceRepositoryJpa occurrences, IdGenerator ids, TimeProvider time) {
        return new IncomeProfileGatewayImpl(profiles, deductions, occurrences, ids, time);
    }

    @Bean
    PayrollLoanGateway payrollLoanGateway(JdbcTemplate jdbc, IdGenerator ids, TimeProvider time) {
        return new PayrollLoanGatewayImpl(jdbc, ids, time);
    }

    @Bean
    TaxTableGateway taxTableGateway(JdbcTemplate jdbc) {
        return new TaxTableGatewayImpl(jdbc);
    }

    @Bean
    CreateIncomeProfileUseCase createIncomeProfileUseCase(IncomeProfileGateway profiles, DebtGateway debts, IdempotencyGateway idempotency,
                                                          IdGenerator ids, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateIncomeProfileUseCase.class,
                new CreateIncomeProfileUseCaseImpl(profiles, debts, idempotency, ids, time), tm);
    }

    @Bean
    UpdateIncomeProfileUseCase updateIncomeProfileUseCase(IncomeProfileGateway profiles, DebtGateway debts,
                                                          PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateIncomeProfileUseCase.class, new UpdateIncomeProfileUseCaseImpl(profiles, debts), tm);
    }

    @Bean
    ArchiveIncomeProfileUseCase archiveIncomeProfileUseCase(IncomeProfileGateway profiles, com.moneylog.core.debt.app.gateways.DebtGateway debts,
                                                            PlatformTransactionManager tm) {
        return TransactionalProxies.required(ArchiveIncomeProfileUseCase.class, new ArchiveIncomeProfileUseCaseImpl(profiles, debts), tm);
    }

    @Bean
    GetIncomeProfileUseCase getIncomeProfileUseCase(IncomeProfileGateway profiles, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetIncomeProfileUseCase.class, new GetIncomeProfileUseCaseImpl(profiles), tm);
    }

    @Bean
    ListIncomeProfilesUseCase listIncomeProfilesUseCase(IncomeProfileGateway profiles, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListIncomeProfilesUseCase.class, new ListIncomeProfilesUseCaseImpl(profiles), tm);
    }

    @Bean
    PreviewIncomeProfileUseCase previewIncomeProfileUseCase(IncomeProfileGateway profiles, TaxTableGateway tables,
                                                            PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(PreviewIncomeProfileUseCase.class,
                new PreviewIncomeProfileUseCaseImpl(profiles, tables), tm);
    }

    @Bean
    GenerateIncomeOccurrencesUseCase generateIncomeOccurrencesUseCase(IncomeProfileGateway profiles, TaxTableGateway tables,
                                                                      RecurrenceGateway recurrences, CategoryGateway categories,
                                                                      IdGenerator ids, PayrollLoanGateway payrollLoans,
                                                                      PlatformTransactionManager tm) {
        return TransactionalProxies.required(GenerateIncomeOccurrencesUseCase.class,
                new GenerateIncomeOccurrencesUseCaseImpl(profiles, tables, recurrences, categories, ids, payrollLoans), tm);
    }

    @Bean
    ListTaxTablesUseCase listTaxTablesUseCase(TaxTableGateway tables, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListTaxTablesUseCase.class, new ListTaxTablesUseCaseImpl(tables), tm);
    }
}
