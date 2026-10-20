package com.moneylog.core.mail.app;

import java.util.Objects;

public record RenderedEmail(String subject, String html, String text) {

    public RenderedEmail {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(text, "text");
    }
}
