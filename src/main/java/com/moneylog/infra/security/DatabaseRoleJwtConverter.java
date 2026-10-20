package com.moneylog.infra.security;

import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;

public class DatabaseRoleJwtConverter implements Converter<Jwt, AbstractOAuth2TokenAuthenticationToken<Jwt>> {

    static final String ROLE_PREFIX = "ROLE_";

    private final UserGateway users;

    public DatabaseRoleJwtConverter(UserGateway users) {
        this.users = users;
    }

    @Override
    public AbstractOAuth2TokenAuthenticationToken<Jwt> convert(Jwt token) {
        User user = parse(token.getSubject()).flatMap(users::findById).filter(User::isActive)
                .orElseThrow(() -> new InvalidBearerTokenException("O usuário do token não existe ou está desativado."));
        return new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority(ROLE_PREFIX + user.role().name())),
                token.getSubject());
    }

    private static java.util.Optional<UUID> parse(String subject) {
        try {
            return java.util.Optional.of(UUID.fromString(subject));
        } catch (IllegalArgumentException | NullPointerException e) {
            return java.util.Optional.empty();
        }
    }
}
