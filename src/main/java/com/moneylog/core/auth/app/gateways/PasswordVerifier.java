package com.moneylog.core.auth.app.gateways;

public interface PasswordVerifier {

    boolean verifyOwner(String rawPassword);

    default boolean hasEnvironmentHash() {
        return true;
    }

    default boolean matches(String rawPassword, String storedHash) {
        return false;
    }

    default String encode(String rawPassword) {
        throw new UnsupportedOperationException();
    }

    void verifyDummy(String rawPassword);
}
