package com.moneylog.infra.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "moneylog.owner")
public record MoneylogOwnerProperties(String email, String passwordHash, String passwordHashFile,
                                      boolean resetPassword, String setupCode, String setupCodeFile,
                                      @DefaultValue("PT24H") Duration setupCodeTtl) {
}
