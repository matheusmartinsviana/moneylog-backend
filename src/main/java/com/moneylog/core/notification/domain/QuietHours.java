package com.moneylog.core.notification.domain;

import java.time.LocalTime;
import java.util.Objects;

/** Janela de silêncio do push (padrão 22:00 a 07:00). A janela pode atravessar a meia-noite; {@code from == to} = sem silêncio. */
public record QuietHours(LocalTime from, LocalTime to) {

    public static final QuietHours DEFAULT = new QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0));

    public QuietHours {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    public boolean contains(LocalTime time) {
        if (from.equals(to)) {
            return false;
        }
        return from.isBefore(to) ? !time.isBefore(from) && time.isBefore(to) : !time.isBefore(from) || time.isBefore(to);
    }
}
