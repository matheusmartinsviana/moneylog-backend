package com.moneylog.infra.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.notification.domain.SecurityEvent;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.domain.IpMask;
import com.moneylog.core.quickentry.domain.QuickEntryScope;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;

public class QuickTokenAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(QuickTokenAuthenticator.class);
    private static final String BEARER = "Bearer ";

    private enum Rejection {
        MISSING, MALFORMED, UNKNOWN, INACTIVE, USER_DISABLED
    }

    private final QuickEntryTokenGateway tokens;
    private final UserGateway users;
    private final ClientIpResolver clientIp;
    private final QuickTokenRateLimiter limiter;
    private final QuickTokenSeenIps seenIps;
    private final SecurityAlerts alerts;
    private final Clock clock;
    private final MoneylogQuickEntryProperties properties;

    public QuickTokenAuthenticator(QuickEntryTokenGateway tokens, UserGateway users, ClientIpResolver clientIp, QuickTokenRateLimiter limiter,
                                   QuickTokenSeenIps seenIps, SecurityAlerts alerts, Clock clock, MoneylogQuickEntryProperties properties) {
        this.tokens = tokens;
        this.users = users;
        this.clientIp = clientIp;
        this.limiter = limiter;
        this.seenIps = seenIps;
        this.alerts = alerts;
        this.clock = clock;
        this.properties = properties;
    }

    public Authentication authenticate(HttpServletRequest request) {
        String ip = clientIp.resolve(request);
        try {
            limiter.ensureIpAllowed(ip);
        } catch (TooManyAttemptsException e) {
            SecurityEventLog.quickTokenRateLimited("ip", ip);
            throw e;
        }
        Optional<String> candidate = bearer(request);
        if (candidate.isEmpty()) {
            throw rejected(ip, Rejection.MISSING);
        }
        if (!QuickEntryTokenSecret.hasValidFormat(candidate.get())) {
            throw rejected(ip, Rejection.MALFORMED);
        }
        Optional<QuickEntryToken> found = tokens.findByTokenHashForAuthentication(QuickEntryTokenSecret.hashOf(candidate.get()));
        if (found.isEmpty()) {
            throw rejected(ip, Rejection.UNKNOWN);
        }
        QuickEntryToken token = found.get();
        Instant now = clock.instant();
        if (token.scope() != QuickEntryScope.QUICK_ENTRY || !token.isActiveAt(now)) {
            throw rejected(ip, Rejection.INACTIVE);
        }
        boolean userActive = users.findById(token.userId()).filter(User::isActive).isPresent();
        if (!userActive) {
            throw rejected(ip, Rejection.USER_DISABLED);
        }
        try {
            limiter.acquireCall(token.id());
        } catch (TooManyAttemptsException e) {
            SecurityEventLog.quickTokenRateLimited("token", ip);
            throw e;
        }
        observe(token, ip, request, now);
        return new QuickTokenAuthentication(new QuickTokenPrincipal(token.userId(), token.id(), token.accountId()));
    }

    private void observe(QuickEntryToken token, String ip, HttpServletRequest request, Instant now) {
        String maskedIp = IpMask.mask(ip);
        if (seenIps.registerAndCheckNew(token.id(), maskedIp, token.lastUsedIp())) {
            SecurityEventLog.quickTokenNewIp(maskedIp, token.userId(), token.id(), token.prefix());
            alerts.raiseForUser(token.userId(), SecurityEvent.QUICK_TOKEN_NEW_IP, maskedIp, null);
        }
        Instant usedBefore = now.minus(properties.lastUsedWriteInterval());
        if (token.lastUsedAt() != null && token.lastUsedAt().isAfter(usedBefore)) {
            return;
        }
        try {
            tokens.recordUse(token.userId(), token.id(), now, usedBefore, maskedIp, cleanUserAgent(request.getHeader("User-Agent")));
        } catch (RuntimeException e) {
            log.warn("quick_token_use_not_recorded error={}", e.getClass().getSimpleName());
        }
    }

    private QuickTokenInvalidException rejected(String ip, Rejection reason) {
        SecurityEventLog.quickTokenRejected(ip, reason.name().toLowerCase(java.util.Locale.ROOT));
        if (limiter.recordFailure(ip)) {
            SecurityEventLog.quickTokenFailureBurst(ip);
            alerts.raise(SecurityEvent.QUICK_TOKEN_FAILURE_BURST, ip, null);
        }
        return new QuickTokenInvalidException();
    }

    private static Optional<String> bearer(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("Authorization");
        if (values == null) {
            return Optional.empty();
        }
        var all = Collections.list(values);
        if (all.size() != 1 || !all.get(0).startsWith(BEARER)) {
            return Optional.empty();
        }
        return Optional.of(trimEnds(all.get(0).substring(BEARER.length())));
    }

    private static String trimEnds(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isPadding(value.charAt(start))) {
            start++;
        }
        while (end > start && isPadding(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isPadding(char c) {
        return Character.isWhitespace(c) || Character.isISOControl(c) || Character.isSpaceChar(c);
    }

    private static String cleanUserAgent(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(userAgent.length());
        userAgent.chars().forEach(c -> out.append(Character.isISOControl(c) ? '_' : (char) c));
        return out.toString();
    }
}
