package com.moneylog.core.commitment.domain;

import java.util.Collection;

public final class FinancingPending {

    private FinancingPending() {
    }

    public static boolean exists(Collection<TrackedCommitment> commitments) {
        return commitments.stream().anyMatch(c -> c.kind() == CommitmentKind.LONG_TERM_PROJECT && c.financingPending().isPositive());
    }
}
