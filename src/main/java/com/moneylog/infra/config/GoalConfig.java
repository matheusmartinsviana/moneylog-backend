package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.AddGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.CheckInGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.DeleteGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCases.UpdateGoalActionUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalActionUseCasesImpl;
import com.moneylog.core.finance.app.goals.usecase.RecordGoalSnapshotsUseCase;
import com.moneylog.core.finance.app.goals.usecase.RecordGoalSnapshotsUseCaseImpl;
import com.moneylog.core.goal.app.gateways.GoalSnapshotGateway;
import com.moneylog.infra.goal.GoalSnapshotJob;
import com.moneylog.infra.goal.gateways.GoalSnapshotGatewayImpl;
import com.moneylog.core.finance.app.goals.usecase.AnchorDebtPayoffBaselineUseCase;
import com.moneylog.core.finance.app.goals.usecase.AnchorDebtPayoffBaselineUseCaseImpl;
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
@EnableScheduling
public class GoalConfig {

    @Bean
    GoalGateway goalGateway(GoalRepositoryJpa goals, GoalReservationRepositoryJpa reservations, TimeProvider time, JdbcTemplate jdbc) {
        return new GoalGatewayImpl(goals, reservations, time, jdbc);
    }

    @Bean
    GoalSnapshotGateway goalSnapshotGateway(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        return new GoalSnapshotGatewayImpl(jdbc, tm);
    }

    @Bean
    GoalReadModel goalReadModel(CommitmentSourcesGateway sources, GoalSnapshotGateway snapshots, TimeProvider time) {
        return new GoalReadModel(sources, snapshots, time);
    }

    @Bean
    AddGoalActionUseCase addGoalActionUseCase(GoalGateway goals, FinancialRecordsGateway records, IdGenerator ids, TimeProvider time,
                                            GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(AddGoalActionUseCase.class, new GoalActionUseCasesImpl(goals, records, ids, time, model), tm);
    }

    @Bean
    UpdateGoalActionUseCase updateGoalActionUseCase(GoalGateway goals, FinancialRecordsGateway records, IdGenerator ids, TimeProvider time,
                                            GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateGoalActionUseCase.class, new GoalActionUseCasesImpl(goals, records, ids, time, model), tm);
    }

    @Bean
    DeleteGoalActionUseCase deleteGoalActionUseCase(GoalGateway goals, FinancialRecordsGateway records, IdGenerator ids, TimeProvider time,
                                            GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(DeleteGoalActionUseCase.class, new GoalActionUseCasesImpl(goals, records, ids, time, model), tm);
    }

    @Bean
    CheckInGoalActionUseCase checkInGoalActionUseCase(GoalGateway goals, FinancialRecordsGateway records, IdGenerator ids, TimeProvider time,
                                            GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CheckInGoalActionUseCase.class, new GoalActionUseCasesImpl(goals, records, ids, time, model), tm);
    }

    @Bean
    RecordGoalSnapshotsUseCase recordGoalSnapshotsUseCase(GoalGateway goals, FinancialRecordsGateway records, CommitmentSourcesGateway sources,
                                                         TimeProvider time, GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(RecordGoalSnapshotsUseCase.class,
                new RecordGoalSnapshotsUseCaseImpl(goals, records, sources, time, model), tm);
    }

    @Bean
    GoalSnapshotJob goalSnapshotJob(GoalSnapshotGateway snapshots, RecordGoalSnapshotsUseCase record) {
        return new GoalSnapshotJob(snapshots, record);
    }

    @Bean
    CreateGoalUseCase createGoalUseCase(GoalGateway goals, FinancialRecordsGateway records, IdempotencyGateway idempotency, IdGenerator ids,
                                        TimeProvider time, GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateGoalUseCase.class, new CreateGoalUseCaseImpl(goals, records, idempotency, ids, time, model), tm);
    }

    @Bean
    UpdateGoalUseCase updateGoalUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateGoalUseCase.class, new UpdateGoalUseCaseImpl(goals, records, time, model), tm);
    }

    @Bean
    GetGoalUseCase getGoalUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetGoalUseCase.class, new GetGoalUseCaseImpl(goals, records, time, model), tm);
    }

    @Bean
    ListGoalsUseCase listGoalsUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListGoalsUseCase.class, new ListGoalsUseCaseImpl(goals, records, time, model), tm);
    }

    @Bean
    ReserveOnGoalUseCase reserveOnGoalUseCase(GoalGateway goals, AccountGateway accounts, FinancialRecordsGateway records,
                                              IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                              GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(ReserveOnGoalUseCase.class,
                new ReserveOnGoalUseCaseImpl(goals, accounts, records, idempotency, ids, time, model), tm);
    }

    @Bean
    AnchorDebtPayoffBaselineUseCase anchorDebtPayoffBaselineUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time,
                                                                    GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(AnchorDebtPayoffBaselineUseCase.class,
                new AnchorDebtPayoffBaselineUseCaseImpl(goals, records, time, model), tm);
    }

    @Bean
    ArchiveGoalUseCase archiveGoalUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, GoalReadModel model,
                                          PlatformTransactionManager tm) {
        return TransactionalProxies.required(ArchiveGoalUseCase.class, new ArchiveGoalUseCaseImpl(goals, records, time, model), tm);
    }

    @Bean
    UnarchiveGoalUseCase unarchiveGoalUseCase(GoalGateway goals, FinancialRecordsGateway records, TimeProvider time, GoalReadModel model,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.required(UnarchiveGoalUseCase.class, new UnarchiveGoalUseCaseImpl(goals, records, time, model), tm);
    }
}
