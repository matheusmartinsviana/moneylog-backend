package com.moneylog.infra.quickentry.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import com.moneylog.core.notification.domain.SecurityEvent;
import com.moneylog.core.quickentry.app.IssuedQuickEntryToken;
import com.moneylog.core.quickentry.app.usecase.CreateQuickEntryTokenUseCase;
import com.moneylog.core.quickentry.app.usecase.CreateQuickEntryTokenUseCase.CreateQuickEntryTokenCommand;
import com.moneylog.core.quickentry.app.usecase.ListQuickEntryTokensUseCase;
import com.moneylog.core.quickentry.app.usecase.ListQuickEntryTokensUseCase.ListQuickEntryTokensQuery;
import com.moneylog.core.quickentry.app.usecase.RenameQuickEntryTokenUseCase;
import com.moneylog.core.quickentry.app.usecase.RenameQuickEntryTokenUseCase.RenameQuickEntryTokenCommand;
import com.moneylog.core.quickentry.app.usecase.RevokeQuickEntryTokenUseCase;
import com.moneylog.core.quickentry.app.usecase.RevokeQuickEntryTokenUseCase.Revoked;
import com.moneylog.core.quickentry.app.usecase.RevokeQuickEntryTokenUseCase.RevokeQuickEntryTokenCommand;
import com.moneylog.infra.quickentry.controller.QuickEntryTokenRequests.QuickEntryTokenInput;
import com.moneylog.infra.quickentry.controller.QuickEntryTokenRequests.QuickEntryTokenRename;
import com.moneylog.infra.security.ClientIpResolver;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.security.SecurityAlerts;
import com.moneylog.infra.security.SecurityEventLog;

@RestController
@RequestMapping(QuickEntryTokenController.PATH)
public class QuickEntryTokenController implements QuickEntryTokenControllerOpenApi {

    public static final String PATH = "/v1/quick-entry-tokens";

    private static final String NO_STORE = "no-store";

    private final CreateQuickEntryTokenUseCase create;
    private final ListQuickEntryTokensUseCase list;
    private final RenameQuickEntryTokenUseCase rename;
    private final RevokeQuickEntryTokenUseCase revoke;
    private final ClientIpResolver clientIp;
    private final SecurityAlerts alerts;

    public QuickEntryTokenController(CreateQuickEntryTokenUseCase create, ListQuickEntryTokensUseCase list,
                                     RenameQuickEntryTokenUseCase rename, RevokeQuickEntryTokenUseCase revoke,
                                     ClientIpResolver clientIp, SecurityAlerts alerts) {
        this.create = create;
        this.list = list;
        this.rename = rename;
        this.revoke = revoke;
        this.clientIp = clientIp;
        this.alerts = alerts;
    }

    @Override
    @PostMapping
    public ResponseEntity<QuickEntryTokenCreatedResponse> create(@CurrentUser UUID userId, @Valid @RequestBody QuickEntryTokenInput body,
                                                                 HttpServletRequest request) {
        IssuedQuickEntryToken issued = create.handle(new CreateQuickEntryTokenCommand(userId, body.label(), body.accountId(),
                body.expiresInDays()));
        String ip = clientIp.resolve(request);
        SecurityEventLog.quickTokenCreated(ip, userId, issued.view().token().id(), issued.view().token().prefix());
        alerts.raiseForUser(userId, SecurityEvent.QUICK_TOKEN_CREATED, ip, null);
        return ResponseEntity.created(URI.create(PATH + "/" + issued.view().token().id()))
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(QuickEntryTokenCreatedResponse.of(issued));
    }

    @Override
    @GetMapping
    public ResponseEntity<List<QuickEntryTokenResponse>> list(@CurrentUser UUID userId) {
        List<QuickEntryTokenResponse> body = list.handle(new ListQuickEntryTokensQuery(userId)).stream()
                .map(QuickEntryTokenResponse::of).toList();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(body);
    }

    @Override
    @PutMapping("/{id}")
    public ResponseEntity<QuickEntryTokenResponse> rename(@CurrentUser UUID userId, @PathVariable UUID id,
                                                          @Valid @RequestBody QuickEntryTokenRename body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .body(QuickEntryTokenResponse.of(rename.handle(new RenameQuickEntryTokenCommand(userId, id, body.label()))));
    }

    @Override
    @PostMapping("/{id}/revoke")
    public ResponseEntity<QuickEntryTokenResponse> revoke(@CurrentUser UUID userId, @PathVariable UUID id, HttpServletRequest request) {
        Revoked revoked = revoke.handle(new RevokeQuickEntryTokenCommand(userId, id));
        if (revoked.changed()) {
            String ip = clientIp.resolve(request);
            SecurityEventLog.quickTokenRevoked(ip, userId, id, revoked.view().token().prefix());
            alerts.raiseForUser(userId, SecurityEvent.QUICK_TOKEN_REVOKED, ip, null);
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(QuickEntryTokenResponse.of(revoked.view()));
    }
}
