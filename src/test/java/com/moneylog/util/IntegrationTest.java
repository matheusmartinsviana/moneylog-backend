package com.moneylog.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Meta-anotação dos testes de integração (BE 07).
 *
 * <ul>
 *   <li>Tag {@code i}: só roda no failsafe ({@code ./mvnw verify}); o nome da classe termina em {@code IT}.</li>
 *   <li>Perfil {@code it} e Postgres real via Testcontainers ({@link PostgresTestcontainer}), sem H2.</li>
 *   <li>Contexto completo com MockMvc.</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("i")
@ActiveProfiles("it")
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestcontainer.class, ItAuth.class})
public @interface IntegrationTest {
}
