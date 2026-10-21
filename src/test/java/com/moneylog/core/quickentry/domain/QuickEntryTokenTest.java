package com.moneylog.core.quickentry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionSource;

@DisplayName("Token de lançamento rápido: invariantes, estado e origem QUICK_SHORTCUT")
class QuickEntryTokenTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final Instant EXPIRES = NOW.plusSeconds(30L * 24 * 3600);
    private static final String HASH = "a".repeat(64);

    private QuickEntryToken token() {
        return QuickEntryToken.issue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "  Toque duplo ", "abcd1234", HASH, NOW, EXPIRES);
    }

    @Test
    @DisplayName("nasce ativo, com escopo QUICK_ENTRY, nome aparado e sem uso nem revogação")
    void deveNascerAtivo() {
        QuickEntryToken token = token();
        assertThat(token.statusAt(NOW)).isEqualTo(QuickEntryTokenStatus.ACTIVE);
        assertThat(token.scope()).isEqualTo(QuickEntryScope.QUICK_ENTRY);
        assertThat(token.label()).isEqualTo("Toque duplo");
        assertThat(token.lastUsedAt()).isNull();
        assertThat(token.revokedAt()).isNull();
    }

    @Test
    @DisplayName("expira exatamente no instante de validade")
    void deveExpirarNoLimite() {
        QuickEntryToken token = token();
        assertThat(token.statusAt(EXPIRES.minusMillis(1))).isEqualTo(QuickEntryTokenStatus.ACTIVE);
        assertThat(token.statusAt(EXPIRES)).isEqualTo(QuickEntryTokenStatus.EXPIRED);
        assertThat(token.isActiveAt(EXPIRES)).isFalse();
    }

    @Test
    @DisplayName("revogar é idempotente e mantém a data e o motivo da primeira revogação")
    void deveRevogarUmaVez() {
        QuickEntryToken revoked = token().revoke(TokenRevocationReason.USER, NOW.plusSeconds(10));
        QuickEntryToken again = revoked.revoke(TokenRevocationReason.USER_DISABLED, NOW.plusSeconds(99));
        assertThat(again).isSameAs(revoked);
        assertThat(again.statusAt(NOW.plusSeconds(100))).isEqualTo(QuickEntryTokenStatus.REVOKED);
        assertThat(again.revokedReason()).isEqualTo(TokenRevocationReason.USER);
        assertThat(again.revokedAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    @DisplayName("revogado e vencido continua REVOKED")
    void deveManterRevogadoDepoisDeVencer() {
        QuickEntryToken revoked = token().revoke(TokenRevocationReason.USER, NOW);
        assertThat(revoked.statusAt(EXPIRES.plusSeconds(1))).isEqualTo(QuickEntryTokenStatus.REVOKED);
    }

    @Test
    @DisplayName("user agent é truncado em 120 caracteres")
    void deveTruncarUserAgent() {
        QuickEntryToken used = token().usedAt(NOW, "203.0.113.0", "x".repeat(300));
        assertThat(used.lastUsedUserAgent()).hasSize(QuickEntryToken.MAX_USER_AGENT_LENGTH);
        assertThat(used.lastUsedAt()).isEqualTo(NOW);
        assertThat(used.lastUsedIp()).isEqualTo("203.0.113.0");
    }

    @Test
    @DisplayName("rejeita nome vazio ou longo, prefixo e hash com tamanho errado, validade não posterior à criação e revogação pela metade")
    void deveRejeitarInvalidos() {
        UUID id = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        assertThatThrownBy(() -> QuickEntryToken.issue(id, user, account, " ", "abcd1234", HASH, NOW, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuickEntryToken.issue(id, user, account, "n".repeat(61), "abcd1234", HASH, NOW, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuickEntryToken.issue(id, user, account, "ok", "abc", HASH, NOW, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuickEntryToken.issue(id, user, account, "ok", "abcd1234", "curto", NOW, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuickEntryToken.issue(id, user, account, "ok", "abcd1234", HASH, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuickEntryToken(id, user, account, "ok", "abcd1234", HASH, QuickEntryScope.QUICK_ENTRY, EXPIRES, NOW, null,
                null, null, null, NOW, 0L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("renomear mantém o resto; a origem QUICK_SHORTCUT não carrega lote, chave nem recorrência")
    void deveRenomearEExporOrigem() {
        QuickEntryToken renamed = token().rename("Atalho do Pix");
        assertThat(renamed.label()).isEqualTo("Atalho do Pix");
        assertThat(renamed.tokenHash()).isEqualTo(HASH);
        TransactionOrigin origin = TransactionOrigin.quickShortcut();
        assertThat(origin.source()).isEqualTo(TransactionSource.QUICK_SHORTCUT);
        assertThat(origin.importBatchId()).isNull();
        assertThat(origin.recurrenceId()).isNull();
    }
}
