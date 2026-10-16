package com.moneylog.infra.purchasegroup.controller;

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

import com.moneylog.core.purchasegroup.app.PurchaseGroupView;
import com.moneylog.core.purchasegroup.app.usecase.AddPurchaseGroupMemberUseCase;
import com.moneylog.core.purchasegroup.app.usecase.AddPurchaseGroupMemberUseCase.AddPurchaseGroupMemberCommand;
import com.moneylog.core.purchasegroup.app.usecase.CreatePurchaseGroupUseCase;
import com.moneylog.core.purchasegroup.app.usecase.CreatePurchaseGroupUseCase.CreatePurchaseGroupCommand;
import com.moneylog.core.purchasegroup.app.usecase.GetPurchaseCommitmentsUseCase;
import com.moneylog.core.purchasegroup.app.usecase.GetPurchaseCommitmentsUseCase.GetPurchaseCommitmentsQuery;
import com.moneylog.core.purchasegroup.app.usecase.GetPurchaseGroupUseCase;
import com.moneylog.core.purchasegroup.app.usecase.GetPurchaseGroupUseCase.GetPurchaseGroupQuery;
import com.moneylog.core.purchasegroup.app.usecase.ListPurchaseGroupsUseCase;
import com.moneylog.core.purchasegroup.app.usecase.ListPurchaseGroupsUseCase.ListPurchaseGroupsQuery;
import com.moneylog.core.purchasegroup.app.usecase.RemovePurchaseGroupMemberUseCase;
import com.moneylog.core.purchasegroup.app.usecase.RemovePurchaseGroupMemberUseCase.RemovePurchaseGroupMemberCommand;
import com.moneylog.core.purchasegroup.app.usecase.UpdatePurchaseGroupUseCase;
import com.moneylog.core.purchasegroup.app.usecase.UpdatePurchaseGroupUseCase.UpdatePurchaseGroupCommand;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.purchasegroup.controller.PurchaseGroupRequests.PurchaseGroupInput;
import com.moneylog.infra.purchasegroup.controller.PurchaseGroupRequests.PurchaseGroupMemberInput;
import com.moneylog.infra.purchasegroup.controller.PurchaseGroupRequests.PurchaseGroupUpdate;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.QueryMonths;
import com.moneylog.infra.web.SliceResponse;

@RestController
@RequestMapping("/v1/purchase-groups")
public class PurchaseGroupController {

    public static final String UNTIL_INVALID = "PURCHASE_GROUP_UNTIL_INVALID";

    private final CreatePurchaseGroupUseCase create;
    private final UpdatePurchaseGroupUseCase update;
    private final AddPurchaseGroupMemberUseCase addMember;
    private final RemovePurchaseGroupMemberUseCase removeMember;
    private final GetPurchaseGroupUseCase get;
    private final ListPurchaseGroupsUseCase list;
    private final GetPurchaseCommitmentsUseCase commitments;

    @SuppressWarnings("java:S107")
    public PurchaseGroupController(CreatePurchaseGroupUseCase create, UpdatePurchaseGroupUseCase update,
                                   AddPurchaseGroupMemberUseCase addMember, RemovePurchaseGroupMemberUseCase removeMember,
                                   GetPurchaseGroupUseCase get, ListPurchaseGroupsUseCase list, GetPurchaseCommitmentsUseCase commitments) {
        this.create = create;
        this.update = update;
        this.addMember = addMember;
        this.removeMember = removeMember;
        this.get = get;
        this.list = list;
        this.commitments = commitments;
    }

    @GetMapping
    public SliceResponse<PurchaseGroupResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) PurchaseGroupView.Status status,
                                                     PageQuery page) {
        return SliceResponse.of(list.handle(new ListPurchaseGroupsQuery(userId, status, page)), PurchaseGroupResponse::of);
    }

    @PostMapping
    public ResponseEntity<PurchaseGroupResponse> create(@CurrentUser UUID userId,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                                        @Valid @RequestBody PurchaseGroupInput body) {
        PurchaseGroupView created = create.handle(new CreatePurchaseGroupCommand(userId, body.name(), body.interest(), body.declared(),
                body.specs(), idempotencyKey));
        return ResponseEntity.created(URI.create("/v1/purchase-groups/" + created.id())).body(PurchaseGroupResponse.of(created));
    }

    @GetMapping("/commitments")
    public PurchaseGroupResponse.Commitments commitments(@CurrentUser UUID userId, @RequestParam String until) {
        return PurchaseGroupResponse.Commitments.of(commitments.handle(new GetPurchaseCommitmentsQuery(userId,
                QueryMonths.parse(until, UNTIL_INVALID, "until"))));
    }

    @GetMapping("/{id}")
    public PurchaseGroupResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return PurchaseGroupResponse.of(get.handle(new GetPurchaseGroupQuery(userId, id)));
    }

    @PutMapping("/{id}")
    public PurchaseGroupResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody PurchaseGroupUpdate body) {
        return PurchaseGroupResponse.of(update.handle(new UpdatePurchaseGroupCommand(userId, id, body.name(), body.interest(), body.version())));
    }

    @PostMapping("/{id}/members")
    public PurchaseGroupResponse addMember(@CurrentUser UUID userId, @PathVariable UUID id,
                                           @Valid @RequestBody PurchaseGroupMemberInput body) {
        return PurchaseGroupResponse.of(addMember.handle(new AddPurchaseGroupMemberCommand(userId, id, body.transactionId())));
    }

    @DeleteMapping("/{id}/members/{transactionId}")
    public PurchaseGroupResponse removeMember(@CurrentUser UUID userId, @PathVariable UUID id, @PathVariable UUID transactionId) {
        return PurchaseGroupResponse.of(removeMember.handle(new RemovePurchaseGroupMemberCommand(userId, id, transactionId)));
    }
}
