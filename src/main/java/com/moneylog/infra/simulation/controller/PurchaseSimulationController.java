package com.moneylog.infra.simulation.controller;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.simulation.SimulatePurchaseUseCase;
import com.moneylog.infra.security.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/simulations")
public class PurchaseSimulationController {

    private final SimulatePurchaseUseCase simulate;

    public PurchaseSimulationController(SimulatePurchaseUseCase simulate) {
        this.simulate = simulate;
    }

    @PostMapping("/purchase")
    public PurchaseSimulationResponse purchase(@CurrentUser UUID userId, @Valid @RequestBody PurchaseSimulationRequest body) {
        return PurchaseSimulationResponse.of(simulate.handle(body.toCommand(userId)));
    }
}
