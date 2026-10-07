package com.moneylog.infra.exception;

/**
 * Valor de caminho recusado na fronteira web com um {@code code} próprio (ex.: {@code SUMMARY_MONTH_INVALID}): o
 * {@link GlobalExceptionHandler} responde {@code 400} com esse {@code code} e o campo em {@code errors[]}.
 */
public class InvalidPathValueException extends RuntimeException {

    private final String code;
    private final String field;

    public InvalidPathValueException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}
