package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

/**
 * Documentação viva gerada pelo springdoc (S-66). O contrato versionado e fonte da verdade é
 * {@code contracts/openapi.yaml}; esta é só a visão do código. Fica desligada fora do perfil {@code dev}
 * ({@code springdoc.api-docs.enabled} e {@code springdoc.swagger-ui.enabled}).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI moneylogOpenApi() {
        return new OpenAPI().info(new Info().title("moneylog API (gerada do código)").version("2.0.0")
                .description("Fonte da verdade do contrato: contracts/openapi.yaml."));
    }
}
