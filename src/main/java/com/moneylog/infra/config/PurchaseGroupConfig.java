package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.finance.app.cards.usecase.CreateOngoingInstallmentsUseCase;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCase;
import com.moneylog.core.finance.app.gateways.PurchaseGroupBackupGateway;
import com.moneylog.core.purchasegroup.app.gateways.PurchaseGroupGateway;
import com.moneylog.core.purchasegroup.app.usecase.AddPurchaseGroupMemberUseCase;
import com.moneylog.core.purchasegroup.app.usecase.AddPurchaseGroupMemberUseCaseImpl;
import com.moneylog.core.purchasegroup.app.usecase.CreatePurchaseGroupUseCase;
import com.moneylog.core.purchasegroup.app.usecase.CreatePurchaseGroupUseCaseImpl;
import com.moneylog.core.purchasegroup.app.usecase.GetPurchaseCommitmentsUseCase;
import com.moneylog.core.purchasegroup.app.usecase.GetPurchaseCommitmentsUseCaseImpl;
import com.moneylog.core.purchasegroup.app.usecase.GetPurchaseGroupUseCase;
import com.moneylog.core.purchasegroup.app.usecase.GetPurchaseGroupUseCaseImpl;
import com.moneylog.core.purchasegroup.app.usecase.ListPurchaseGroupsUseCase;
import com.moneylog.core.purchasegroup.app.usecase.ListPurchaseGroupsUseCaseImpl;
import com.moneylog.core.purchasegroup.app.usecase.RemovePurchaseGroupMemberUseCase;
import com.moneylog.core.purchasegroup.app.usecase.RemovePurchaseGroupMemberUseCaseImpl;
import com.moneylog.core.purchasegroup.app.usecase.UpdatePurchaseGroupUseCase;
import com.moneylog.core.purchasegroup.app.usecase.UpdatePurchaseGroupUseCaseImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.infra.purchasegroup.gateways.PurchaseGroupBackupGatewayImpl;
import com.moneylog.infra.purchasegroup.gateways.PurchaseGroupGatewayImpl;
import com.moneylog.infra.purchasegroup.persistence.PurchaseGroupMemberRepositoryJpa;
import com.moneylog.infra.purchasegroup.persistence.PurchaseGroupRepositoryJpa;

@Configuration(proxyBeanMethods = false)
public class PurchaseGroupConfig {

    @Bean
    PurchaseGroupGateway purchaseGroupGateway(PurchaseGroupRepositoryJpa groups, PurchaseGroupMemberRepositoryJpa members,
                                              TimeProvider time) {
        return new PurchaseGroupGatewayImpl(groups, members, time);
    }

    @Bean
    PurchaseGroupBackupGateway purchaseGroupBackupGateway(PurchaseGroupGateway groups) {
        return new PurchaseGroupBackupGatewayImpl(groups);
    }

    @Bean
    CreatePurchaseGroupUseCase createPurchaseGroupUseCase(PurchaseGroupGateway groups, CreateTransactionUseCase createPurchase,
                                                          CreateOngoingInstallmentsUseCase createOngoing, FinancialRecordsGateway records,
                                                          IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                                          PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreatePurchaseGroupUseCase.class,
                new CreatePurchaseGroupUseCaseImpl(groups, createPurchase, createOngoing, records, idempotency, ids, time), tm);
    }

    @Bean
    UpdatePurchaseGroupUseCase updatePurchaseGroupUseCase(PurchaseGroupGateway groups, FinancialRecordsGateway records, TimeProvider time,
                                                          PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdatePurchaseGroupUseCase.class, new UpdatePurchaseGroupUseCaseImpl(groups, records, time), tm);
    }

    @Bean
    AddPurchaseGroupMemberUseCase addPurchaseGroupMemberUseCase(PurchaseGroupGateway groups, FinancialRecordsGateway records,
                                                                TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(AddPurchaseGroupMemberUseCase.class,
                new AddPurchaseGroupMemberUseCaseImpl(groups, records, time), tm);
    }

    @Bean
    RemovePurchaseGroupMemberUseCase removePurchaseGroupMemberUseCase(PurchaseGroupGateway groups, FinancialRecordsGateway records,
                                                                      TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(RemovePurchaseGroupMemberUseCase.class,
                new RemovePurchaseGroupMemberUseCaseImpl(groups, records, time), tm);
    }

    @Bean
    GetPurchaseGroupUseCase getPurchaseGroupUseCase(PurchaseGroupGateway groups, FinancialRecordsGateway records, TimeProvider time,
                                                    PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetPurchaseGroupUseCase.class,
                new GetPurchaseGroupUseCaseImpl(groups, records, time), tm);
    }

    @Bean
    ListPurchaseGroupsUseCase listPurchaseGroupsUseCase(PurchaseGroupGateway groups, FinancialRecordsGateway records, TimeProvider time,
                                                        PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListPurchaseGroupsUseCase.class,
                new ListPurchaseGroupsUseCaseImpl(groups, records, time), tm);
    }

    @Bean
    GetPurchaseCommitmentsUseCase getPurchaseCommitmentsUseCase(FinancialRecordsGateway records, TimeProvider time,
                                                                PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetPurchaseCommitmentsUseCase.class,
                new GetPurchaseCommitmentsUseCaseImpl(records, time), tm);
    }
}
