package com.moneylog.infra.config;

import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.AccountTransactionFactory;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.finance.app.transactions.usecase.CancelInstallmentsUseCase;
import com.moneylog.core.finance.app.transactions.usecase.CancelInstallmentsUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.GetTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.ListEntriesUseCase;
import com.moneylog.core.finance.app.transactions.usecase.ListEntriesUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.ListTransactionsUseCase;
import com.moneylog.core.finance.app.transactions.usecase.ListTransactionsUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCaseImpl;
import com.moneylog.core.finance.app.transactions.usecase.GetTransactionUseCaseImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.infra.card.gateways.InvoiceRowGateway;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;
import com.moneylog.infra.transaction.gateways.IdempotencyGatewayImpl;
import com.moneylog.infra.transaction.gateways.TransactionGatewayImpl;
import com.moneylog.infra.transaction.gateways.TransactionRevisionGatewayImpl;
import com.moneylog.infra.transaction.gateways.TransactionHydrator;
import com.moneylog.infra.transaction.persistence.InstallmentRepositoryJpa;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa;

@Configuration(proxyBeanMethods = false)
public class TransactionConfig {

    @Bean
    TransactionHydrator transactionHydrator(InstallmentRepositoryJpa installments, InvoiceRowGateway invoices) {
        return new TransactionHydrator(installments, invoices);
    }

    @Bean
    TransactionGateway transactionGateway(TransactionRepositoryJpa repository, InstallmentRepositoryJpa installments,
                                          LedgerPostingRepositoryJpa postings, TransactionHydrator hydrator,
                                          InvoiceRowGateway invoices, CardGateway cards, TimeProvider time,
                                          EntityManager entityManager) {
        return new TransactionGatewayImpl(repository, installments, postings, hydrator, invoices, cards, time, entityManager);
    }

    @Bean
    TransactionDetailsAssembler transactionDetailsAssembler(TransactionGateway transactions, AccountGateway accounts,
                                                           CategoryGateway categories, CardGateway cards, TimeProvider time,
                                                           DebtGateway debts, PersonGateway persons) {
        return new TransactionDetailsAssembler(transactions, accounts, categories, cards, time, debts, persons);
    }

    @Bean
    IdempotencyGateway idempotencyGateway(JdbcTemplate jdbc) {
        return new IdempotencyGatewayImpl(jdbc);
    }

    @Bean
    AccountTransactionFactory accountTransactionFactory(TransactionGateway transactions, AccountGateway accounts,
                                                        CategoryGateway categories, CardGateway cards, TimeProvider time,
                                                        PersonGateway persons, DebtGateway debts, IdGenerator ids) {
        return new AccountTransactionFactory(transactions, accounts, categories, cards, time, persons, debts, ids);
    }

    @Bean
    CreateTransactionUseCase createTransactionUseCase(TransactionGateway transactions, AccountTransactionFactory factory,
                                                      LedgerWriteGateway ledger, IdGenerator ids,
                                                      TransactionDetailsAssembler assembler, IdempotencyGateway idempotency,
                                                      TimeProvider time, DebtGateway debts, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateTransactionUseCase.class,
                new CreateTransactionUseCaseImpl(transactions, factory, ledger, ids, assembler, idempotency, time, debts), tm);
    }

    @Bean
    TransactionRevisionGateway transactionRevisionGateway(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new TransactionRevisionGatewayImpl(jdbc, objectMapper);
    }

    @Bean
    UpdateTransactionUseCase updateTransactionUseCase(TransactionGateway transactions, AccountGateway accounts,
                                                      AccountTransactionFactory factory, TransactionRevisionGateway revisions,
                                                      LedgerWriteGateway ledger, TransactionDetailsAssembler assembler,
                                                      TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateTransactionUseCase.class,
                new UpdateTransactionUseCaseImpl(transactions, accounts, factory, revisions, ledger, assembler, time), tm);
    }

    @Bean
    VoidTransactionUseCase voidTransactionUseCase(TransactionGateway transactions, AccountGateway accounts, CardGateway cards,
                                                  LedgerWriteGateway ledger, TransactionDetailsAssembler assembler, TimeProvider time,
                                                  DebtGateway debts, PlatformTransactionManager tm) {
        return TransactionalProxies.required(VoidTransactionUseCase.class,
                new VoidTransactionUseCaseImpl(transactions, accounts, cards, ledger, assembler, time, debts), tm);
    }

    @Bean
    CancelInstallmentsUseCase cancelInstallmentsUseCase(TransactionGateway transactions, CardGateway cards,
                                                        TransactionRevisionGateway revisions, LedgerWriteGateway ledger,
                                                        TransactionDetailsAssembler assembler, TimeProvider time,
                                                        PlatformTransactionManager tm) {
        return TransactionalProxies.required(CancelInstallmentsUseCase.class,
                new CancelInstallmentsUseCaseImpl(transactions, cards, revisions, ledger, assembler, time), tm);
    }

    @Bean
    ListTransactionsUseCase listTransactionsUseCase(TransactionGateway transactions, FinancialRecordsGateway records,
                                                    TransactionDetailsAssembler assembler, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListTransactionsUseCase.class, new ListTransactionsUseCaseImpl(transactions, records, assembler), tm);
    }

    @Bean
    ListEntriesUseCase listEntriesUseCase(FinancialRecordsGateway records, CategoryGateway categories, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListEntriesUseCase.class, new ListEntriesUseCaseImpl(records, categories), tm);
    }

    @Bean
    GetTransactionUseCase getTransactionUseCase(TransactionGateway transactions, TransactionDetailsAssembler assembler,
                                                PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetTransactionUseCase.class, new GetTransactionUseCaseImpl(transactions, assembler), tm);
    }
}
