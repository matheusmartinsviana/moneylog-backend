package com.moneylog.infra.auth.controller;

public record AuthStatusResponse(boolean passwordSetupRequired) {
}
