package com.moneylog.infra.backup;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.moneylog.core.backup.app.BackupException;
import com.moneylog.core.backup.app.DatabaseDumper;

/**
 * {@code pg_dump -Fc} / {@code pg_restore} como processo. A senha vai por {@code PGPASSWORD} (nunca na linha de comando, que aparece em
 * {@code ps}); a saída de erro do processo não é copiada para a exceção (pode ecoar dados).
 */
public class PgDumpCommand implements DatabaseDumper {

    private static final long TIMEOUT_MINUTES = 30;

    private final String host;
    private final int port;
    private final String database;
    private final String user;
    private final String password;

    public PgDumpCommand(String host, int port, String database, String user, String password) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.user = user;
        this.password = password;
    }

    /** Lê host, porta e banco de uma URL JDBC {@code jdbc:postgresql://host:5432/db}. */
    public static PgDumpCommand fromJdbcUrl(String jdbcUrl, String user, String password) {
        URI uri = URI.create(jdbcUrl.replaceFirst("^jdbc:", ""));
        String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        return new PgDumpCommand(uri.getHost(), uri.getPort() == -1 ? 5432 : uri.getPort(), db, user, password);
    }

    List<String> dumpArgs(Path target) {
        List<String> c = base("pg_dump");
        c.addAll(List.of("--format=custom", "--no-owner", "--no-privileges", "--file", target.toString(), database));
        return c;
    }

    List<String> restoreArgs(Path source) {
        List<String> c = base("pg_restore");
        c.addAll(List.of("--no-owner", "--no-privileges", "--exit-on-error", "--dbname", database, source.toString()));
        return c;
    }

    @Override
    public void dump(Path target) {
        run(dumpArgs(target), "pg_dump");
    }

    @Override
    public void restore(Path source) {
        run(restoreArgs(source), "pg_restore");
    }

    private List<String> base(String tool) {
        List<String> c = new ArrayList<>();
        c.addAll(List.of(tool, "--host", host, "--port", String.valueOf(port), "--username", user));
        return c;
    }

    private void run(List<String> command, String name) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("PGPASSWORD", password);
        try {
            Process p = pb.start();
            if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new BackupException(name + " excedeu " + TIMEOUT_MINUTES + " minutos");
            }
            if (p.exitValue() != 0) {
                throw new BackupException(name + " terminou com código " + p.exitValue());
            }
        } catch (IOException e) {
            throw new BackupException("não foi possível executar " + name + " (o cliente do Postgres está instalado?)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException(name + " interrompido", e);
        }
    }
}
