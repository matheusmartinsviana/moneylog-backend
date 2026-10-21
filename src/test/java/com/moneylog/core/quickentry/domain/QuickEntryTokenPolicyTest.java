package com.moneylog.core.quickentry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickAccountNotAllowedException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickTokenLimitReachedException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickTokenValidityInvalidException;

@DisplayName("Política do token de lançamento rápido: validade, limite e conta")
class QuickEntryTokenPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    private final QuickEntryTokenPolicy policy = new QuickEntryTokenPolicy(30, 90, 3);

    @Test
    @DisplayName("validade padrão é 30 dias e o máximo é 90")
    void deveAplicarValidade() {
        assertThat(policy.expiresAt(NOW, null)).isEqualTo(NOW.plusSeconds(30L * 86400));
        assertThat(policy.expiresAt(NOW, 1)).isEqualTo(NOW.plusSeconds(86400));
        assertThat(policy.expiresAt(NOW, 90)).isEqualTo(NOW.plusSeconds(90L * 86400));
        assertThatThrownBy(() -> policy.expiresAt(NOW, 91)).isInstanceOf(QuickTokenValidityInvalidException.class);
        assertThatThrownBy(() -> policy.expiresAt(NOW, 0)).isInstanceOf(QuickTokenValidityInvalidException.class);
    }

    @Test
    @DisplayName("o terceiro token ativo ainda passa e o quarto bate no limite")
    void deveLimitarAtivos() {
        assertThatCode(() -> policy.ensureCanIssue(2)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.ensureCanIssue(3)).isInstanceOf(QuickTokenLimitReachedException.class);
    }

    @ParameterizedTest
    @EnumSource(AccountType.class)
    @DisplayName("só corrente, poupança e carteira ativas servem")
    void deveFiltrarTipo(AccountType type) {
        boolean allowed = type != AccountType.INVESTMENT;
        if (allowed) {
            assertThatCode(() -> policy.ensureAccountAllowed(type, AccountStatus.ACTIVE)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> policy.ensureAccountAllowed(type, AccountStatus.ACTIVE)).isInstanceOf(QuickAccountNotAllowedException.class);
        }
        assertThatThrownBy(() -> policy.ensureAccountAllowed(type, AccountStatus.ARCHIVED)).isInstanceOf(QuickAccountNotAllowedException.class);
    }

    @Test
    @DisplayName("configuração incoerente é recusada na construção")
    void deveRecusarConfiguracaoInvalida() {
        assertThatThrownBy(() -> new QuickEntryTokenPolicy(30, 91, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuickEntryTokenPolicy(60, 30, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuickEntryTokenPolicy(30, 90, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
