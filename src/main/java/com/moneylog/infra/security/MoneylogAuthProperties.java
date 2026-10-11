package com.moneylog.infra.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "moneylog.auth")
public record MoneylogAuthProperties(
        @DefaultValue("PT15M") Duration accessTokenTtl,
        @DefaultValue("P30D") Duration refreshTtl,
        @DefaultValue("PT30S") Duration reuseGrace,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("5") int maxLoginFailures,
        @DefaultValue("PT15M") Duration loginWindow,
        @DefaultValue("10") int maxAccountFailures,
        @DefaultValue("PT1M") Duration accountLockBase,
        @DefaultValue("PT15M") Duration accountLockMax,
        @DefaultValue("P30D") Duration trustedIpTtl,
        @DefaultValue("10000") int limiterMaxEntries,
        @DefaultValue("P90D") Duration maxSessionAge,
        @DefaultValue("P7D") Duration refreshRetention) {
}
