package com.moneylog.infra.security;

public class OriginNotAllowedException extends RuntimeException {

    public OriginNotAllowedException() {
        super("Origin não permitido");
    }
}
