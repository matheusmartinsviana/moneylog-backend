package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.AuthSession;

public interface RefreshSessionUseCase {

    /**
     * Rotaciona o refresh token (S-83).
     *
     * @throws com.moneylog.core.auth.exception.AuthExceptions.RefreshInvalidException token inválido, expirado,
     *         revogado ou reutilizado fora da janela (neste último caso a família é revogada <b>e a revogação é
     *         confirmada</b>: a fronteira transacional não faz rollback desta exceção)
     */
    AuthSession handle(RefreshCommand command);

    record RefreshCommand(String refreshToken) {
    }
}
