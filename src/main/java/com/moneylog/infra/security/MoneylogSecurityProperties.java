package com.moneylog.infra.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code moneylog.security.*}: segredo do JWT, origens permitidas (CSRF do refresh e do logout) e proxies confiáveis
 * (S-86). Tudo vem de variável de ambiente.
 *
 * @param jwtSecret      {@code JWT_SECRET}, no mínimo 32 bytes
 * @param allowedOrigins {@code MONEYLOG_ALLOWED_ORIGINS}, separadas por vírgula
 * @param trustedProxies CIDRs dos proxies de confiança (CloudFront/ALB); vazio = ignora {@code X-Forwarded-For}
 */
@ConfigurationProperties(prefix = "moneylog.security")
public record MoneylogSecurityProperties(String jwtSecret, List<String> allowedOrigins, List<String> trustedProxies) {

    public MoneylogSecurityProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        trustedProxies = trustedProxies == null ? List.of() : trustedProxies.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
