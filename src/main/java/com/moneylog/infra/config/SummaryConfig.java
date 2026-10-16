package com.moneylog.infra.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.finance.app.export.BackupExtrasReader;
import com.moneylog.core.finance.app.export.ExportBackupUseCase;
import com.moneylog.core.finance.app.export.ExportBackupUseCaseImpl;
import com.moneylog.core.finance.app.export.ExportTransactionsCsvUseCase;
import com.moneylog.core.finance.app.export.ExportTransactionsCsvUseCaseImpl;
import com.moneylog.core.finance.app.export.RestoreBackupUseCase;
import com.moneylog.core.finance.app.export.RestoreBackupUseCaseImpl;
import com.moneylog.core.finance.app.export.RestoreTargetGateway;
import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.gateways.PurchaseGroupBackupGateway;
import com.moneylog.core.finance.app.projection.GetProjectionUseCase;
import com.moneylog.core.finance.app.projection.GetProjectionUseCaseImpl;
import com.moneylog.core.finance.app.summary.GetMonthlySummaryUseCase;
import com.moneylog.core.finance.app.summary.GetMonthlySummaryUseCaseImpl;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.income.app.gateways.IncomeProfileGateway;
import com.moneylog.core.income.app.gateways.PayrollLoanGateway;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.transaction.app.gateways.ForeignChargeGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.infra.export.JdbcRestoreTargetGateway;
import com.moneylog.infra.export.controller.ExportRateLimiter;

@Configuration(proxyBeanMethods = false)
public class SummaryConfig {

    @Bean
    GetMonthlySummaryUseCase getMonthlySummaryUseCase(FinancialRecordsGateway records, CategoryGateway categories, TimeProvider time,
                                                      PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetMonthlySummaryUseCase.class,
                new GetMonthlySummaryUseCaseImpl(records, categories, time), tm);
    }

    @Bean
    GetProjectionUseCase getProjectionUseCase(FinancialRecordsGateway records, CommitmentSourcesGateway sources, TimeProvider time,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetProjectionUseCase.class, new GetProjectionUseCaseImpl(records, sources, time), tm);
    }

    @Bean
    ExportTransactionsCsvUseCase exportTransactionsCsvUseCase(FinancialRecordsGateway records, CategoryGateway categories,
                                                              PlatformTransactionManager tm,
                                                              @Value("${moneylog.export.max-transactions:" + ExportBackupUseCase.MAX_TRANSACTIONS + "}") int maxTransactions) {
        return TransactionalProxies.snapshotReadOnly(ExportTransactionsCsvUseCase.class,
                new ExportTransactionsCsvUseCaseImpl(records, categories, maxTransactions), tm);
    }

    @Bean
    BackupExtrasReader backupExtrasReader(CategoryGateway categories, ForeignChargeGateway foreignCharges,
                                          PurchaseGroupBackupGateway purchaseGroups, AssetGateway assets, IncomeProfileGateway incomeProfiles,
                                          PayrollLoanGateway payrollLoans, CommitmentGateway commitments, ThirdPartyExpenseGateway thirdParty) {
        return new BackupExtrasReader(categories, foreignCharges, purchaseGroups, assets, incomeProfiles, payrollLoans, commitments,
                thirdParty);
    }

    @Bean
    ExportBackupUseCase exportBackupUseCase(FinancialRecordsGateway records, CategoryGateway categories, PersonGateway people,
                                            RecurrenceGateway recurrences, BackupExtrasReader extras, TimeProvider time,
                                            PlatformTransactionManager tm,
                                            @Value("${moneylog.export.max-transactions:" + ExportBackupUseCase.MAX_TRANSACTIONS + "}") int maxTransactions) {
        return TransactionalProxies.snapshotReadOnly(ExportBackupUseCase.class,
                new ExportBackupUseCaseImpl(records, categories, people, recurrences, extras, time, maxTransactions), tm);
    }

    @Bean
    RestoreTargetGateway restoreTargetGateway(JdbcTemplate jdbc) {
        return new JdbcRestoreTargetGateway(jdbc);
    }

    @Bean
    @SuppressWarnings("java:S107")
    RestoreBackupUseCase restoreBackupUseCase(RestoreTargetGateway target, CategoryGateway categories,
                                              SeedSystemCategoriesUseCase seed, AccountGateway accounts, CardGateway cards,
                                              PersonGateway people, DebtGateway debts, RecurrenceGateway recurrences,
                                              TransactionGateway transactions, ForeignChargeGateway foreignCharges,
                                              LedgerWriteGateway ledger, GoalGateway goals, AssetGateway assets,
                                              IncomeProfileGateway incomeProfiles, PayrollLoanGateway payrollLoans,
                                              CommitmentGateway commitments, ThirdPartyExpenseGateway thirdParty,
                                              PurchaseGroupBackupGateway purchaseGroups, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(RestoreBackupUseCase.class,
                new RestoreBackupUseCaseImpl(target, categories, seed, accounts, cards, people, debts, recurrences, transactions,
                        foreignCharges, ledger, goals, assets, incomeProfiles, payrollLoans, commitments, thirdParty, purchaseGroups,
                        time), tm);
    }

    @Bean
    ExportRateLimiter exportRateLimiter(Clock clock, @Value("${moneylog.export.rate-limit.max-requests:6}") int maxRequests,
                                        @Value("${moneylog.export.rate-limit.window:PT1M}") Duration window) {
        return new ExportRateLimiter(clock, maxRequests, window);
    }
}
