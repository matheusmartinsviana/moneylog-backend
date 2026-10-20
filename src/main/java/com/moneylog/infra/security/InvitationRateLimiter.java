package com.moneylog.infra.security;

import java.time.Clock;

import com.moneylog.core.sk.id.SecureToken;

public class InvitationRateLimiter {

    private final LoginRateLimiter delegate;

    public InvitationRateLimiter(Clock clock, LoginRateLimiter.Settings settings) {
        this.delegate = new LoginRateLimiter(clock, settings);
    }

    public LoginRateLimiter.Attempt reserve(String ip, String presentedToken) {
        return delegate.reserve(ip, SecureToken.hash(presentedToken == null ? "" : presentedToken));
    }

    public void release(LoginRateLimiter.Attempt attempt) {
        delegate.succeeded(attempt);
    }
}
