package com.moneylog.infra.exception;

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
