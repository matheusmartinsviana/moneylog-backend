package com.moneylog.core.finance.app.simulation;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.domain.PurchaseSimulation;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public interface SimulatePurchaseUseCase {

    PurchaseSimulation handle(SimulatePurchaseCommand command);

    record SimulatePurchaseCommand(UUID userId, Money amount, int installments, Integer interestRatePerMonthBps,
                                   Competence firstInstallmentMonth, List<UUID> cancelSubscriptionIds) {

        public SimulatePurchaseCommand {
            cancelSubscriptionIds = cancelSubscriptionIds == null ? List.of() : List.copyOf(cancelSubscriptionIds);
        }
    }
}
