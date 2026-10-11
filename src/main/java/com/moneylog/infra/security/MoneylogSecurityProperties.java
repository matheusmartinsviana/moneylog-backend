package com.moneylog.infra.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "moneylog.security")
public record MoneylogSecurityProperties(String jwtSecret, List<String> allowedOrigins, List<String> trustedProxies) {

    public MoneylogSecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        trustedProxies = trustedProxies == null ? List.of() : trustedProxies.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
