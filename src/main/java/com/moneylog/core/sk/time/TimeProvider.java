package com.moneylog.core.sk.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Porta do relógio. "Hoje" e "agora" nunca vêm de {@code LocalDate.now()} direto: sempre daqui, para que os testes
 * sejam determinísticos (D1 §1.2).
 */
public interface TimeProvider {

    /** Instante técnico em UTC (createdAt, voidedAt...). */
    Instant now();

    /** Data financeira de hoje no fuso do usuário (S-1). */
    LocalDate today();

    ZoneId zone();
}
