package com.moneylog.core.debt.domain;

import com.moneylog.core.sk.ledger.VoidDecision;

/**
 * Guardas de estorno das movimentações de dívida (D1 §2.4, regra 1, S-36). Devolvem uma {@link VoidDecision}; quem
 * adapta para a porta {@code TransactionVoidPolicy} é o pacote de movimentação.
 */
public final class DebtVoidRules {

    public static final String DEBT_CLOSED = "DEBT_CLOSED";
    public static final String DEBT_HAS_SETTLEMENTS = "DEBT_HAS_SETTLEMENTS";

    private DebtVoidRules() {
    }

    /** Origem (ou registro sem movimentação): só sem quitação ativa e sem encerramento. */
    public static VoidDecision forOrigin(Debt debt) {
        if (debt.closure() != null) {
            return VoidDecision.refuse(DEBT_CLOSED, "a dívida está encerrada (" + debt.closure() + ").");
        }
        if (debt.settled().isPositive()) {
            return VoidDecision.refuse(DEBT_HAS_SETTLEMENTS, "a dívida tem quitações ativas; estorne as quitações antes.");
        }
        return VoidDecision.allow();
    }

    /** Quitação: só se a dívida não foi perdoada (o status é recalculado sozinho, porque é derivado). */
    public static VoidDecision forSettlement(Debt debt) {
        if (debt.closure() == DebtClosure.CANCELED) {
            return VoidDecision.refuse(DEBT_CLOSED, "a dívida foi perdoada; desfazer o perdão fica fora da Etapa 1.");
        }
        return VoidDecision.allow();
    }
}
