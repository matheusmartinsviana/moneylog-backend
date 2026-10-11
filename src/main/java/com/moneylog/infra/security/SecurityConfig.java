package com.moneylog.infra.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

import tools.jackson.databind.ObjectMapper;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.sk.time.TimeProvider;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties({MoneylogSecurityProperties.class, MoneylogAuthProperties.class, MoneylogOwnerProperties.class})
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder jwtDecoder,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            resolver.resolveException(request, response, null, exception);
        };
        AccessDeniedHandler deniedHandler = (request, response, exception) ->
                resolver.resolveException(request, response, null, exception);
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/v1/auth/login", "/v1/auth/refresh", "/v1/auth/logout", "/v1/auth/setup-password").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/auth/status").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
        return http.build();
    }

    @Bean
    RequestRejectedHandler requestRejectedHandler(ObjectMapper mapper) {
        return new ProblemRequestRejectedHandler(mapper);
    }

    @Bean
    JwtEncoder jwtEncoder(MoneylogSecurityProperties properties) {
        SecretKey key = JwtSecret.toKey(properties.jwtSecret());
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(MoneylogSecurityProperties properties, Clock clock, UserGateway users) {
        SecretKey key = JwtSecret.toKey(properties.jwtSecret());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                new JwtClaimValidator<Object>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<Object>(JwtClaimNames.SUB, Objects::nonNull),
                new JwtClaimValidator<Object>(JwtClaimNames.IAT, Objects::nonNull),
                timestamps, new JwtOwnerValidator(users)));
        return decoder;
    }

    @Bean
    TokenIssuer tokenIssuer(JwtEncoder encoder, TimeProvider time, MoneylogAuthProperties properties) {
        return new JwtTokenIssuer(encoder, time, properties);
    }

    @Bean
    PasswordVerifier passwordVerifier(MoneylogOwnerProperties owner) {
        return new BcryptPasswordVerifier(OwnerPasswordHash.resolveOptional(owner.passwordHash(), owner.passwordHashFile()));
    }

    @Bean
    RefreshCookies refreshCookies(MoneylogAuthProperties auth, MoneylogSecurityProperties security, Environment environment) {
        CookieSecurityGuard.validate(auth.cookieSecure(), environment.acceptsProfiles(Profiles.of("dev")),
                security.allowedOrigins());
        return new RefreshCookies(auth.cookieSecure());
    }

    @Bean
    OriginCheck originCheck(MoneylogSecurityProperties security) {
        return new OriginCheck(security.allowedOrigins());
    }

    @Bean
    ClientIpResolver clientIpResolver(MoneylogSecurityProperties security, Environment environment) {
        TrustedProxiesGuard.validate(security.trustedProxies(), environment);
        return new ClientIpResolver(security.trustedProxies());
    }

    @Bean
    LoginRateLimiter loginRateLimiter(Clock clock, MoneylogAuthProperties auth) {
        return new LoginRateLimiter(clock, auth.maxLoginFailures(), auth.loginWindow());
    }
}
