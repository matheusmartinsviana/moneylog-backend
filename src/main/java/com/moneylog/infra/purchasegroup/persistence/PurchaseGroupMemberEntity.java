package com.moneylog.infra.purchasegroup.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "purchase_group_members")
@IdClass(PurchaseGroupMemberId.class)
public class PurchaseGroupMemberEntity {

    @Id
    @Column(name = "group_id", nullable = false, updatable = false)
    private UUID groupId;

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PurchaseGroupMemberEntity() {
    }

    public PurchaseGroupMemberEntity(UUID groupId, UUID transactionId, UUID userId, int position, Instant createdAt) {
        this.groupId = groupId;
        this.transactionId = transactionId;
        this.userId = userId;
        this.position = position;
        this.createdAt = createdAt;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getPosition() {
        return position;
    }
}
