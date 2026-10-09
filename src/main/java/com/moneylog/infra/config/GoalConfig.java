package com.moneylog.infra.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
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
import com.moneylog.core.goal.app.gateways.PhotoStorage;
import com.moneylog.core.goal.photo.PhotoImagePipeline;
import com.moneylog.core.goal.photo.PhotoLimits;
import com.moneylog.core.finance.app.goals.usecase.GetGoalPhotoContentUseCaseImpl;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.GetGoalPhotoContentUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.RemoveGoalPhotoUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.ReorderGoalPhotosUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.SetGoalFeaturedUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.SetGoalPhotoCoverUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.UploadGoalPhotoUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCasesImpl;
import com.moneylog.core.finance.app.goals.usecase.SetGoalFeaturedUseCaseImpl;
import com.moneylog.infra.goal.controller.PhotoUploadRateLimiter;
import com.moneylog.infra.goal.gateways.DbPhotoStorage;
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

    // ------------------------------------------------------------------ fotos e destaque (ADR-018)

    @Bean
    PhotoLimits photoLimits(@Value("${moneylog.goals.photos.max-per-goal:8}") int maxPerGoal,
                            @Value("${moneylog.goals.photos.max-per-user:40}") int maxPerUser,
                            @Value("${moneylog.goals.photos.max-bytes-per-user:41943040}") long maxBytesPerUser,
                            @Value("${moneylog.goals.photos.max-upload-bytes:8388608}") long maxUploadBytes,
                            @Value("${moneylog.goals.photos.max-pixels:24000000}") long maxPixels,
                            @Value("${moneylog.goals.photos.max-side:8000}") int maxSide) {
        return new PhotoLimits(maxPerGoal, maxPerUser, maxBytesPerUser, maxUploadBytes, maxPixels, maxSide);
    }

    @Bean
    PhotoImagePipeline photoImagePipeline(PhotoLimits limits) {
        return new PhotoImagePipeline(limits);
    }

    @Bean
    PhotoStorage photoStorage(JdbcTemplate jdbc) {
        return new DbPhotoStorage(jdbc);
    }

    @Bean
    PhotoUploadRateLimiter photoUploadRateLimiter(Clock clock, @Value("${moneylog.goals.photos.rate-limit.max-requests:30}") int maxRequests,
                                                  @Value("${moneylog.goals.photos.rate-limit.window:PT1M}") Duration window) {
        return new PhotoUploadRateLimiter(clock, maxRequests, window);
    }

    @Bean
    UploadGoalPhotoUseCase uploadGoalPhotoUseCase(GoalGateway goals, PhotoStorage storage, PhotoImagePipeline pipeline, PhotoLimits limits,
                                                  FinancialRecordsGateway records, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                                  GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UploadGoalPhotoUseCase.class,
                new GoalPhotoUseCasesImpl(goals, storage, pipeline, limits, records, idempotency, ids, time, model), tm);
    }

    @Bean
    ReorderGoalPhotosUseCase reorderGoalPhotosUseCase(GoalGateway goals, PhotoStorage storage, PhotoImagePipeline pipeline, PhotoLimits limits,
                                                      FinancialRecordsGateway records, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                                      GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(ReorderGoalPhotosUseCase.class,
                new GoalPhotoUseCasesImpl(goals, storage, pipeline, limits, records, idempotency, ids, time, model), tm);
    }

    @Bean
    SetGoalPhotoCoverUseCase setGoalPhotoCoverUseCase(GoalGateway goals, PhotoStorage storage, PhotoImagePipeline pipeline, PhotoLimits limits,
                                                      FinancialRecordsGateway records, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                                      GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(SetGoalPhotoCoverUseCase.class,
                new GoalPhotoUseCasesImpl(goals, storage, pipeline, limits, records, idempotency, ids, time, model), tm);
    }

    @Bean
    RemoveGoalPhotoUseCase removeGoalPhotoUseCase(GoalGateway goals, PhotoStorage storage, PhotoImagePipeline pipeline, PhotoLimits limits,
                                                  FinancialRecordsGateway records, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                                  GoalReadModel model, PlatformTransactionManager tm) {
        return TransactionalProxies.required(RemoveGoalPhotoUseCase.class,
                new GoalPhotoUseCasesImpl(goals, storage, pipeline, limits, records, idempotency, ids, time, model), tm);
    }

    @Bean
    GetGoalPhotoContentUseCase getGoalPhotoContentUseCase(PhotoStorage storage, PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(GetGoalPhotoContentUseCase.class, new GetGoalPhotoContentUseCaseImpl(storage), tm);
    }

    @Bean
    SetGoalFeaturedUseCase setGoalFeaturedUseCase(GoalGateway goals, FinancialRecordsGateway records, GoalReadModel model,
                                                  PlatformTransactionManager tm) {
        return TransactionalProxies.required(SetGoalFeaturedUseCase.class, new SetGoalFeaturedUseCaseImpl(goals, records, model), tm);
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
