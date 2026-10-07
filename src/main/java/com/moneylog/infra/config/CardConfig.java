package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.finance.app.cards.usecase.ArchiveCardUseCase;
import com.moneylog.core.finance.app.cards.usecase.ArchiveCardUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.ChangeCardBillingDaysUseCase;
import com.moneylog.core.finance.app.cards.usecase.ChangeCardBillingDaysUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.CreateCardUseCase;
import com.moneylog.core.finance.app.cards.usecase.CreateCardUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.GetCardUseCase;
import com.moneylog.core.finance.app.cards.usecase.GetCardUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.GetFutureInstallmentsUseCase;
import com.moneylog.core.finance.app.cards.usecase.GetFutureInstallmentsUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.GetInvoiceUseCase;
import com.moneylog.core.finance.app.cards.usecase.GetInvoiceUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.ListCardsUseCase;
import com.moneylog.core.finance.app.cards.usecase.ListCardsUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.ListInvoicesUseCase;
import com.moneylog.core.finance.app.cards.usecase.ListInvoicesUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.PayInvoiceUseCase;
import com.moneylog.core.finance.app.cards.usecase.PayInvoiceUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.PreviewBillingChangeUseCase;
import com.moneylog.core.finance.app.cards.usecase.PreviewBillingChangeUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.PreviewCardPurchaseUseCase;
import com.moneylog.core.finance.app.cards.usecase.PreviewCardPurchaseUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.PreviewInvoicePaymentUseCase;
import com.moneylog.core.finance.app.cards.usecase.PreviewInvoicePaymentUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.UnarchiveCardUseCase;
import com.moneylog.core.finance.app.cards.usecase.UnarchiveCardUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.UpdateCardDetailsUseCase;
import com.moneylog.core.finance.app.cards.usecase.UpdateCardDetailsUseCaseImpl;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.infra.card.gateways.CardRepositoryGatewayImpl;
import com.moneylog.infra.card.gateways.InvoiceRowGateway;
import com.moneylog.infra.card.persistence.BillingRuleRepositoryJpa;
import com.moneylog.infra.card.persistence.CardRepositoryJpa;

/** Ligação da feature cartão (T51): gateways e casos de uso transacionais. */
@Configuration(proxyBeanMethods = false)
public class CardConfig {

    @Bean
    CardGateway cardGateway(CardRepositoryJpa cards, BillingRuleRepositoryJpa rules, IdGenerator ids, TimeProvider time,
                            InvoiceRowGateway invoiceRows, jakarta.persistence.EntityManager entityManager) {
        return new CardRepositoryGatewayImpl(cards, rules, ids, time, invoiceRows, entityManager);
    }

    @Bean
    InvoiceRowGateway invoiceRowGateway(JdbcTemplate jdbc, TimeProvider time) {
        return new InvoiceRowGateway(jdbc, time);
    }

    @Bean
    CreateCardUseCase createCardUseCase(CardGateway cards, AccountGateway accounts, FinancialRecordsGateway records, IdGenerator ids,
                                        TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateCardUseCase.class, new CreateCardUseCaseImpl(cards, accounts, records, ids, time), tm);
    }

    @Bean
    ChangeCardBillingDaysUseCase changeCardBillingDaysUseCase(CardGateway cards, AccountGateway accounts, TransactionGateway transactions,
                                                              FinancialRecordsGateway records, TimeProvider time,
                                                              PlatformTransactionManager tm) {
        return TransactionalProxies.required(ChangeCardBillingDaysUseCase.class,
                new ChangeCardBillingDaysUseCaseImpl(cards, accounts, transactions, records, time), tm);
    }

    @Bean
    UpdateCardDetailsUseCase updateCardDetailsUseCase(CardGateway cards, AccountGateway accounts, FinancialRecordsGateway records,
                                                      TimeProvider time, ChangeCardBillingDaysUseCase billingDays,
                                                      PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateCardDetailsUseCase.class,
                new UpdateCardDetailsUseCaseImpl(cards, accounts, records, time, billingDays), tm);
    }

    @Bean
    PreviewBillingChangeUseCase previewBillingChangeUseCase(FinancialRecordsGateway records, TimeProvider time,
                                                            PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(PreviewBillingChangeUseCase.class,
                new PreviewBillingChangeUseCaseImpl(records, time), tm);
    }

    @Bean
    ArchiveCardUseCase archiveCardUseCase(CardGateway cards, FinancialRecordsGateway records, TimeProvider time,
                                          PlatformTransactionManager tm) {
        return TransactionalProxies.required(ArchiveCardUseCase.class, new ArchiveCardUseCaseImpl(cards, records, time), tm);
    }

    @Bean
    UnarchiveCardUseCase unarchiveCardUseCase(CardGateway cards, FinancialRecordsGateway records, TimeProvider time,
                                              PlatformTransactionManager tm) {
        return TransactionalProxies.required(UnarchiveCardUseCase.class, new UnarchiveCardUseCaseImpl(cards, records, time), tm);
    }

    @Bean
    GetCardUseCase getCardUseCase(CardGateway cards, FinancialRecordsGateway records, TimeProvider time,
                                  PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetCardUseCase.class, new GetCardUseCaseImpl(cards, records, time), tm);
    }

    @Bean
    PreviewCardPurchaseUseCase previewCardPurchaseUseCase(CardGateway cards, FinancialRecordsGateway records, IdGenerator ids,
                                                          TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(PreviewCardPurchaseUseCase.class,
                new PreviewCardPurchaseUseCaseImpl(cards, records, ids, time), tm);
    }

    @Bean
    ListInvoicesUseCase listInvoicesUseCase(FinancialRecordsGateway records, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListInvoicesUseCase.class, new ListInvoicesUseCaseImpl(records, time), tm);
    }

    @Bean
    GetInvoiceUseCase getInvoiceUseCase(InvoiceRowGateway invoices, FinancialRecordsGateway records, TimeProvider time,
                                        PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetInvoiceUseCase.class, new GetInvoiceUseCaseImpl(invoices, records, time), tm);
    }

    @Bean
    PreviewInvoicePaymentUseCase previewInvoicePaymentUseCase(InvoiceRowGateway invoices, FinancialRecordsGateway records,
                                                              IdGenerator ids, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(PreviewInvoicePaymentUseCase.class,
                new PreviewInvoicePaymentUseCaseImpl(invoices, records, ids, time), tm);
    }

    @Bean
    PayInvoiceUseCase payInvoiceUseCase(InvoiceRowGateway invoices, CardGateway cards, AccountGateway accounts,
                                        TransactionGateway transactions, LedgerWriteGateway ledger, FinancialRecordsGateway records,
                                        IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(PayInvoiceUseCase.class,
                new PayInvoiceUseCaseImpl(invoices, cards, accounts, transactions, ledger, records, idempotency, ids, time), tm);
    }

    @Bean
    GetFutureInstallmentsUseCase getFutureInstallmentsUseCase(FinancialRecordsGateway records, TimeProvider time,
                                                              PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetFutureInstallmentsUseCase.class,
                new GetFutureInstallmentsUseCaseImpl(records, time), tm);
    }

    @Bean
    ListCardsUseCase listCardsUseCase(CardGateway cards, FinancialRecordsGateway records, TimeProvider time,
                                      PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListCardsUseCase.class, new ListCardsUseCaseImpl(cards, records, time), tm);
    }
}
