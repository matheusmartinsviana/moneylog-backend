package com.moneylog.infra.auth.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetupPasswordRequest(@NotBlank @Email @Size(max = 254) String email,
                                   @NotBlank @Size(max = 200) String setupCode,
                                   @NotBlank @Size(max = 200) String newPassword) {

    @Override
    public String toString() {
        return "SetupPasswordRequest[***]";
    }
}
