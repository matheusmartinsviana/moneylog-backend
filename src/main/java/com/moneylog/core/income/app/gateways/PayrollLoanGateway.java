package com.moneylog.core.income.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.income.app.PayrollLoanApplication;
import com.moneylog.core.income.app.PayrollLoanPlan;
import com.moneylog.core.income.app.PayrollLoanPlanLink;
import com.moneylog.core.sk.money.Money;

public interface PayrollLoanGateway {

    void savePlan(UUID userId, UUID recurrenceId, PayrollLoanPlan plan);

    Optional<PayrollLoanPlan> findPlan(UUID userId, UUID recurrenceId);

    List<PayrollLoanPlanLink> findAllPlans(UUID userId);

    List<PayrollLoanApplication> findAllApplications(UUID userId);

    void saveApplication(UUID userId, UUID transactionId, UUID debtId, Money applied, Money interest, Money principal);
}
