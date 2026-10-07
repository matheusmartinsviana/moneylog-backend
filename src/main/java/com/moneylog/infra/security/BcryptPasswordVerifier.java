package com.moneylog.infra.security;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;

/** {@link PasswordVerifier} com BCrypt: o hash do dono e um hash fictício de mesmo custo. */
public class BcryptPasswordVerifier implements PasswordVerifier {

    private final BCryptPasswordEncoder encoder;
    private final String ownerHash;
    private final String dummyHash;

    public BcryptPasswordVerifier(OwnerPasswordHash ownerHash) {
        this.encoder = new BCryptPasswordEncoder(ownerHash.cost());
        this.ownerHash = ownerHash.value();
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        this.dummyHash = encoder.encode(Base64.getEncoder().encodeToString(random));
    }

    @Override
    public boolean verifyOwner(String rawPassword) {
        return encoder.matches(rawPassword, ownerHash);
    }

    @Override
    public void verifyDummy(String rawPassword) {
        encoder.matches(rawPassword, dummyHash);
    }
}
