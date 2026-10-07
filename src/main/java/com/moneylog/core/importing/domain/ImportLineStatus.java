package com.moneylog.core.importing.domain;

/**
 * Status de uma linha em revisão (D1 §6).
 */
public enum ImportLineStatus {
    /** Nova, aguardando decisão. */
    PENDING,
    /** Será (ou foi) aplicada como movimentação. */
    ACCEPTED,
    /** A mesma chave já existe numa movimentação ativa. Só entra se o usuário forçar. */
    DUPLICATE,
    /** Parece um lançamento manual já registrado (mesma origem e valor, data ±1 dia, S-25). Exige decisão. */
    POSSIBLE_DUPLICATE,
    /** Descartada pelo usuário. */
    REJECTED
}
