package com.moneylog.infra.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.app.debts.usecase.GetThirdPartyReceivablesUseCase;
import com.moneylog.core.finance.app.debts.usecase.GetThirdPartyReceivablesUseCaseImpl;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.MarkThirdPartyExpenseUseCase;
import com.moneylog.core.thirdparty.app.MarkThirdPartyExpenseUseCaseImpl;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.finance.app.transactions.ShareSettlement;
import com.moneylog.core.thirdparty.app.gateways.ShareMigrationGateway;
import com.moneylog.core.finance.app.migration.ApplyShareMigrationUseCase;
import com.moneylog.core.finance.app.migration.ApplyShareMigrationUseCaseImpl;
import com.moneylog.core.finance.app.migration.PreviewShareMigrationUseCase;
import com.moneylog.core.finance.app.migration.PreviewShareMigrationUseCaseImpl;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.infra.thirdparty.ShareMigrationGatewayImpl;
import com.moneylog.infra.thirdparty.ThirdPartyExpenseGatewayImpl;

@Configuration
class ThirdPartyConfig {

    @Bean
    ThirdPartyExpenseGateway thirdPartyExpenseGateway(JdbcTemplate jdbc, TimeProvider time) {
        return new ThirdPartyExpenseGatewayImpl(jdbc, time);
    }

    @Bean
    MarkThirdPartyExpenseUseCase markThirdPartyExpenseUseCase(ThirdPartyExpenseGateway marks, TransactionGateway transactions,
                                                              PersonGateway people, IdempotencyGateway idempotency, IdGenerator ids,
                                                              TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(MarkThirdPartyExpenseUseCase.class,
                new MarkThirdPartyExpenseUseCaseImpl(marks, transactions, people, idempotency, ids, time), tm);
    }

    @Bean
    com.moneylog.core.thirdparty.app.RecordThirdPartyReceiptUseCase recordThirdPartyReceiptUseCase(ThirdPartyExpenseGateway marks,
            TransactionGateway transactions, PersonGateway people, AccountGateway accounts, LedgerWriteGateway ledger,
            IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time, com.moneylog.core.sk.ledger.LedgerReadGateway ledgerRead,
            PlatformTransactionManager tm) {
        return TransactionalProxies.required(com.moneylog.core.thirdparty.app.RecordThirdPartyReceiptUseCase.class,
                new com.moneylog.core.thirdparty.app.RecordThirdPartyReceiptUseCaseImpl(marks, transactions, people, accounts, ledger, idempotency, ids, time, ledgerRead), tm);
    }

    @Bean
    com.moneylog.core.thirdparty.app.UnmarkThirdPartyExpenseUseCase unmarkThirdPartyExpenseUseCase(ThirdPartyExpenseGateway marks,
                                                                                                    TransactionGateway transactions, PersonGateway people,
                                                                                                    AccountGateway accounts, LedgerWriteGateway ledger,
                                                                                                    com.moneylog.core.sk.ledger.LedgerReadGateway ledgerRead,
                                                                                                    TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(com.moneylog.core.thirdparty.app.UnmarkThirdPartyExpenseUseCase.class,
                new com.moneylog.core.thirdparty.app.UnmarkThirdPartyExpenseUseCaseImpl(marks, transactions, people, accounts, ledger, ledgerRead, time), tm);
    }

    @Bean
    GetThirdPartyReceivablesUseCase getThirdPartyReceivablesUseCase(FinancialRecordsGateway records, ThirdPartyExpenseGateway marks,
                                                                    PersonGateway people, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetThirdPartyReceivablesUseCase.class,
                new GetThirdPartyReceivablesUseCaseImpl(records, marks, people, time), tm);
    }

    @Bean
    com.moneylog.core.finance.app.debts.usecase.SetReceivableExpectedMonthUseCase setReceivableExpectedMonthUseCase(
            FinancialRecordsGateway records, ThirdPartyExpenseGateway marks, PlatformTransactionManager tm) {
        return TransactionalProxies.required(com.moneylog.core.finance.app.debts.usecase.SetReceivableExpectedMonthUseCase.class,
                new com.moneylog.core.finance.app.debts.usecase.SetReceivableExpectedMonthUseCaseImpl(records, marks), tm);
    }

    @Bean
    ShareMigrationGateway shareMigrationGateway(JdbcTemplate jdbc,
                                                @Value("${moneylog.migration.report-dir:${user.home}/.moneylog/import/relatorios}") String reportDir) {
        return new ShareMigrationGatewayImpl(jdbc, java.nio.file.Path.of(reportDir));
    }

    @Bean
    PreviewShareMigrationUseCase previewShareMigrationUseCase(FinancialRecordsGateway records, ThirdPartyExpenseGateway marks,
                                                              ShareMigrationGateway migrations, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(PreviewShareMigrationUseCase.class,
                new PreviewShareMigrationUseCaseImpl(records, marks, migrations), tm);
    }

    @Bean
    ApplyShareMigrationUseCase applyShareMigrationUseCase(FinancialRecordsGateway records, ThirdPartyExpenseGateway marks,
                                                          ShareMigrationGateway migrations, TransactionGateway transactions,
                                                          TransactionRevisionGateway revisions, LedgerWriteGateway ledger,
                                                          ShareSettlement shareSettlement, IdGenerator ids, TimeProvider time,
                                                          PlatformTransactionManager tm) {
        return TransactionalProxies.required(ApplyShareMigrationUseCase.class,
                new ApplyShareMigrationUseCaseImpl(records, marks, migrations, transactions, revisions, ledger, shareSettlement, ids, time), tm);
    }
}
