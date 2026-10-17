package com.moneylog.infra.asset.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.asset.app.usecase.ArchiveAssetUseCase;
import com.moneylog.core.asset.app.usecase.ArchiveAssetUseCase.ArchiveAssetCommand;
import com.moneylog.core.asset.app.usecase.CreateAssetUseCase;
import com.moneylog.core.asset.app.usecase.CreateAssetUseCase.CreateAssetCommand;
import com.moneylog.core.asset.app.usecase.GetAssetUseCase;
import com.moneylog.core.asset.app.usecase.GetAssetUseCase.GetAssetQuery;
import com.moneylog.core.asset.app.usecase.ListAssetsUseCase;
import com.moneylog.core.asset.app.usecase.ListAssetsUseCase.ListAssetsQuery;
import com.moneylog.core.asset.app.usecase.RecordAssetBalanceUseCase;
import com.moneylog.core.asset.app.usecase.RecordAssetBalanceUseCase.RecordAssetBalanceCommand;
import com.moneylog.core.asset.app.usecase.UpdateAssetUseCase;
import com.moneylog.core.asset.app.usecase.UpdateAssetUseCase.UpdateAssetCommand;
import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.infra.asset.controller.AssetRequests.AssetBalanceInput;
import com.moneylog.infra.asset.controller.AssetRequests.AssetInput;
import com.moneylog.infra.asset.controller.AssetRequests.AssetUpdate;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;

@RestController
@RequestMapping("/v1/assets")
public class AssetController {

    private final CreateAssetUseCase create;
    private final UpdateAssetUseCase update;
    private final ArchiveAssetUseCase archive;
    private final RecordAssetBalanceUseCase recordBalance;
    private final GetAssetUseCase get;
    private final ListAssetsUseCase list;

    @SuppressWarnings("java:S107")
    public AssetController(CreateAssetUseCase create, UpdateAssetUseCase update, ArchiveAssetUseCase archive,
                           RecordAssetBalanceUseCase recordBalance, GetAssetUseCase get, ListAssetsUseCase list) {
        this.create = create;
        this.update = update;
        this.archive = archive;
        this.recordBalance = recordBalance;
        this.get = get;
        this.list = list;
    }

    @GetMapping
    public SliceResponse<AssetResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) AssetResponse.Status status,
                                             PageQuery page) {
        boolean archived = status == AssetResponse.Status.ARCHIVED;
        return SliceResponse.of(list.handle(new ListAssetsQuery(userId, archived, page)), AssetResponse::of);
    }

    @PostMapping
    public ResponseEntity<AssetResponse> create(@CurrentUser UUID userId,
                                                @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                                @Valid @RequestBody AssetInput body) {
        Versioned<Asset> created = create.handle(new CreateAssetCommand(userId, body.toSpec(), idempotencyKey));
        return ResponseEntity.created(URI.create("/v1/assets/" + created.value().id())).body(AssetResponse.of(created));
    }

    @GetMapping("/{id}")
    public AssetResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return AssetResponse.of(get.handle(new GetAssetQuery(userId, id)));
    }

    @PutMapping("/{id}")
    public AssetResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody AssetUpdate body) {
        return AssetResponse.of(update.handle(new UpdateAssetCommand(userId, id, body.toSpec(), body.version())));
    }

    @DeleteMapping("/{id}")
    public AssetResponse archive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return AssetResponse.of(archive.handle(new ArchiveAssetCommand(userId, id)));
    }

    @PostMapping("/{id}/balance")
    public AssetResponse recordBalance(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody AssetBalanceInput body) {
        return AssetResponse.of(recordBalance.handle(new RecordAssetBalanceCommand(userId, id, body.toMoney(), body.date())));
    }
}
