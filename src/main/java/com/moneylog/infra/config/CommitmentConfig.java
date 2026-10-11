package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.commitment.app.usecase.CreateCommitmentUseCase;
import com.moneylog.core.commitment.app.usecase.CreateCommitmentUseCaseImpl;
import com.moneylog.core.commitment.app.usecase.GetCommitmentUseCase;
import com.moneylog.core.commitment.app.usecase.GetCommitmentUseCaseImpl;
import com.moneylog.core.commitment.app.usecase.ListCommitmentsUseCase;
import com.moneylog.core.commitment.app.usecase.ListCommitmentsUseCaseImpl;
import com.moneylog.core.commitment.app.usecase.MarkScheduleItemPaidUseCase;
import com.moneylog.core.commitment.app.usecase.MarkScheduleItemPaidUseCaseImpl;
import com.moneylog.core.commitment.app.usecase.UpdateCommitmentProgressUseCase;
import com.moneylog.core.commitment.app.usecase.UpdateCommitmentProgressUseCaseImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.infra.commitment.gateways.CommitmentGatewayImpl;

@Configuration
class CommitmentConfig {

    @Bean
    CommitmentGateway commitmentGateway(JdbcTemplate jdbc, TimeProvider time) {
        return new CommitmentGatewayImpl(jdbc, time);
    }

    @Bean
    CreateCommitmentUseCase createCommitmentUseCase(CommitmentGateway commitments, IdempotencyGateway idempotency, IdGenerator ids,
                                                    TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateCommitmentUseCase.class,
                new CreateCommitmentUseCaseImpl(commitments, idempotency, ids, time), tm);
    }

    @Bean
    GetCommitmentUseCase getCommitmentUseCase(CommitmentGateway commitments, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetCommitmentUseCase.class, new GetCommitmentUseCaseImpl(commitments), tm);
    }

    @Bean
    ListCommitmentsUseCase listCommitmentsUseCase(CommitmentGateway commitments, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListCommitmentsUseCase.class, new ListCommitmentsUseCaseImpl(commitments), tm);
    }

    @Bean
    MarkScheduleItemPaidUseCase markScheduleItemPaidUseCase(CommitmentGateway commitments, PlatformTransactionManager tm) {
        return TransactionalProxies.required(MarkScheduleItemPaidUseCase.class, new MarkScheduleItemPaidUseCaseImpl(commitments), tm);
    }

    @Bean
    UpdateCommitmentProgressUseCase updateCommitmentProgressUseCase(CommitmentGateway commitments, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateCommitmentProgressUseCase.class, new UpdateCommitmentProgressUseCaseImpl(commitments), tm);
    }
}
