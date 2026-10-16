package com.moneylog.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCaseImpl;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.app.gateways.TaxTableGateway;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;

@Configuration(proxyBeanMethods = false)
public class StatementConfig {

    @Bean
    @SuppressWarnings("java:S107")
    GetOwnerStatementUseCase getOwnerStatementUseCase(FinancialRecordsGateway records, CommitmentSourcesGateway recurrences,
                                                      CommitmentGateway commitments, IncomeProfileGateway profiles,
                                                      TaxTableGateway tables, CategoryGateway categories, ThirdPartyExpenseGateway marks,
                                                      TimeProvider time, PlatformTransactionManager tm,
                                                      @Value("${moneylog.owner-statement.income-lag-months:1}") int incomeLagMonths) {
        return TransactionalProxies.snapshotReadOnly(GetOwnerStatementUseCase.class,
                new GetOwnerStatementUseCaseImpl(records, recurrences, commitments, profiles, tables, categories, marks, time,
                        incomeLagMonths), tm);
    }
}
