package com.moneylog.core.mail.app;

import java.util.regex.Pattern;

public record EmailMessage(String to, String subject, String html, String text, String replyTo) {

    private static final Pattern ADDRESS = Pattern.compile("[^\\s@<>\",;]+@[^\\s@<>\",;]+");

    public EmailMessage {
        to = required(to, "to");
        subject = required(subject, "subject");
        html = required(html, "html");
        text = required(text, "text");
        replyTo = replyTo == null || replyTo.isBlank() ? null : replyTo.strip();
        if (!ADDRESS.matcher(to).matches()) {
            throw new IllegalArgumentException("to deve ser um único endereço de e-mail");
        }
        if (replyTo != null && !ADDRESS.matcher(replyTo).matches()) {
            throw new IllegalArgumentException("replyTo deve ser um único endereço de e-mail");
        }
        if (subject.contains("\r") || subject.contains("\n")) {
            throw new IllegalArgumentException("subject não pode ter quebra de linha");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " é obrigatório");
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return "EmailMessage[to=" + EmailAddressMask.mask(to) + "]";
    }
}
