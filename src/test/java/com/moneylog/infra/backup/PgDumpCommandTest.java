package com.moneylog.infra.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Comando pg_dump/pg_restore")
class PgDumpCommandTest {

    @Test
    @DisplayName("lê host, porta e banco da URL JDBC e nunca põe a senha na linha de comando")
    void deveMontarArgumentosSemSenha() {
        PgDumpCommand c = PgDumpCommand.fromJdbcUrl("jdbc:postgresql://db.interno:5433/moneylog", "app", "SENTINELA-SENHA");

        assertThat(c.dumpArgs(Path.of("/tmp/x"))).contains("pg_dump", "--host", "db.interno", "--port", "5433", "--username", "app", "moneylog")
                .doesNotContain("SENTINELA-SENHA");
        assertThat(c.restoreArgs(Path.of("/tmp/x"))).contains("pg_restore", "--exit-on-error").doesNotContain("SENTINELA-SENHA");
    }

    @Test
    @DisplayName("sem porta na URL usa 5432")
    void deveUsarPortaPadrao() {
        assertThat(PgDumpCommand.fromJdbcUrl("jdbc:postgresql://localhost/moneylog", "u", "p").dumpArgs(Path.of("/tmp/x"))).contains("5432");
    }
}
