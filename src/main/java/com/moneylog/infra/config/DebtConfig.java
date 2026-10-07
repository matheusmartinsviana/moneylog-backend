package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.app.usecase.ArchivePersonUseCase;
import com.moneylog.core.debt.app.usecase.ArchivePersonUseCaseImpl;
import com.moneylog.core.debt.app.usecase.CreatePersonUseCase;
import com.moneylog.core.debt.app.usecase.CreatePersonUseCaseImpl;
import com.moneylog.core.debt.app.usecase.GetDebtUseCase;
import com.moneylog.core.debt.app.usecase.GetDebtUseCaseImpl;
import com.moneylog.core.debt.app.usecase.GetPersonUseCase;
import com.moneylog.core.debt.app.usecase.GetPersonUseCaseImpl;
import com.moneylog.core.debt.app.usecase.ListDebtsUseCase;
import com.moneylog.core.debt.app.usecase.ListDebtsUseCaseImpl;
import com.moneylog.core.debt.app.usecase.ListPeopleUseCase;
import com.moneylog.core.debt.app.usecase.ListPeopleUseCaseImpl;
import com.moneylog.core.debt.app.usecase.UnarchivePersonUseCase;
import com.moneylog.core.debt.app.usecase.UnarchivePersonUseCaseImpl;
import com.moneylog.core.debt.app.usecase.UpdatePersonUseCase;
import com.moneylog.core.debt.app.usecase.UpdatePersonUseCaseImpl;
import com.moneylog.core.finance.app.debts.usecase.CancelDebtUseCase;
import com.moneylog.core.finance.app.debts.usecase.CancelDebtUseCaseImpl;
import com.moneylog.core.finance.app.debts.usecase.CreateDebtUseCase;
import com.moneylog.core.finance.app.debts.usecase.CreateDebtUseCaseImpl;
import com.moneylog.core.finance.app.debts.usecase.UpdateDebtUseCase;
import com.moneylog.core.finance.app.debts.usecase.UpdateDebtUseCaseImpl;
import com.moneylog.core.finance.app.debts.usecase.VoidDebtRecordUseCase;
import com.moneylog.core.finance.app.debts.usecase.VoidDebtRecordUseCaseImpl;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.people.IdempotentCreatePersonUseCase;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.infra.debt.gateways.DebtGatewayImpl;
import com.moneylog.infra.debt.gateways.DebtHydrator;
import com.moneylog.infra.debt.persistence.DebtRepositoryJpa;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;
import com.moneylog.infra.person.gateways.PersonGatewayImpl;
import com.moneylog.infra.person.persistence.PersonRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa;

/** Ligação das features pessoa e dívida (Etapa 4): gateways e casos de uso transacionais de pessoas e de leitura de dívidas. */
@Configuration(proxyBeanMethods = false)
public class DebtConfig {

    @Bean
    DebtHydrator debtHydrator(TransactionRepositoryJpa transactions, LedgerPostingRepositoryJpa postings) {
        return new DebtHydrator(transactions, postings);
    }

    @Bean
    PersonGateway personGateway(PersonRepositoryJpa repository, TimeProvider time) {
        return new PersonGatewayImpl(repository, time);
    }

    @Bean
    DebtGateway debtGateway(DebtRepositoryJpa repository, TransactionRepositoryJpa transactions, DebtHydrator hydrator,
                            JdbcTemplate jdbc, TimeProvider time) {
        return new DebtGatewayImpl(repository, transactions, hydrator, jdbc, time);
    }

    @Bean
    CreatePersonUseCase createPersonUseCase(PersonGateway people, DebtGateway debts, IdempotencyGateway idempotency, IdGenerator ids,
                                            TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreatePersonUseCase.class, new IdempotentCreatePersonUseCase(
                new CreatePersonUseCaseImpl(people, debts, ids, time), people, debts, idempotency, ids, time), tm);
    }

    @Bean
    UpdatePersonUseCase updatePersonUseCase(PersonGateway people, DebtGateway debts, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdatePersonUseCase.class, new UpdatePersonUseCaseImpl(people, debts, time), tm);
    }

    @Bean
    ArchivePersonUseCase archivePersonUseCase(PersonGateway people, DebtGateway debts, TimeProvider time,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.required(ArchivePersonUseCase.class, new ArchivePersonUseCaseImpl(people, debts, time), tm);
    }

    @Bean
    UnarchivePersonUseCase unarchivePersonUseCase(PersonGateway people, DebtGateway debts, TimeProvider time,
                                                  PlatformTransactionManager tm) {
        return TransactionalProxies.required(UnarchivePersonUseCase.class, new UnarchivePersonUseCaseImpl(people, debts, time), tm);
    }

    @Bean
    GetPersonUseCase getPersonUseCase(PersonGateway people, DebtGateway debts, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetPersonUseCase.class, new GetPersonUseCaseImpl(people, debts, time), tm);
    }

    @Bean
    ListPeopleUseCase listPeopleUseCase(PersonGateway people, DebtGateway debts, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListPeopleUseCase.class, new ListPeopleUseCaseImpl(people, debts, time), tm);
    }

    @Bean
    GetDebtUseCase getDebtUseCase(DebtGateway debts, PersonGateway people, CategoryGateway categories, TimeProvider time,
                                  PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetDebtUseCase.class, new GetDebtUseCaseImpl(debts, people, categories, time), tm);
    }

    @Bean
    ListDebtsUseCase listDebtsUseCase(DebtGateway debts, PersonGateway people, CategoryGateway categories, TimeProvider time,
                                      PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListDebtsUseCase.class, new ListDebtsUseCaseImpl(debts, people, categories, time), tm);
    }

    @Bean
    CreateDebtUseCase createDebtUseCase(PersonGateway people, DebtGateway debts, CategoryGateway categories, LedgerWriteGateway ledger,
                                        IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateDebtUseCase.class,
                new CreateDebtUseCaseImpl(people, debts, categories, ledger, idempotency, ids, time), tm);
    }

    @Bean
    UpdateDebtUseCase updateDebtUseCase(PersonGateway people, DebtGateway debts, CategoryGateway categories, TimeProvider time,
                                        PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateDebtUseCase.class, new UpdateDebtUseCaseImpl(people, debts, categories, time), tm);
    }

    @Bean
    CancelDebtUseCase cancelDebtUseCase(PersonGateway people, DebtGateway debts, CategoryGateway categories, LedgerWriteGateway ledger,
                                        TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CancelDebtUseCase.class,
                new CancelDebtUseCaseImpl(people, debts, categories, ledger, time), tm);
    }

    @Bean
    VoidDebtRecordUseCase voidDebtRecordUseCase(PersonGateway people, DebtGateway debts, CategoryGateway categories,
                                                LedgerWriteGateway ledger, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(VoidDebtRecordUseCase.class,
                new VoidDebtRecordUseCaseImpl(people, debts, categories, ledger, time), tm);
    }
}
