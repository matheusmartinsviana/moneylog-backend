package com.moneylog.core.auth.app;

import java.util.UUID;

/**
 * Evento publicado <b>depois do commit</b> quando o dono é criado (primeiro boot). Dispara o seed de categorias
 * (S-38, S-59).
 */
public record OwnerCreated(UUID userId, String email) {
}
