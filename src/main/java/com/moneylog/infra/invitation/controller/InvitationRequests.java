package com.moneylog.infra.invitation.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class InvitationRequests {

    private InvitationRequests() {
    }

    public record InvitationInput(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record InvitationPreviewInput(@NotBlank @Size(max = 200) String token) {

        @Override
        public String toString() {
            return "InvitationPreviewInput[]";
        }
    }

    public record InvitationAcceptInput(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 200) String password) {

        @Override
        public String toString() {
            return "InvitationAcceptInput[]";
        }
    }
}
