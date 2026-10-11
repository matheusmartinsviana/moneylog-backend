package com.moneylog.infra.exception;

import java.util.List;

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

    public record FieldIssue(String field, String code, String message) {
    }
}
