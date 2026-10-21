package com.moneylog.infra.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moneylog.quick-entry")
public record MoneylogQuickEntryProperties(RateLimit rateLimit, Duration lastUsedWriteInterval) {

    public MoneylogQuickEntryProperties {
        rateLimit = rateLimit == null ? new RateLimit(null, null, null, null) : rateLimit;
        lastUsedWriteInterval = lastUsedWriteInterval == null ? Duration.ofMinutes(1) : lastUsedWriteInterval;
    }

    public record RateLimit(Integer maxFailuresPerIp, Duration failureWindow, Integer maxCallsPerToken, Duration callWindow) {

        public RateLimit {
            maxFailuresPerIp = maxFailuresPerIp == null ? 10 : maxFailuresPerIp;
            failureWindow = failureWindow == null ? Duration.ofMinutes(5) : failureWindow;
            maxCallsPerToken = maxCallsPerToken == null ? 20 : maxCallsPerToken;
            callWindow = callWindow == null ? Duration.ofMinutes(1) : callWindow;
        }
    }
}
