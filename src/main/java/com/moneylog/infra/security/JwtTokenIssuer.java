package com.moneylog.infra.security;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.sk.time.TimeProvider;

/** JWT HS256 de curta duração (claim {@code sub} = userId) e refresh opaco de 256 bits. */
public class JwtTokenIssuer implements TokenIssuer {

    private final JwtEncoder encoder;
    private final TimeProvider time;
    private final MoneylogAuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    public JwtTokenIssuer(JwtEncoder encoder, TimeProvider time, MoneylogAuthProperties properties) {
        this.encoder = encoder;
        this.time = time;
        this.properties = properties;
    }

    @Override
    public AccessToken issueAccessToken(UUID userId) {
        Instant now = time.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(userId.toString()).issuedAt(now).expiresAt(expiresAt)
                .id(UUID.randomUUID().toString()).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new AccessToken(token, expiresAt);
    }

    @Override
    public String newRefreshTokenValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
