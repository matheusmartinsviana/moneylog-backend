package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.app.AuthSession;

public interface SetupPasswordUseCase {

    /**
     * Primeiro acesso (ADR-015): define a senha do dono e abre a sessão. Exige o e-mail do dono e o código de uso único.
     * Qualquer causa de recusa do código/e-mail/estado responde igual ({@code InvalidSetupCodeException}); senha fraca
     * é {@code WeakPasswordException} e não consome o código. Revoga todas as sessões anteriores.
     */
    AuthSession handle(SetupPasswordCommand command);

    record SetupPasswordCommand(String email, String setupCode, String newPassword) {
    }
}
