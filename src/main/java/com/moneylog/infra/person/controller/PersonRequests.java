package com.moneylog.infra.person.controller;

import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class PersonRequests {

    private PersonRequests() {
    }

    public record PersonInput(@NotBlank @Size(max = 100) @NoControlChars String name,
                              @Size(max = 2000) @NoControlChars(allowLineBreaks = true) String notes) {
    }

    public record PersonUpdate(@NotBlank @Size(max = 100) @NoControlChars String name,
                               @Size(max = 2000) @NoControlChars(allowLineBreaks = true) String notes, @NotNull Long version) {
    }
}
