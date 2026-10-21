package com.moneylog.infra.config;

import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.finance.app.imports.ApplyImportBatchUseCase;
import com.moneylog.core.finance.app.imports.ApplyImportBatchUseCaseImpl;
import com.moneylog.core.finance.app.imports.UndoImportBatchUseCase;
import com.moneylog.core.finance.app.imports.UndoImportBatchUseCaseImpl;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase;
import com.moneylog.core.importing.app.gateways.ExistingEntriesGateway;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway;
import com.moneylog.core.importing.app.gateways.ImportHistoryGateway;
import com.moneylog.core.importing.app.gateways.ImportIdempotencyGateway;
import com.moneylog.core.importing.app.gateways.ImportSourceGateway;
import com.moneylog.core.importing.app.usecase.CreateImportBatchUseCase;
import com.moneylog.core.importing.app.usecase.CreateImportBatchUseCaseImpl;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.DeleteCategorizationRuleUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.DiscardImportBatchUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.GetImportBatchUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.GetImportMappingUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.ListCategorizationRulesUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.ListImportBatchesUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.ListImportLinesUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.SaveImportMappingUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCasesImpl;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.BulkReviewImportLinesUseCase;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.ReviewImportLineUseCase;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCasesImpl;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportLineAlreadyRecordedException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.infra.importing.gateways.ExistingEntriesGatewayImpl;
import com.moneylog.infra.importing.gateways.ImportBatchGatewayImpl;
import com.moneylog.infra.importing.gateways.ImportHistoryGatewayImpl;
import com.moneylog.infra.importing.gateways.ImportIdempotencyGatewayImpl;
import com.moneylog.infra.importing.gateways.ImportSourceGatewayImpl;

@Configuration(proxyBeanMethods = false)
public class ImportConfig {

    @Bean
    ImportBatchGateway importBatchGateway(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        return new ImportBatchGatewayImpl(jdbc, tm);
    }

    @Bean
    ExistingEntriesGateway existingEntriesGateway(JdbcTemplate jdbc) {
        return new ExistingEntriesGatewayImpl(jdbc);
    }

    @Bean
    ImportHistoryGateway importHistoryGateway(JdbcTemplate jdbc, TimeProvider time) {
        return new ImportHistoryGatewayImpl(jdbc, time);
    }

    @Bean
    ImportSourceGateway importSourceGateway(JdbcTemplate jdbc) {
        return new ImportSourceGatewayImpl(jdbc);
    }

    @Bean
    ImportIdempotencyGateway importIdempotencyGateway(IdempotencyGateway idempotency) {
        return new ImportIdempotencyGatewayImpl(idempotency);
    }

    @Bean
    CreateImportBatchUseCase createImportBatchUseCase(ImportBatchGateway batches, ExistingEntriesGateway existing, ImportHistoryGateway history,
                                                      ImportSourceGateway sources, ImportIdempotencyGateway idempotency, IdGenerator ids,
                                                      TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateImportBatchUseCase.class,
                new CreateImportBatchUseCaseImpl(batches, existing, history, sources, idempotency, ids, time), tm);
    }

    @Bean
    GetImportBatchUseCase getImportBatchUseCase(ImportBatchGateway batches, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetImportBatchUseCase.class, new ImportQueryUseCasesImpl.GetBatch(batches), tm);
    }

    @Bean
    ListImportBatchesUseCase listImportBatchesUseCase(ImportBatchGateway batches, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListImportBatchesUseCase.class, new ImportQueryUseCasesImpl.ListBatches(batches), tm);
    }

    @Bean
    ListImportLinesUseCase listImportLinesUseCase(ImportBatchGateway batches, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListImportLinesUseCase.class, new ImportQueryUseCasesImpl.ListLines(batches), tm);
    }

    @Bean
    ReviewImportLineUseCase reviewImportLineUseCase(ImportBatchGateway batches, ImportHistoryGateway history, IdGenerator ids,
                                                    ExistingEntriesGateway existing, CategoryGateway categories, PlatformTransactionManager tm) {
        return TransactionalProxies.required(ReviewImportLineUseCase.class,
                new ReviewImportLinesUseCasesImpl.ReviewLine(batches, history, ids, existing, categories), tm);
    }

    @Bean
    BulkReviewImportLinesUseCase bulkReviewImportLinesUseCase(ImportBatchGateway batches, PlatformTransactionManager tm) {
        return TransactionalProxies.required(BulkReviewImportLinesUseCase.class, new ReviewImportLinesUseCasesImpl.BulkReview(batches), tm);
    }

    @Bean
    ApplyImportBatchUseCase applyImportBatchUseCase(ImportBatchGateway batches, CreateTransactionUseCase create, ImportIdempotencyGateway idempotency,
                                                    TimeProvider time, ExistingEntriesGateway existing, PlatformTransactionManager tm) {
        return TransactionalProxies.required(ApplyImportBatchUseCase.class,
                new ApplyImportBatchUseCaseImpl(batches, new DuplicateTranslatingCreate(create), idempotency, time, existing), tm);
    }

    @Bean
    DiscardImportBatchUseCase discardImportBatchUseCase(ImportBatchGateway batches, PlatformTransactionManager tm) {
        return TransactionalProxies.required(DiscardImportBatchUseCase.class, new ImportQueryUseCasesImpl.DiscardBatch(batches), tm);
    }

    private record DuplicateTranslatingCreate(CreateTransactionUseCase delegate) implements CreateTransactionUseCase {
        @Override
        public TransactionDetails handle(CreateTransactionCommand command, UUID idempotencyKey) {
            return delegate.handle(command, idempotencyKey);
        }

        @Override
        public TransactionDetails handleClaimed(CreateTransactionCommand command, UUID id, TransactionOrigin origin) {
            return delegate.handleClaimed(command, id, origin);
        }

        @Override
        public TransactionDetails handleImported(CreateTransactionCommand command, TransactionOrigin origin) {
            try {
                return delegate.handleImported(command, origin);
            } catch (DataIntegrityViolationException e) {
                throw new ImportLineAlreadyRecordedException();
            }
        }
    }

    @Bean
    UndoImportBatchUseCase undoImportBatchUseCase(ImportBatchGateway batches, VoidTransactionUseCase voids, TimeProvider time) {
        return new UndoImportBatchUseCaseImpl(batches, voids, time);
    }

    @Bean
    GetImportMappingUseCase getImportMappingUseCase(ImportHistoryGateway history, ImportSourceGateway sources, PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(GetImportMappingUseCase.class, new ImportQueryUseCasesImpl.GetMapping(history, sources), tm);
    }

    @Bean
    SaveImportMappingUseCase saveImportMappingUseCase(ImportHistoryGateway history, ImportSourceGateway sources, PlatformTransactionManager tm) {
        return TransactionalProxies.required(SaveImportMappingUseCase.class, new ImportQueryUseCasesImpl.SaveMapping(history, sources), tm);
    }

    @Bean
    ListCategorizationRulesUseCase listCategorizationRulesUseCase(ImportHistoryGateway history, PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(ListCategorizationRulesUseCase.class, new ImportQueryUseCasesImpl.ListRules(history), tm);
    }

    @Bean
    DeleteCategorizationRuleUseCase deleteCategorizationRuleUseCase(ImportHistoryGateway history, PlatformTransactionManager tm) {
        return TransactionalProxies.required(DeleteCategorizationRuleUseCase.class, new ImportQueryUseCasesImpl.DeleteRule(history), tm);
    }
}
