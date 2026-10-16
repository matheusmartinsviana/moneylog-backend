package com.moneylog.infra.purchasegroup.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class PurchaseGroupMemberId implements Serializable {

    private UUID groupId;
    private UUID transactionId;

    protected PurchaseGroupMemberId() {
    }

    public PurchaseGroupMemberId(UUID groupId, UUID transactionId) {
        this.groupId = groupId;
        this.transactionId = transactionId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PurchaseGroupMemberId other && Objects.equals(groupId, other.groupId)
                && Objects.equals(transactionId, other.transactionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, transactionId);
    }
}
