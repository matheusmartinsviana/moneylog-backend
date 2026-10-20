package com.moneylog.infra.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayPlaceholderConfig {

    @Bean
    FlywayConfigurationCustomizer sqlSafePlaceholders() {
        return configuration -> {
            Map<String, String> escaped = new HashMap<>();
            configuration.getPlaceholders().forEach((key, value) -> escaped.put(key, escapeSqlLiteral(value)));
            configuration.placeholders(escaped);
        };
    }

    public static String escapeSqlLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
