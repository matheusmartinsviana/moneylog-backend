package com.moneylog.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code moneylog.owner.*}: o dono único (ADR-003). O hash BCrypt vem direto ({@code MONEYLOG_OWNER_PASSWORD_HASH}) ou
 * de um arquivo ({@code MONEYLOG_OWNER_PASSWORD_HASH_FILE}), que evita o problema do {@code $} no {@code .env}.
 */
@ConfigurationProperties(prefix = "moneylog.owner")
public record MoneylogOwnerProperties(String email, String passwordHash, String passwordHashFile) {
}
