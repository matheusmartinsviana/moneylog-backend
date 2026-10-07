package com.moneylog.infra.category.controller;

import com.moneylog.core.category.domain.CategoryKind;

import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corpos de entrada de {@code /v1/categories} (contrato: {@code CategoryInput} e {@code CategoryUpdate}). */
public final class CategoryRequests {

    private CategoryRequests() {
    }

    public record CategoryInput(@NotBlank @Size(max = 60) @NoControlChars String name, @NotNull CategoryKind kind) {
    }

    public record CategoryUpdate(@NotBlank @Size(max = 60) @NoControlChars String name, @NotNull Long version) {
    }
}
