package com.moneylog.infra.web;

import java.io.IOException;

public class BodyTooLargeException extends IOException {

    public BodyTooLargeException() {
        super("Corpo da requisição acima do limite.");
    }
}
