package com.moneylog.infra.auth.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import com.moneylog.core.auth.app.AuthSession;
import com.moneylog.core.auth.app.usecase.AuthStatusUseCase;
import com.moneylog.core.auth.app.usecase.LoginUseCase;
import com.moneylog.core.auth.app.usecase.LoginUseCase.LoginCommand;
import com.moneylog.core.auth.app.usecase.LogoutUseCase;
import com.moneylog.core.auth.app.usecase.LogoutUseCase.LogoutCommand;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCase;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCase.RefreshCommand;
import com.moneylog.core.auth.app.usecase.SetupPasswordUseCase;
import com.moneylog.core.auth.app.usecase.SetupPasswordUseCase.SetupPasswordCommand;
import com.moneylog.core.auth.exception.AuthExceptions.InvalidCredentialsException;
import com.moneylog.core.auth.exception.AuthExceptions.InvalidSetupCodeException;
import com.moneylog.core.auth.exception.AuthExceptions.RefreshInvalidException;
import com.moneylog.core.notification.domain.SecurityEvent;
import com.moneylog.infra.security.ClientIpResolver;
import com.moneylog.infra.security.LoginRateLimiter;
import com.moneylog.infra.security.MoneylogAuthProperties;
import com.moneylog.infra.security.OriginCheck;
import com.moneylog.infra.security.RefreshCookies;
import com.moneylog.infra.security.SecurityAlerts;
import com.moneylog.infra.security.SecurityEventLog;
import com.moneylog.infra.security.TooManyAttemptsException;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final LoginUseCase login;
    private final RefreshSessionUseCase refresh;
    private final LogoutUseCase logout;
    private final SetupPasswordUseCase setupPassword;
    private final AuthStatusUseCase status;
    private final LoginRateLimiter rateLimiter;
    private final ClientIpResolver clientIp;
    private final OriginCheck originCheck;
    private final RefreshCookies cookies;
    private final MoneylogAuthProperties auth;
    private final SecurityAlerts alerts;

    public AuthController(LoginUseCase login, RefreshSessionUseCase refresh, LogoutUseCase logout,
                          SetupPasswordUseCase setupPassword, AuthStatusUseCase status,
                          LoginRateLimiter rateLimiter, ClientIpResolver clientIp, OriginCheck originCheck,
                          RefreshCookies cookies, MoneylogAuthProperties auth, SecurityAlerts alerts) {
        this.login = login;
        this.refresh = refresh;
        this.logout = logout;
        this.setupPassword = setupPassword;
        this.status = status;
        this.rateLimiter = rateLimiter;
        this.clientIp = clientIp;
        this.originCheck = originCheck;
        this.cookies = cookies;
        this.auth = auth;
        this.alerts = alerts;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthTokenResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        String ip = clientIp.resolve(request);
        LoginRateLimiter.Attempt attempt = reserve(ip, "login", body.email());
        AuthSession session;
        try {
            session = login.handle(new LoginCommand(body.email(), body.password()));
        } catch (InvalidCredentialsException e) {
            SecurityEventLog.loginFailed(ip, body.email());
            throw e;
        }
        boolean newIp = rateLimiter.succeeded(attempt);
        SecurityEventLog.loginSucceeded(ip, body.email());
        if (newIp) {
            alerts.raise(SecurityEvent.NEW_LOGIN, ip, null);
        }
        return sessionResponse(session);
    }

    @PostMapping("/setup-password")
    public ResponseEntity<AuthTokenResponse> setupPassword(@Valid @RequestBody SetupPasswordRequest body,
                                                           HttpServletRequest request) {
        String ip = clientIp.resolve(request);
        LoginRateLimiter.Attempt attempt = reserve(ip, "setup", body.email());
        AuthSession session;
        try {
            session = setupPassword.handle(new SetupPasswordCommand(body.email(), body.setupCode(), body.newPassword()));
        } catch (InvalidSetupCodeException e) {
            SecurityEventLog.setupFailed(ip, body.email());
            throw e;
        }
        rateLimiter.succeeded(attempt);
        SecurityEventLog.setupSucceeded(ip, body.email());
        alerts.raise(SecurityEvent.SETUP_COMPLETED, ip, null);
        return sessionResponse(session);
    }

    private LoginRateLimiter.Attempt reserve(String ip, String scope, String email) {
        try {
            return rateLimiter.reserve(ip, scope + ":" + (email == null ? "" : email.strip()));
        } catch (TooManyAttemptsException e) {
            SecurityEventLog.blocked(e.scope(), ip, email);
            alerts.raise(SecurityEvent.LOGIN_BLOCKED, ip, e.scope().name().toLowerCase(java.util.Locale.ROOT));
            throw e;
        }
    }

    @GetMapping("/status")
    public AuthStatusResponse status() {
        return new AuthStatusResponse(status.handle().passwordSetupRequired());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthTokenResponse> refresh(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
            HttpServletRequest request, HttpServletResponse response) {
        originCheck.ensureAllowed(request);
        try {
            return sessionResponse(refresh.handle(new RefreshCommand(refreshToken)));
        } catch (RefreshInvalidException e) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookies.expire());
            throw e;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
                                       HttpServletRequest request) {
        originCheck.ensureAllowed(request);
        logout.handle(new LogoutCommand(refreshToken));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.expire()).build();
    }

    private ResponseEntity<AuthTokenResponse> sessionResponse(AuthSession session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken(), auth.refreshTtl()))
                .body(new AuthTokenResponse(session.accessToken().value(), session.accessToken().expiresAt()));
    }
}
