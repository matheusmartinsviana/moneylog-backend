package com.moneylog.core.finance.app.simulation;

import com.moneylog.core.sk.exception.BusinessRuleException;

public class SimulationInvalidException extends BusinessRuleException {

    public SimulationInvalidException(String reason) {
        super("SIMULATION_INVALID", "Simulação inválida: " + reason);
    }
}
