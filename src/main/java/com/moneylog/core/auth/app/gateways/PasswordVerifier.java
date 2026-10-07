package com.moneylog.core.auth.app.gateways;

/**
 * Verificação de senha do dono (ADR-003): o hash BCrypt vem do ambiente, nunca do banco.
 */
public interface PasswordVerifier {

    /** A senha confere com a do dono? */
    boolean verifyOwner(String rawPassword);

    /**
     * Gasta o mesmo custo de uma verificação real comparando com um hash fictício, para e-mail desconhecido e senha
     * errada levarem o mesmo tempo (S-86).
     */
    void verifyDummy(String rawPassword);
}
