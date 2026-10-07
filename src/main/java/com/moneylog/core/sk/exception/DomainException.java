package com.moneylog.core.sk.exception;

import java.util.Objects;

/**
 * Base de todas as exceções de domínio. O {@code code} é estável (string em inglês, ex.: {@code ACCOUNT_ARCHIVED})
 * e é o que a API devolve; a mensagem é só para log e nunca vaza num 500.
 *
 * <p>Hierarquia (o handler da Etapa 2 mapeia pela classe base):
 * <ul>
 *   <li>{@link NotFoundException} → 404</li>
 *   <li>{@link BusinessRuleException} → 422</li>
 *   <li>{@link ConflictException} → 409</li>
 *   <li>subclasse direta de {@code DomainException} → invariante interna quebrada (bug) → 500</li>
 * </ul>
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = requireCode(code);
    }

    protected DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = requireCode(code);
    }

    public String code() {
        return code;
    }

    private static String requireCode(String code) {
        Objects.requireNonNull(code, "code");
        if (!code.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("code deve ser UPPER_SNAKE_CASE: " + code);
        }
        return code;
    }
}
