package com.moneylog.core.commitment.app;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.commitment.domain.PersonTotals;
import com.moneylog.core.commitment.domain.TrackedCommitment;

public record CommitmentByPersonView(TrackedCommitment commitment, List<PersonTotals> people, List<Integer> undefinedItems,
                                     Map<UUID, String> personNames) {
}
