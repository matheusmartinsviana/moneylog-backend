package com.moneylog.core.auth.app.gateways;

/**
 * Verificação e geração de hash de senha (ADR-003, ADR-015). A senha do dono pode estar no banco (vale mais) ou, por
 * retrocompatibilidade, no hash BCrypt do ambiente.
 */
public interface PasswordVerifier {

    /** A senha confere com o hash do <b>ambiente</b>? {@code false} se não há hash no ambiente. */
    boolean verifyOwner(String rawPassword);

    /** Existe hash de senha no ambiente (retrocompatibilidade)? */
    default boolean hasEnvironmentHash() {
        return true;
    }

    /** A senha confere com este hash BCrypt guardado no banco? */
    default boolean matches(String rawPassword, String storedHash) {
        return false;
    }

    /** Gera o hash BCrypt de uma senha nova. */
    default String encode(String rawPassword) {
        throw new UnsupportedOperationException();
    }

    /**
     * Gasta o mesmo custo de uma verificação real comparando com um hash fictício, para e-mail desconhecido e senha
     * errada levarem o mesmo tempo (S-86).
     */
    void verifyDummy(String rawPassword);
}
