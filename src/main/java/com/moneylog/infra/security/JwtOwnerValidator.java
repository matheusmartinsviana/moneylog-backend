package com.moneylog.infra.security;

import java.util.UUID;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import com.moneylog.core.auth.app.gateways.UserGateway;

/**
 * O dono do token existe (B6): um JWT válido de um usuário que não está no banco (removido, ou assinado por quem tem o
 * segredo) é {@code 401}, em vez de seguir até uma FK que vira {@code 500}.
 */
public class JwtOwnerValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error UNKNOWN_OWNER = new OAuth2Error("invalid_token", "O dono do token não existe.", null);

    private final UserGateway users;

    public JwtOwnerValidator(UserGateway users) {
        this.users = users;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            if (users.existsById(UUID.fromString(token.getSubject()))) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            // subject ausente ou que não é UUID: cai na falha abaixo
        }
        return OAuth2TokenValidatorResult.failure(UNKNOWN_OWNER);
    }
}
