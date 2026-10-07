package com.moneylog.infra.export.controller;

import java.time.Clock;
import java.time.Duration;

import com.moneylog.infra.web.UserRateLimiter;

/**
 * Limite de taxa simples das rotas de exportação (S-260, M1 da revisão da Etapa 5): no máximo {@code maxRequests} pedidos por usuário numa
 * janela deslizante, em memória, como o do login ({@code LoginRateLimiter}). A exportação carrega o razão inteiro: sem limite, um cliente em
 * laço pressiona a JVM da instância pequena. Estourou: {@code 429 TOO_MANY_ATTEMPTS} com {@code Retry-After}. A mecânica é a de
 * {@link UserRateLimiter} (compartilhada com a busca global, que tem limite próprio).
 */
public class ExportRateLimiter extends UserRateLimiter {

    public ExportRateLimiter(Clock clock, int maxRequests, Duration window) {
        super(clock, maxRequests, window);
    }
}
