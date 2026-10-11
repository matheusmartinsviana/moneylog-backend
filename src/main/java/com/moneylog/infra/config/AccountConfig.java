package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.finance.app.accounts.usecase.ArchiveAccountUseCase;
import com.moneylog.core.finance.app.accounts.usecase.ArchiveAccountUseCaseImpl;
import com.moneylog.core.finance.app.accounts.usecase.CreateAccountUseCase;
import com.moneylog.core.finance.app.accounts.usecase.CreateAccountUseCaseImpl;
import com.moneylog.core.finance.app.accounts.usecase.GetAccountUseCase;
import com.moneylog.core.finance.app.accounts.usecase.GetAccountUseCaseImpl;
import com.moneylog.core.finance.app.accounts.usecase.ListAccountsUseCase;
import com.moneylog.core.finance.app.accounts.usecase.ListAccountsUseCaseImpl;
import com.moneylog.core.finance.app.accounts.usecase.UnarchiveAccountUseCase;
import com.moneylog.core.finance.app.accounts.usecase.UnarchiveAccountUseCaseImpl;
import com.moneylog.core.finance.app.accounts.usecase.UpdateAccountUseCase;
import com.moneylog.core.finance.app.accounts.usecase.UpdateAccountUseCaseImpl;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.account.gateways.AccountGatewayImpl;
import com.moneylog.infra.account.persistence.AccountRepositoryJpa;
import com.moneylog.infra.card.persistence.BillingRuleRepositoryJpa;
import com.moneylog.infra.card.persistence.CardRepositoryJpa;
import com.moneylog.infra.debt.gateways.DebtHydrator;
import com.moneylog.infra.debt.persistence.DebtRepositoryJpa;
import com.moneylog.infra.finance.gateways.FinancialRecordsGatewayImpl;
import com.moneylog.infra.goal.persistence.GoalRepositoryJpa;
import com.moneylog.infra.goal.persistence.GoalReservationRepositoryJpa;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;
import com.moneylog.infra.transaction.gateways.TransactionHydrator;
import com.moneylog.infra.transaction.persistence.TransactionRepositoryJpa;

@Configuration(proxyBeanMethods = false)
public class AccountConfig {

    @Bean
    AccountGateway accountGateway(AccountRepositoryJpa repository, TimeProvider time) {
        return new AccountGatewayImpl(repository, time);
    }

    @Bean
    FinancialRecordsGateway financialRecordsGateway(AccountRepositoryJpa accounts, TransactionRepositoryJpa transactions,
                                                    LedgerPostingRepositoryJpa postings, CardRepositoryJpa cards,
                                                    BillingRuleRepositoryJpa billingRules, TransactionHydrator hydrator,
                                                    DebtRepositoryJpa debts, DebtHydrator debtHydrator,
                                                    GoalRepositoryJpa goals, GoalReservationRepositoryJpa goalReservations,
                                                    jakarta.persistence.EntityManager entityManager) {
        return new FinancialRecordsGatewayImpl(accounts, transactions, postings, cards, billingRules, hydrator, debts, debtHydrator,
                goals, goalReservations, entityManager);
    }

    @Bean
    CreateAccountUseCase createAccountUseCase(AccountGateway accounts, LedgerWriteGateway ledger,
                                              FinancialRecordsGateway records, IdGenerator ids, TimeProvider time,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateAccountUseCase.class,
                new CreateAccountUseCaseImpl(accounts, ledger, records, ids, time), tm);
    }

    @Bean
    UpdateAccountUseCase updateAccountUseCase(AccountGateway accounts, LedgerWriteGateway ledger,
                                              FinancialRecordsGateway records, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateAccountUseCase.class,
                new UpdateAccountUseCaseImpl(accounts, ledger, records, time), tm);
    }

    @Bean
    ArchiveAccountUseCase archiveAccountUseCase(AccountGateway accounts, FinancialRecordsGateway records, TimeProvider time,
                                                PlatformTransactionManager tm) {
        return TransactionalProxies.required(ArchiveAccountUseCase.class, new ArchiveAccountUseCaseImpl(accounts, records, time), tm);
    }

    @Bean
    UnarchiveAccountUseCase unarchiveAccountUseCase(AccountGateway accounts, FinancialRecordsGateway records, TimeProvider time,
                                                    PlatformTransactionManager tm) {
        return TransactionalProxies.required(UnarchiveAccountUseCase.class,
                new UnarchiveAccountUseCaseImpl(accounts, records, time), tm);
    }

    @Bean
    GetAccountUseCase getAccountUseCase(AccountGateway accounts, FinancialRecordsGateway records, TimeProvider time,
                                        PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetAccountUseCase.class, new GetAccountUseCaseImpl(accounts, records, time), tm);
    }

    @Bean
    ListAccountsUseCase listAccountsUseCase(AccountGateway accounts, FinancialRecordsGateway records, TimeProvider time,
                                            PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListAccountsUseCase.class, new ListAccountsUseCaseImpl(accounts, records, time), tm);
    }
}
