package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeCardRecurrenceUseCase;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeCardRecurrenceUseCaseImpl;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeOccurrenceUseCase;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeOccurrenceUseCaseImpl;
import com.moneylog.core.finance.app.recurrences.usecase.SkipOccurrenceUseCase;
import com.moneylog.core.finance.app.recurrences.usecase.SkipOccurrenceUseCaseImpl;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.recurrence.app.RecurrenceViews;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.app.usecase.CreateRecurrenceUseCase;
import com.moneylog.core.recurrence.app.usecase.CreateRecurrenceUseCaseImpl;
import com.moneylog.core.recurrence.app.usecase.DeactivateRecurrenceUseCase;
import com.moneylog.core.recurrence.app.usecase.DeactivateRecurrenceUseCaseImpl;
import com.moneylog.core.recurrence.app.usecase.EndRecurrenceUseCase;
import com.moneylog.core.recurrence.app.usecase.EndRecurrenceUseCaseImpl;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceSummaryUseCase;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceSummaryUseCaseImpl;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceUseCase;
import com.moneylog.core.recurrence.app.usecase.GetRecurrenceUseCaseImpl;
import com.moneylog.core.recurrence.app.usecase.ListOccurrencesUseCase;
import com.moneylog.core.recurrence.app.usecase.ListOccurrencesUseCaseImpl;
import com.moneylog.core.recurrence.app.usecase.ListRecurrencesUseCase;
import com.moneylog.core.recurrence.app.usecase.ListRecurrencesUseCaseImpl;
import com.moneylog.core.recurrence.app.usecase.UpdateRecurrenceUseCase;
import com.moneylog.core.recurrence.app.usecase.UpdateRecurrenceUseCaseImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.infra.recurrence.gateways.RecurrenceGatewayImpl;
import com.moneylog.infra.recurrence.persistence.RecurrenceRepositoryJpa;
import com.moneylog.infra.recurrence.persistence.RecurrenceSkipRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa;

@Configuration(proxyBeanMethods = false)
public class RecurrenceConfig {

    @Bean
    RecurrenceGateway recurrenceGateway(RecurrenceRepositoryJpa repository, RecurrenceSkipRepositoryJpa skips,
                                        TransactionRepositoryJpa transactions, IdGenerator ids, TimeProvider time) {
        return new RecurrenceGatewayImpl(repository, skips, transactions, ids, time);
    }

    @Bean
    RecurrenceViews recurrenceViews(CategoryGateway categories, AccountGateway accounts, CardGateway cards) {
        return new RecurrenceViews(categories, accounts, cards);
    }

    @Bean
    CreateRecurrenceUseCase createRecurrenceUseCase(RecurrenceGateway recurrences, CategoryGateway categories, AccountGateway accounts,
                                                    CardGateway cards, IdempotencyGateway idempotency, IdGenerator ids,
                                                    TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateRecurrenceUseCase.class,
                new CreateRecurrenceUseCaseImpl(recurrences, categories, accounts, cards, idempotency, ids, time), tm);
    }

    @Bean
    UpdateRecurrenceUseCase updateRecurrenceUseCase(RecurrenceGateway recurrences, CategoryGateway categories, AccountGateway accounts,
                                                    CardGateway cards, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateRecurrenceUseCase.class,
                new UpdateRecurrenceUseCaseImpl(recurrences, categories, accounts, cards, time), tm);
    }

    @Bean
    EndRecurrenceUseCase endRecurrenceUseCase(RecurrenceGateway recurrences, RecurrenceViews views, TimeProvider time,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.required(EndRecurrenceUseCase.class, new EndRecurrenceUseCaseImpl(recurrences, views, time), tm);
    }

    @Bean
    DeactivateRecurrenceUseCase deactivateRecurrenceUseCase(RecurrenceGateway recurrences, RecurrenceViews views, TimeProvider time,
                                                            PlatformTransactionManager tm) {
        return TransactionalProxies.required(DeactivateRecurrenceUseCase.class,
                new DeactivateRecurrenceUseCaseImpl(recurrences, views, time), tm);
    }

    @Bean
    GetRecurrenceUseCase getRecurrenceUseCase(RecurrenceGateway recurrences, RecurrenceViews views, TimeProvider time,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetRecurrenceUseCase.class, new GetRecurrenceUseCaseImpl(recurrences, views, time), tm);
    }

    @Bean
    ListRecurrencesUseCase listRecurrencesUseCase(RecurrenceGateway recurrences, RecurrenceViews views, TimeProvider time,
                                                  PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListRecurrencesUseCase.class,
                new ListRecurrencesUseCaseImpl(recurrences, views, time), tm);
    }

    @Bean
    ListOccurrencesUseCase listOccurrencesUseCase(RecurrenceGateway recurrences, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListOccurrencesUseCase.class, new ListOccurrencesUseCaseImpl(recurrences, time), tm);
    }

    @Bean
    GetRecurrenceSummaryUseCase getRecurrenceSummaryUseCase(RecurrenceGateway recurrences, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetRecurrenceSummaryUseCase.class, new GetRecurrenceSummaryUseCaseImpl(recurrences, time), tm);
    }

    @Bean
    RealizeCardRecurrenceUseCase realizeCardRecurrenceUseCase(RecurrenceGateway recurrences, CardGateway cards, CategoryGateway categories,
                                                              TransactionGateway transactions, LedgerWriteGateway ledger,
                                                              TransactionDetailsAssembler assembler, IdGenerator ids, TimeProvider time,
                                                              PlatformTransactionManager tm) {
        return TransactionalProxies.required(RealizeCardRecurrenceUseCase.class,
                new RealizeCardRecurrenceUseCaseImpl(recurrences, cards, categories, transactions, ledger, assembler, ids, time), tm);
    }

    @Bean
    RealizeOccurrenceUseCase realizeOccurrenceUseCase(RecurrenceGateway recurrences, AccountGateway accounts, CategoryGateway categories,
                                                      TransactionGateway transactions, LedgerWriteGateway ledger,
                                                      TransactionDetailsAssembler assembler, IdempotencyGateway idempotency,
                                                      RealizeCardRecurrenceUseCase cardRealization, IdGenerator ids, TimeProvider time,
                                                      PlatformTransactionManager tm) {
        return TransactionalProxies.required(RealizeOccurrenceUseCase.class,
                new RealizeOccurrenceUseCaseImpl(recurrences, accounts, categories, transactions, ledger, assembler, idempotency,
                        cardRealization, ids, time), tm);
    }

    @Bean
    SkipOccurrenceUseCase skipOccurrenceUseCase(RecurrenceGateway recurrences, PlatformTransactionManager tm) {
        return TransactionalProxies.required(SkipOccurrenceUseCase.class, new SkipOccurrenceUseCaseImpl(recurrences), tm);
    }
}
