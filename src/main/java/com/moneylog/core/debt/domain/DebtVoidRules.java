package com.moneylog.core.debt.domain;

import com.moneylog.core.sk.ledger.VoidDecision;

public final class DebtVoidRules {

    public static final String DEBT_CLOSED = "DEBT_CLOSED";
    public static final String DEBT_HAS_SETTLEMENTS = "DEBT_HAS_SETTLEMENTS";

    private DebtVoidRules() {
    }

    public static VoidDecision forOrigin(Debt debt) {
        if (debt.closure() != null) {
            return VoidDecision.refuse(DEBT_CLOSED, "a dívida está encerrada (" + debt.closure() + ").");
        }
        if (debt.settled().isPositive()) {
            return VoidDecision.refuse(DEBT_HAS_SETTLEMENTS, "a dívida tem quitações ativas; estorne as quitações antes.");
        }
        return VoidDecision.allow();
    }

    public static VoidDecision forSettlement(Debt debt) {
        if (debt.closure() == DebtClosure.CANCELED) {
            return VoidDecision.refuse(DEBT_CLOSED, "a dívida foi perdoada; desfazer o perdão fica fora da Etapa 1.");
        }
        return VoidDecision.allow();
    }
}
