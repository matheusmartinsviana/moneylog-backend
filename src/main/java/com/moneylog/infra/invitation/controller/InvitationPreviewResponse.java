package com.moneylog.infra.invitation.controller;

import java.time.Instant;

public record InvitationPreviewResponse(String email, Instant expiresAt) {
}
