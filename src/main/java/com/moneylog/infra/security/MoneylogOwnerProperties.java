package com.moneylog.infra.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code moneylog.owner.*}: o dono único (ADR-003, ADR-015). O hash BCrypt do ambiente é opcional e só vale enquanto o
 * dono não tem senha no banco (retrocompatibilidade). {@code resetPassword} é de uso único: apaga a senha do banco e
 * exige novo primeiro acesso. O código do primeiro acesso pode vir de {@code setupCode}/{@code setupCodeFile}
 * (secrets em produção); sem ele, é gerado e impresso uma vez no log do perfil dev.
 */
@ConfigurationProperties(prefix = "moneylog.owner")
public record MoneylogOwnerProperties(String email, String passwordHash, String passwordHashFile,
                                      boolean resetPassword, String setupCode, String setupCodeFile,
                                      @DefaultValue("PT24H") Duration setupCodeTtl) {
}
