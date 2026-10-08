package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.debt.app.gateways.PersonGateway;
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
            PersonGateway people, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(com.moneylog.core.thirdparty.app.RecordThirdPartyReceiptUseCase.class,
                new com.moneylog.core.thirdparty.app.RecordThirdPartyReceiptUseCaseImpl(marks, people, idempotency, ids, time), tm);
    }

    @Bean
    com.moneylog.core.thirdparty.app.UnmarkThirdPartyExpenseUseCase unmarkThirdPartyExpenseUseCase(ThirdPartyExpenseGateway marks,
                                                                                                    PlatformTransactionManager tm) {
        return TransactionalProxies.required(com.moneylog.core.thirdparty.app.UnmarkThirdPartyExpenseUseCase.class,
                new com.moneylog.core.thirdparty.app.UnmarkThirdPartyExpenseUseCaseImpl(marks), tm);
    }

    @Bean
    GetThirdPartyReceivablesUseCase getThirdPartyReceivablesUseCase(FinancialRecordsGateway records, ThirdPartyExpenseGateway marks,
                                                                    PersonGateway people, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetThirdPartyReceivablesUseCase.class,
                new GetThirdPartyReceivablesUseCaseImpl(records, marks, people, time), tm);
    }
}
