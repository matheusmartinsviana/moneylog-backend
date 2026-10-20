package com.moneylog.infra.invitation.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.invitation.app.InvitationView;
import com.moneylog.core.invitation.app.usecase.InviteUserUseCase;
import com.moneylog.core.invitation.app.usecase.InviteUserUseCase.InviteUserCommand;
import com.moneylog.core.invitation.app.usecase.ListInvitationsUseCase;
import com.moneylog.core.invitation.app.usecase.ListInvitationsUseCase.ListInvitationsQuery;
import com.moneylog.core.invitation.app.usecase.ResendInvitationUseCase;
import com.moneylog.core.invitation.app.usecase.ResendInvitationUseCase.ResendInvitationCommand;
import com.moneylog.core.invitation.app.usecase.RevokeInvitationUseCase;
import com.moneylog.core.invitation.app.usecase.RevokeInvitationUseCase.RevokeInvitationCommand;
import com.moneylog.core.invitation.domain.InvitationStatus;
import com.moneylog.infra.invitation.controller.InvitationRequests.InvitationInput;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/invitations")
public class InvitationController {

    static final String NO_STORE = "private, no-store";

    private final InviteUserUseCase invite;
    private final ListInvitationsUseCase list;
    private final ResendInvitationUseCase resend;
    private final RevokeInvitationUseCase revoke;

    public InvitationController(InviteUserUseCase invite, ListInvitationsUseCase list, ResendInvitationUseCase resend,
                                RevokeInvitationUseCase revoke) {
        this.invite = invite;
        this.list = list;
        this.resend = resend;
        this.revoke = revoke;
    }

    @PostMapping
    public ResponseEntity<InvitationResponse> create(@CurrentUser UUID userId, @Valid @RequestBody InvitationInput body) {
        InvitationView created = invite.handle(new InviteUserCommand(userId, body.email()));
        return ResponseEntity.created(URI.create("/v1/invitations/" + created.id()))
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(InvitationResponse.of(created));
    }

    @GetMapping
    public ResponseEntity<List<InvitationResponse>> list(@RequestParam(required = false) InvitationStatus status) {
        List<InvitationResponse> body = list.handle(new ListInvitationsQuery(status)).stream().map(InvitationResponse::of).toList();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(body);
    }

    @PostMapping("/{id}/resend")
    public ResponseEntity<InvitationResponse> resend(@CurrentUser UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(InvitationResponse.of(resend.handle(new ResendInvitationCommand(userId, id))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<InvitationResponse> revoke(@PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(InvitationResponse.of(revoke.handle(new RevokeInvitationCommand(id))));
    }
}
