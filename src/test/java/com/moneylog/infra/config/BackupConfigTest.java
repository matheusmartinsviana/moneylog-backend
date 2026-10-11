package com.moneylog.infra.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BackupConfig: cifra obrigatória em produção")
class BackupConfigTest {

    @Test
    @DisplayName("prod com backup habilitado e sem chave: falha fechada")
    void deveRecusarProdSemChave() {
        assertThatThrownBy(() -> BackupConfig.requireEncryptionKeyInProd(true, ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BACKUP_ENCRYPTION_KEY");
        assertThatThrownBy(() -> BackupConfig.requireEncryptionKeyInProd(true, "   ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> BackupConfig.requireEncryptionKeyInProd(true, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("prod com chave, ou fora de prod sem chave: segue")
    void devePermitirComChaveOuForaDeProd() {
        assertThatCode(() -> BackupConfig.requireEncryptionKeyInProd(true, "chave")).doesNotThrowAnyException();
        assertThatCode(() -> BackupConfig.requireEncryptionKeyInProd(false, "")).doesNotThrowAnyException();
    }
}
