package com.moneylog.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Placeholders do Flyway entram no SQL como literal seguro")
class FlywayPlaceholderConfigTest {

    @Test
    @DisplayName("aspa simples é dobrada, valor vazio e nulo viram vazio")
    void escapaAspas() {
        assertThat(FlywayPlaceholderConfig.escapeSqlLiteral("o'brien@x.com")).isEqualTo("o''brien@x.com");
        assertThat(FlywayPlaceholderConfig.escapeSqlLiteral("x'); drop table users; --")).isEqualTo("x''); drop table users; --");
        assertThat(FlywayPlaceholderConfig.escapeSqlLiteral("")).isEmpty();
        assertThat(FlywayPlaceholderConfig.escapeSqlLiteral(null)).isEmpty();
    }
}
