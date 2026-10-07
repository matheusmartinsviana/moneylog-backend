package com.moneylog.infra.config;

import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.moneylog.core.sk.time.Competence;
import com.moneylog.infra.security.CurrentUserArgumentResolver;
import com.moneylog.infra.web.MoneyJson;
import com.moneylog.infra.web.PageQueryResolver;
import com.moneylog.infra.web.StrictDatesJson;
import com.moneylog.infra.web.TraceIdFilter;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;

import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Fundação web (T21): {@code traceId} por requisição, paginação ({@code page}/{@code pageSize}), competência na query
 * ({@code YYYY-MM}) e a serialização de {@code Money}.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    @Bean
    JacksonModule moneyJsonModule() {
        return new MoneyJson();
    }

    /** BUG-QA-30: {@code LocalDate} só aceita {@code AAAA-MM-DD} (data-hora é 400). */
    @Bean
    JacksonModule strictDatesJsonModule() {
        return new StrictDatesJson();
    }

    /**
     * Sem coerção de número/booleano para texto: {@code "amount": 10.5} (número) não vira {@code "10.5"} num campo
     * string; vira {@code 400} (T21 CA3). O dinheiro é string do começo ao fim.
     */
    @Bean
    JsonMapperBuilderCustomizer noScalarToTextCoercion() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    /**
     * S-220: o contrato declara {@code integer}; {@code "25"} (texto) em campo inteiro é {@code 400 INVALID_FORMAT}, como
     * {@code 25.5}. Sem {@code ALLOW_COERCION_OF_SCALARS} o Jackson não converte texto em número, booleano nem o
     * contrário. Os campos do contrato que são texto (dinheiro, datas, ids) já chegam como texto e não dependem da coerção.
     */
    @Bean
    JsonMapperBuilderCustomizer strictScalars() {
        return builder -> builder.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    /** BUG-QA-30: o contrato diz string para enum; {@code "priority": 1} (posição) é {@code 400}, não {@code MEDIUM}. */
    @Bean
    JsonMapperBuilderCustomizer noEnumByPosition() {
        return builder -> builder.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** BUG-QA-05: query com percent-encoding inválido vira 400 problem+json antes de qualquer leitura de parâmetro. */
    @Bean
    FilterRegistrationBean<com.moneylog.infra.web.QueryStringGuardFilter> queryStringGuardFilter(
            org.springframework.security.web.firewall.RequestRejectedHandler rejectedHandler) {
        FilterRegistrationBean<com.moneylog.infra.web.QueryStringGuardFilter> registration =
                new FilterRegistrationBean<>(new com.moneylog.infra.web.QueryStringGuardFilter(rejectedHandler));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageQueryResolver());
        resolvers.add(new CurrentUserArgumentResolver());
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, Competence.class, source -> {
            try {
                return Competence.parse(source.strip());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Competência inválida (use YYYY-MM): " + source, e);
            }
        });
    }
}
