package com.moneylog.infra.simulation.controller;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.simulation.SimulatePurchaseUseCase.SimulatePurchaseCommand;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.web.QueryMonths;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PurchaseSimulationRequest(@NotNull @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String amount,
                                        @NotNull Integer installments, Integer interestRatePerMonthBps,
                                        @NotNull @Pattern(regexp = "^\\d{4}-\\d{2}$") String firstInstallmentMonth,
                                        @Size(max = 50) List<@NotNull UUID> cancelSubscriptionIds) {

    public static final String SIMULATION_INVALID = "SIMULATION_INVALID";

    public SimulatePurchaseCommand toCommand(UUID userId) {
        return new SimulatePurchaseCommand(userId, Money.of(amount, CurrencyCode.BRL), installments, interestRatePerMonthBps,
                QueryMonths.parse(firstInstallmentMonth, SIMULATION_INVALID, "firstInstallmentMonth"), cancelSubscriptionIds);
    }
}
