package com.moneylog.core.debt.domain;

/** Com movimentação (nasce junto com a única movimentação de origem, S-41) ou sem (reconhecimento contra EQUITY). */
public enum DebtOrigin {
    WITH_MOVEMENT,
    WITHOUT_MOVEMENT
}
