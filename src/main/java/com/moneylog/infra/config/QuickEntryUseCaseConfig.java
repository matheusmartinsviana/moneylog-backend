package com.moneylog.infra.config;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCase;
import com.moneylog.core.quickentry.app.CreateQuickEntryUseCase;
import com.moneylog.core.quickentry.app.CreateQuickEntryUseCaseImpl;
import com.moneylog.core.quickentry.app.QuickEntryLimits;
import com.moneylog.core.quickentry.app.gateways.QuickEntryAuditGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUsageGateway;
import com.moneylog.core.quickentry.app.gateways.UserTimeZoneGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.infra.quickentry.gateways.QuickEntryUsageGatewayImpl;
import com.moneylog.infra.quickentry.gateways.UserTimeZoneGatewayImpl;

@Configuration(proxyBeanMethods = false)
public class QuickEntryUseCaseConfig {

    @Bean
    UserTimeZoneGateway userTimeZoneGateway(JdbcTemplate jdbc) {
        return new UserTimeZoneGatewayImpl(jdbc);
    }

    @Bean
    QuickEntryUsageGateway quickEntryUsageGateway(JdbcTemplate jdbc) {
        return new QuickEntryUsageGatewayImpl(jdbc);
    }

    @Bean
    QuickEntryLimits quickEntryLimits(@Value("${moneylog.quick-entry.max-amount:0}") BigDecimal maxAmount,
                                      @Value("${moneylog.quick-entry.max-entries-per-day:0}") int maxEntriesPerDay,
                                      @Value("${moneylog.quick-entry.max-expense-per-day:0}") BigDecimal maxExpensePerDay,
                                      @Value("${moneylog.quick-entry.max-income-per-day:0}") BigDecimal maxIncomePerDay) {
        return new QuickEntryLimits(money(maxAmount), maxEntriesPerDay, money(maxExpensePerDay), money(maxIncomePerDay));
    }

    @Bean
    CreateQuickEntryUseCase createQuickEntryUseCase(CreateTransactionUseCase createTransaction, TransactionGateway transactions,
                                                    TransactionDetailsAssembler assembler, IdempotencyGateway idempotency,
                                                    AccountGateway accounts, CategoryGateway categories, QuickEntryAuditGateway audits,
                                                    UserTimeZoneGateway timeZones, QuickEntryUsageGateway usage, IdGenerator ids,
                                                    TimeProvider time, QuickEntryLimits limits, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateQuickEntryUseCase.class,
                new CreateQuickEntryUseCaseImpl(createTransaction, transactions, assembler, idempotency, accounts, categories, audits,
                        timeZones, usage, ids, time, limits), tm);
    }

    private static Money money(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return null;
        }
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > Money.SCALE) {
            throw new IllegalStateException("limite do lançamento rápido inválido: " + value);
        }
        return Money.of(value.setScale(Money.SCALE).toPlainString(), CurrencyCode.BRL);
    }
}
