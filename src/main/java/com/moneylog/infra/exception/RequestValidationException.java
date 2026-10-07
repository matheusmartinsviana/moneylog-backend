package com.moneylog.infra.exception;

import java.util.List;

/**
 * Entrada recusada na fronteira web (parâmetro ausente ou fora do limite, formato inválido). O
 * {@link GlobalExceptionHandler} responde {@code 400 VALIDATION_FAILED} com {@code errors[]}.
 */
public class RequestValidationException extends RuntimeException {

    private final transient List<FieldIssue> issues;

    public RequestValidationException(List<FieldIssue> issues) {
        super("Requisição inválida: " + issues);
        this.issues = List.copyOf(issues);
    }

    public RequestValidationException(String field, String code, String message) {
        this(List.of(new FieldIssue(field, code, message)));
    }

    public List<FieldIssue> issues() {
        return issues;
    }

    /** Um item de {@code errors[]} do ProblemDetail. */
    public record FieldIssue(String field, String code, String message) {
    }
}
