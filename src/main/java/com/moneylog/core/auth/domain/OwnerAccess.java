package com.moneylog.core.auth.domain;

import java.time.Instant;

/**
 * Estado de acesso do dono (ADR-015): a senha do banco, se houver, e o código de uso único do primeiro acesso.
 * Só guarda hashes; nada em claro.
 *
 * @param passwordHash          BCrypt da senha escolhida pelo dono, ou {@code null}
 * @param passwordSetupPending  o dono pediu redefinição e ainda não escolheu a nova senha
 * @param setupCodeHash         SHA-256 (hex) do código de primeiro acesso ainda não usado, ou {@code null}
 * @param setupCodeExpiresAt    validade do código
 */
public record OwnerAccess(String passwordHash, boolean passwordSetupPending, String setupCodeHash, Instant setupCodeExpiresAt) {

    public boolean hasStoredPassword() {
        return passwordHash != null;
    }

    /**
     * Falta o dono escolher a senha? Sim quando não há senha no banco e (ou há redefinição pedida, ou não existe hash no
     * ambiente para a retrocompatibilidade).
     */
    public boolean passwordSetupRequired(boolean environmentHashConfigured) {
        return passwordHash == null && (passwordSetupPending || !environmentHashConfigured);
    }

    public boolean setupCodeValidAt(Instant now) {
        return setupCodeHash != null && setupCodeExpiresAt != null && now.isBefore(setupCodeExpiresAt);
    }
}
