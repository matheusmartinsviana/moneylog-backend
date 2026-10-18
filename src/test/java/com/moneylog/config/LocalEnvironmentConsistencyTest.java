package com.moneylog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.util.PostgresTestcontainer;

@DisplayName("Configuração local consistente")
class LocalEnvironmentConsistencyTest {

    private static final Path BACKEND = Path.of("").toAbsolutePath();
    private static final Path ROOT = BACKEND;
    private static final Pattern PLACEHOLDER = Pattern.compile("(?<!\\$)\\$\\{([A-Z][A-Z0-9_]*)(?::[^}]*)?}");
    private static final Pattern ENV_LINE = Pattern.compile("^([A-Z][A-Z0-9_]*)=(.*)$");

    @Test
    @DisplayName("toda variável usada no application*.yaml e no compose existe no .env.example")
    void deveDeclararTodasAsVariaveisNoEnvExample() throws IOException {
        Set<String> usadas = new LinkedHashSet<>();
        try (Stream<Path> yamls = Files.list(BACKEND.resolve("src/main/resources"))) {
            for (Path yaml : yamls.filter(p -> p.getFileName().toString().matches("application.*\\.ya?ml")).toList()) {
                usadas.addAll(placeholders(Files.readString(yaml)));
            }
        }
        usadas.addAll(placeholders(Files.readString(ROOT.resolve("docker-compose.yml"))));

        Set<String> declaradas = envExample().stream().map(l -> l.split("=", 2)[0]).collect(java.util.stream.Collectors.toSet());

        assertThat(usadas).isNotEmpty();
        assertThat(declaradas).containsAll(usadas).contains(
                "DB_URL", "DB_USER", "DB_PASSWORD", "JWT_SECRET", "AI_PROVIDER", "AI_API_KEY", "AI_MODEL",
                "MONEYLOG_OWNER_EMAIL", "MONEYLOG_OWNER_PASSWORD_HASH");
    }

    @Test
    @DisplayName("placeholders: ${A} e ${A:x} contam; $${NAO} (escapado) e nome fora do padrão não")
    void deveReconhecerPlaceholdersDoSpring() {
        assertThat(placeholders("a: ${A}\nb: ${A:x}\nc: $${NAO}\nd: ${minuscula}\ne: ${B_2:${C}}")).containsExactly("A", "B_2");
    }

    @Test
    @DisplayName(".env.example não traz segredo preenchido e o hash fica entre aspas simples")
    void naoDeveTerSegredoNoEnvExample() throws IOException {
        for (String linha : envExample()) {
            String[] kv = linha.split("=", 2);
            if (List.of("DB_PASSWORD", "JWT_SECRET", "AI_API_KEY", "MONEYLOG_OWNER_EMAIL").contains(kv[0])) {
                assertThat(kv[1]).as(kv[0]).isEmpty();
            }
            if (kv[0].equals("MONEYLOG_OWNER_PASSWORD_HASH")) {
                assertThat(kv[1]).as(kv[0]).isEqualTo("''");
            }
            if (kv[0].equals("AI_PROVIDER")) {
                assertThat(kv[1]).isEqualTo("none");
            }
        }
    }

    @Test
    @DisplayName("o compose usa a mesma imagem pinada dos testes de integração e não tem senha fixa")
    void deveUsarAMesmaImagemDoPostgres() throws IOException {
        String compose = Files.readString(ROOT.resolve("docker-compose.yml"));

        assertThat(PostgresTestcontainer.IMAGE).isEqualTo("postgres:17");
        assertThat(compose).contains("image: " + PostgresTestcontainer.IMAGE + "\n");
        assertThat(compose).containsPattern("POSTGRES_PASSWORD: \\$\\{DB_PASSWORD");
        assertThat(compose).contains("healthcheck:");
    }

    @Test
    @DisplayName("nenhum application*.yaml principal traz senha literal")
    void naoDeveTerSenhaNosYamlsPrincipais() throws IOException {
        String base = Files.readString(BACKEND.resolve("src/main/resources/application.yaml"));
        assertThat(base).contains("password: ${DB_PASSWORD}").contains("jwt-secret: ${JWT_SECRET}");
        assertThat(Files.readString(BACKEND.resolve("src/main/resources/application-dev.yaml"))).doesNotContain("password");
    }

    @Test
    @DisplayName(".env está no .gitignore")
    void deveIgnorarODotEnv() throws IOException {
        assertThat(Files.readAllLines(ROOT.resolve(".gitignore"))).contains(".env", "!.env.example");
    }

    private static Set<String> placeholders(String content) {
        Set<String> nomes = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(content);
        while (m.find()) {
            nomes.add(m.group(1));
        }
        return nomes;
    }

    private static List<String> envExample() throws IOException {
        List<String> linhas = new ArrayList<>();
        for (String linha : Files.readAllLines(ROOT.resolve(".env.example"))) {
            if (ENV_LINE.matcher(linha).matches()) {
                linhas.add(linha);
            }
        }
        return linhas;
    }
}
