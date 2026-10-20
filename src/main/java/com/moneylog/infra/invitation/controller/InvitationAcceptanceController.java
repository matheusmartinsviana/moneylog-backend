package com.moneylog.infra.invitation.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import com.moneylog.core.auth.app.AuthSession;
import com.moneylog.core.invitation.app.usecase.AcceptInvitationUseCase;
import com.moneylog.core.invitation.app.usecase.AcceptInvitationUseCase.AcceptInvitationCommand;
import com.moneylog.core.invitation.app.usecase.PreviewInvitationUseCase;
import com.moneylog.core.invitation.app.usecase.PreviewInvitationUseCase.InvitationPreview;
import com.moneylog.core.invitation.app.usecase.PreviewInvitationUseCase.PreviewInvitationCommand;
import com.moneylog.infra.auth.controller.AuthTokenResponse;
import com.moneylog.infra.invitation.controller.InvitationRequests.InvitationAcceptInput;
import com.moneylog.infra.invitation.controller.InvitationRequests.InvitationPreviewInput;
import com.moneylog.infra.security.ClientIpResolver;
import com.moneylog.infra.security.InvitationRateLimiter;
import com.moneylog.infra.security.LoginRateLimiter;
import com.moneylog.infra.security.MoneylogAuthProperties;
import com.moneylog.infra.security.RefreshCookies;
import com.moneylog.core.auth.exception.AuthExceptions.WeakPasswordException;

@RestController
@RequestMapping("/v1/invitations")
public class InvitationAcceptanceController {

    private final PreviewInvitationUseCase preview;
    private final AcceptInvitationUseCase accept;
    private final InvitationRateLimiter rateLimiter;
    private final ClientIpResolver clientIp;
    private final RefreshCookies cookies;
    private final MoneylogAuthProperties auth;

    public InvitationAcceptanceController(PreviewInvitationUseCase preview, AcceptInvitationUseCase accept,
                                          InvitationRateLimiter rateLimiter, ClientIpResolver clientIp, RefreshCookies cookies,
                                          MoneylogAuthProperties auth) {
        this.preview = preview;
        this.accept = accept;
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
        this.cookies = cookies;
        this.auth = auth;
    }

    @PostMapping("/preview")
    public ResponseEntity<InvitationPreviewResponse> preview(@Valid @RequestBody InvitationPreviewInput body, HttpServletRequest request) {
        LoginRateLimiter.Attempt attempt = rateLimiter.reserve(clientIp.resolve(request), body.token());
        InvitationPreview found = preview.handle(new PreviewInvitationCommand(body.token()));
        rateLimiter.release(attempt);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, InvitationController.NO_STORE)
                .body(new InvitationPreviewResponse(found.email(), found.expiresAt()));
    }

    @PostMapping("/accept")
    public ResponseEntity<AuthTokenResponse> accept(@Valid @RequestBody InvitationAcceptInput body, HttpServletRequest request) {
        LoginRateLimiter.Attempt attempt = rateLimiter.reserve(clientIp.resolve(request), body.token());
        AuthSession session;
        try {
            session = accept.handle(new AcceptInvitationCommand(body.token(), body.password()));
        } catch (WeakPasswordException e) {
            rateLimiter.release(attempt);
            throw e;
        }
        rateLimiter.release(attempt);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken(), auth.refreshTtl()))
                .header(HttpHeaders.CACHE_CONTROL, InvitationController.NO_STORE)
                .body(AuthTokenResponse.of(session));
    }
}
