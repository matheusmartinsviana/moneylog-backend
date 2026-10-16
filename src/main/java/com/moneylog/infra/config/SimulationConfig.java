package com.moneylog.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.finance.app.simulation.SimulatePurchaseUseCase;
import com.moneylog.core.finance.app.simulation.SimulatePurchaseUseCaseImpl;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.sk.time.TimeProvider;

@Configuration(proxyBeanMethods = false)
public class SimulationConfig {

    @Bean
    SimulatePurchaseUseCase simulatePurchaseUseCase(GetOwnerStatementUseCase statement, RecurrenceGateway recurrences,
                                                    CommitmentGateway commitments, TimeProvider time, PlatformTransactionManager tm,
                                                    @Value("${moneylog.simulation.calculated-months:1}") int calculatedMonths) {
        return TransactionalProxies.snapshotReadOnly(SimulatePurchaseUseCase.class,
                new SimulatePurchaseUseCaseImpl(statement, recurrences, commitments, time, calculatedMonths), tm);
    }
}
