package com.moneylog.core.auth.app.usecase;

import java.time.Duration;
import java.time.Instant;

import com.moneylog.core.auth.app.AuthSession;
import com.moneylog.core.auth.app.RefreshTokenHash;
import com.moneylog.core.auth.app.SessionOpener;
import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.domain.RefreshToken;
import com.moneylog.core.auth.exception.AuthExceptions.RefreshInvalidException;
import com.moneylog.core.sk.time.TimeProvider;

/**
 * Refresh rotativo com detecção de reuso (ADR-003, S-83):
 * <ul>
 *   <li>token ativo e ainda não usado: gera o sucessor na mesma família e marca o antigo como substituído;</li>
 *   <li>token já substituído, reapresentado dentro da janela de tolerância: gera outro sucessor na mesma família, sem
 *       revogar nada (duas abas, F5 durante o refresh);</li>
 *   <li>token já substituído, fora da janela: reuso, revoga a família inteira;</li>
 *   <li>token revogado (logout ou reuso), expirado ou desconhecido: recusado, sem tolerância.</li>
 * </ul>
 * A linha do token é travada ({@code FOR UPDATE}), então duas requisições simultâneas com o mesmo cookie se
 * serializam e as duas recebem um par válido.
 */
public class RefreshSessionUseCaseImpl implements RefreshSessionUseCase {

    private final RefreshTokenGateway refreshTokens;
    private final SessionOpener sessions;
    private final TimeProvider time;
    private final Duration reuseGrace;

    public RefreshSessionUseCaseImpl(RefreshTokenGateway refreshTokens, SessionOpener sessions, TimeProvider time, Duration reuseGrace) {
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.time = time;
        this.reuseGrace = reuseGrace;
    }

    @Override
    public AuthSession handle(RefreshCommand command) {
        if (command.refreshToken() == null || command.refreshToken().isBlank()) {
            throw new RefreshInvalidException();
        }
        Instant now = time.now();
        RefreshToken token = refreshTokens.findByHashForUpdate(RefreshTokenHash.of(command.refreshToken()))
                .orElseThrow(RefreshInvalidException::new);
        if (token.isRevoked() || token.isExpired(now)) {
            throw new RefreshInvalidException();
        }
        if (!token.isReplaced()) {
            AuthSession session = sessions.open(token.userId(), token.familyId(), token.familyStartedAt(), now);
            refreshTokens.markReplaced(token.id(), session.refreshTokenId(), now);
            return session;
        }
        if (Duration.between(token.replacedAt(), now).compareTo(reuseGrace) <= 0) {
            return sessions.open(token.userId(), token.familyId(), token.familyStartedAt(), now);
        }
        refreshTokens.revokeFamily(token.familyId(), now);
        throw new RefreshInvalidException();
    }
}
