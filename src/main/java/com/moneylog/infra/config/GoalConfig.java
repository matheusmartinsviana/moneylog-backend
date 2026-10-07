package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.usecase.ArchiveGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.ArchiveGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.CreateGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.CreateGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.GetGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.GetGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.ListGoalsUseCase;
import com.moneylog.core.finance.app.goals.usecase.ListGoalsUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.ReserveOnGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.ReserveOnGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.UnarchiveGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.UnarchiveGoalUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.UpdateGoalUseCase;
import com.moneylog.core.finance.app.goals.usecase.UpdateGoalUseCaseImpl;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.infra.goal.gateways.GoalGatewayImpl;
import com.moneylog.infra.goal.persistence.GoalRepositoryJpa;
import com.moneylog.infra.goal.persistence.GoalReservationRepositoryJpa;

/** Ligação da feature meta (Etapa 5, T93): gateway JPA, CRUD, reservas (lógicas) e arquivar. */
@Configuration(proxyBeanMethods = false)
public class GoalConfig {

    @Bean
    GoalGateway goalGateway(GoalRepositoryJpa goals, GoalReservationRepositoryJpa reservations, TimeProvider time) {
        return new GoalGatewayImpl(goals, reservations, time);
    }

    @Bean
    CreateGoalUseCase createGoalUseCase(GoalGateway goals, FinancialRecordsGateway records, IdempotencyGateway idempotency, IdGenerator ids,
                                        TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateGoalUseCase.class, new CreateGoalUseCaseImpl(goals, records, idempotency, ids, time), tm);
    }

    @Bean
    UpdateGoalUseCase updateGoalUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateGoalUseCase.class, new UpdateGoalUseCaseImpl(goals, records, time), tm);
    }

    @Bean
    GetGoalUseCase getGoalUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetGoalUseCase.class, new GetGoalUseCaseImpl(goals, records, time), tm);
    }

    @Bean
    ListGoalsUseCase listGoalsUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListGoalsUseCase.class, new ListGoalsUseCaseImpl(goals, records, time), tm);
    }

    @Bean
    ReserveOnGoalUseCase reserveOnGoalUseCase(GoalGateway goals, AccountGateway accounts, FinancialRecordsGateway records,
                                              IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.required(ReserveOnGoalUseCase.class,
                new ReserveOnGoalUseCaseImpl(goals, accounts, records, idempotency, ids, time), tm);
    }

    @Bean
    ArchiveGoalUseCase archiveGoalUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time,
                                          PlatformTransactionManager tm) {
        return TransactionalProxies.required(ArchiveGoalUseCase.class, new ArchiveGoalUseCaseImpl(goals, records, time), tm);
    }

    @Bean
    UnarchiveGoalUseCase unarchiveGoalUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.required(UnarchiveGoalUseCase.class, new UnarchiveGoalUseCaseImpl(goals, records, time), tm);
    }
}
