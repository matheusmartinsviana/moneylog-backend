package com.moneylog.infra.security;

import org.springframework.security.core.AuthenticationException;

public class QuickTokenInvalidException extends AuthenticationException {

    public QuickTokenInvalidException() {
        super("Token de lançamento rápido inválido");
    }
}
