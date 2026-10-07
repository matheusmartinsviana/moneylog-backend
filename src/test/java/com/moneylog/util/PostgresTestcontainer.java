package com.moneylog.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres real para os testes de integração, com a mesma imagem do {@code docker-compose.yml}.
 * O container é reaproveitado entre os testes do mesmo contexto Spring.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainer {

    public static final String IMAGE = loadImage();

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse(IMAGE));
    }

    private static String loadImage() {
        try (InputStream in = PostgresTestcontainer.class.getResourceAsStream("/it-images.properties")) {
            if (in == null) {
                throw new IllegalStateException("it-images.properties não encontrado no classpath de teste");
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("postgres.image");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
