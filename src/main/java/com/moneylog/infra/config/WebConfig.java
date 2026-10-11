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

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    @Bean
    JacksonModule moneyJsonModule() {
        return new MoneyJson();
    }

    @Bean
    JacksonModule strictDatesJsonModule() {
        return new StrictDatesJson();
    }

    @Bean
    JsonMapperBuilderCustomizer noScalarToTextCoercion() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> config
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    @Bean
    JsonMapperBuilderCustomizer strictScalars() {
        return builder -> builder.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

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
