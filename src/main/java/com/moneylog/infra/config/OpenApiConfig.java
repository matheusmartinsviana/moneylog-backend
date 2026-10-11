package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI moneylogOpenApi() {
        return new OpenAPI().info(new Info().title("moneylog API (gerada do código)").version("2.0.0")
                .description("Fonte da verdade do contrato: contracts/openapi.yaml."));
    }
}
