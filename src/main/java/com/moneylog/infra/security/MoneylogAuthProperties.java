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
        @DefaultValue("P90D") Duration maxSessionAge,
        @DefaultValue("P7D") Duration refreshRetention) {
}
