package com.moneylog.infra.security;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;

public class BcryptPasswordVerifier implements PasswordVerifier {

    static final int NEW_HASH_COST = 12;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(NEW_HASH_COST);
    private final String environmentHash;
    private final String dummyHash;

    public BcryptPasswordVerifier(OwnerPasswordHash ownerHash) {
        this.environmentHash = ownerHash == null ? null : ownerHash.value();
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        this.dummyHash = encoder.encode(Base64.getEncoder().encodeToString(random));
    }

    @Override
    public boolean verifyOwner(String rawPassword) {
        if (environmentHash == null) {
            verifyDummy(rawPassword);
            return false;
        }
        return encoder.matches(rawPassword, environmentHash);
    }

    @Override
    public boolean hasEnvironmentHash() {
        return environmentHash != null;
    }

    @Override
    public boolean matches(String rawPassword, String storedHash) {
        return encoder.matches(rawPassword, storedHash);
    }

    @Override
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public void verifyDummy(String rawPassword) {
        encoder.matches(rawPassword, dummyHash);
    }
}
