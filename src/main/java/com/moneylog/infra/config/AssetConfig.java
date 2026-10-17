package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.asset.app.usecase.ArchiveAssetUseCase;
import com.moneylog.core.asset.app.usecase.ArchiveAssetUseCaseImpl;
import com.moneylog.core.asset.app.usecase.CreateAssetUseCase;
import com.moneylog.core.asset.app.usecase.CreateAssetUseCaseImpl;
import com.moneylog.core.asset.app.usecase.GetAssetUseCase;
import com.moneylog.core.asset.app.usecase.GetAssetUseCaseImpl;
import com.moneylog.core.asset.app.usecase.ListAssetsUseCase;
import com.moneylog.core.asset.app.usecase.ListAssetsUseCaseImpl;
import com.moneylog.core.asset.app.usecase.RecordAssetBalanceUseCase;
import com.moneylog.core.asset.app.usecase.RecordAssetBalanceUseCaseImpl;
import com.moneylog.core.asset.app.usecase.UpdateAssetUseCase;
import com.moneylog.core.asset.app.usecase.UpdateAssetUseCaseImpl;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.networth.usecase.GetNetWorthOverviewUseCase;
import com.moneylog.core.finance.app.networth.usecase.GetNetWorthOverviewUseCaseImpl;
import com.moneylog.core.finance.app.statement.GetOwnerStatementUseCase;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.infra.asset.gateways.AssetGatewayImpl;
import com.moneylog.infra.asset.persistence.AssetBalanceRepositoryJpa;
import com.moneylog.infra.asset.persistence.AssetRepositoryJpa;

@Configuration(proxyBeanMethods = false)
public class AssetConfig {

    @Bean
    AssetGateway assetGateway(AssetRepositoryJpa assets, AssetBalanceRepositoryJpa balances, IdGenerator ids, TimeProvider time) {
        return new AssetGatewayImpl(assets, balances, ids, time);
    }

    @Bean
    CreateAssetUseCase createAssetUseCase(AssetGateway assets, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                          PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateAssetUseCase.class, new CreateAssetUseCaseImpl(assets, idempotency, ids, time), tm);
    }

    @Bean
    UpdateAssetUseCase updateAssetUseCase(AssetGateway assets, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateAssetUseCase.class, new UpdateAssetUseCaseImpl(assets), tm);
    }

    @Bean
    ArchiveAssetUseCase archiveAssetUseCase(AssetGateway assets, PlatformTransactionManager tm) {
        return TransactionalProxies.required(ArchiveAssetUseCase.class, new ArchiveAssetUseCaseImpl(assets), tm);
    }

    @Bean
    RecordAssetBalanceUseCase recordAssetBalanceUseCase(AssetGateway assets, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(RecordAssetBalanceUseCase.class, new RecordAssetBalanceUseCaseImpl(assets, time), tm);
    }

    @Bean
    GetAssetUseCase getAssetUseCase(AssetGateway assets, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetAssetUseCase.class, new GetAssetUseCaseImpl(assets), tm);
    }

    @Bean
    ListAssetsUseCase listAssetsUseCase(AssetGateway assets, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListAssetsUseCase.class, new ListAssetsUseCaseImpl(assets), tm);
    }

    @Bean
    GetNetWorthOverviewUseCase getNetWorthOverviewUseCase(FinancialRecordsGateway records, GetOwnerStatementUseCase statement,
                                                          AssetGateway assets, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetNetWorthOverviewUseCase.class,
                new GetNetWorthOverviewUseCaseImpl(records, statement, assets, time), tm);
    }
}
