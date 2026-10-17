package com.moneylog.core.asset.app.usecase;

import java.util.UUID;

import com.moneylog.core.asset.app.AssetSpec;
import com.moneylog.core.asset.app.gateways.AssetGateway;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.exception.AssetExceptions.AssetNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class CreateAssetUseCaseImpl implements CreateAssetUseCase {

    private final AssetGateway assets;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    public CreateAssetUseCaseImpl(AssetGateway assets, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time) {
        this.assets = assets;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public Versioned<Asset> handle(CreateAssetCommand command) {
        UUID assetId = ids.next();
        if (command.idempotencyKey() != null) {
            String hash = hashOf(command.spec());
            Claim existing = IdempotentClaim.claimOrFind(idempotency, command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey(), hash,
                    assetId, time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hash);
                return assets.findById(command.userId(), existing.resourceId())
                        .orElseThrow(() -> new AssetNotFoundException(existing.resourceId()));
            }
        }
        AssetSpec s = command.spec();
        Asset asset = Asset.create(assetId, command.userId(), s.kind(), s.name(), s.employer(), s.balance(), s.balanceDate(),
                s.monthlyDeposit(), s.availability(), s.countsAsReserve(), time.now());
        asset.defineProvenance(s.provenance());
        return assets.insert(asset);
    }

    static String hashOf(AssetSpec s) {
        return IdempotentClaim.hash("kind=" + s.kind() + ";name=" + (s.name() == null ? "-" : s.name().strip())
                + ";employer=" + (s.employer() == null ? "-" : s.employer().strip())
                + ";balance=" + (s.balance() == null ? "-" : s.balance().toPlainString()) + ";date=" + s.balanceDate()
                + ";deposit=" + (s.monthlyDeposit() == null ? "-" : s.monthlyDeposit().toPlainString())
                + ";availability=" + s.availability() + ";reserve=" + s.countsAsReserve()
                + (s.provenance() == null ? "" : ";provenance=" + s.provenance().origin() + "/" + s.provenance().confidence()) + ";");
    }
}
