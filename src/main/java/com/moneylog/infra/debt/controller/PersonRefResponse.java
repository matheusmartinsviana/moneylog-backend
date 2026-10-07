package com.moneylog.infra.debt.controller;

import java.util.UUID;

/** {@code PersonRef} do contrato. */
public record PersonRefResponse(UUID id, String name) {
}
