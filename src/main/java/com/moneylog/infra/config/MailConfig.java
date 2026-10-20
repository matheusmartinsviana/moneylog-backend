package com.moneylog.infra.config;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import com.moneylog.core.mail.app.EmailTemplate;
import com.moneylog.core.mail.app.gateways.EmailSender;
import com.moneylog.infra.mail.DisabledEmailSender;
import com.moneylog.infra.mail.FakeEmailSender;
import com.moneylog.infra.mail.LogEmailSender;
import com.moneylog.infra.mail.SesEmailSender;
import com.moneylog.infra.mail.config.MailProperties;
import com.moneylog.infra.mail.template.EmailTemplateRenderer;

@Configuration(proxyBeanMethods = false)
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    public MailProperties mailProperties(@Value("${moneylog.mail.provider:none}") String provider, @Value("${moneylog.mail.from:}") String from,
                                  @Value("${moneylog.mail.reply-to:}") String replyTo, @Value("${moneylog.mail.ses.region:}") String sesRegion,
                                  @Value("${moneylog.mail.ses.configuration-set:}") String configurationSet,
                                  @Value("${moneylog.mail.app-base-url:https://moneylog.mviana.tech}") String appBaseUrl, Environment environment) {
        MailProperties properties = new MailProperties(provider, from, replyTo, sesRegion, configurationSet, appBaseUrl,
                environment.acceptsProfiles(Profiles.of("prod")));
        if (MailProperties.FAKE.equals(properties.provider()) && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("MAIL_PROVIDER=fake não é permitido em produção.");
        }
        return properties;
    }

    @Bean
    public EmailSender emailSender(MailProperties properties, Environment environment) {
        switch (properties.provider()) {
            case MailProperties.SES -> {
                log.info("E-mail ligado: provedor=ses, regiao={}", properties.sesRegion());
                return new SesEmailSender(sesClient(properties), properties.from(), properties.replyTo(), properties.sesConfigurationSet());
            }
            case MailProperties.LOG -> {
                boolean dev = environment.acceptsProfiles(Profiles.of("dev"));
                log.info("E-mail simulado em log (MAIL_PROVIDER=log), link do convite no log: {}", dev);
                return new LogEmailSender(dev);
            }
            case MailProperties.FAKE -> {
                return new FakeEmailSender();
            }
            default -> {
                log.info("E-mail desligado (MAIL_PROVIDER=none).");
                return new DisabledEmailSender();
            }
        }
    }

    @Bean
    EmailTemplate emailTemplate(MailProperties properties) {
        return new EmailTemplateRenderer(properties.appBaseUrl());
    }

    private static SesV2Client sesClient(MailProperties properties) {
        return SesV2Client.builder()
                .region(Region.of(properties.sesRegion()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(CONNECT_TIMEOUT).socketTimeout(READ_TIMEOUT))
                .overrideConfiguration(ClientOverrideConfiguration.builder().apiCallTimeout(API_CALL_TIMEOUT).build())
                .build();
    }
}
