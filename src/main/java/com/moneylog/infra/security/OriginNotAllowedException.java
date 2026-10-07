package com.moneylog.infra.security;

/** O {@code Origin} da requisição não está em {@code MONEYLOG_ALLOWED_ORIGINS} (proteção CSRF do refresh e do logout). */
public class OriginNotAllowedException extends RuntimeException {

    public OriginNotAllowedException() {
        super("Origin não permitido");
    }
}
