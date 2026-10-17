package com.moneylog.core.purchasegroup.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupInvalidException;
import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupMemberInvalidException;
import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupNotFoundException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public final class PurchaseGroup {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int MAX_MEMBERS = 10;

    private final UUID id;
    private final UUID userId;
    private String name;
    private boolean interest;
    private final Money declaredTotal;
    private final List<UUID> members;

    private PurchaseGroup(UUID id, UUID userId, String name, boolean interest, Money declaredTotal, List<UUID> members) {
        if (id == null || userId == null) {
            throw new PurchaseGroupInvalidException("id e dono são obrigatórios.");
        }
        this.id = id;
        this.userId = userId;
        this.name = validName(name);
        this.interest = interest;
        this.declaredTotal = validDeclaredTotal(declaredTotal);
        List<UUID> unique = members == null ? List.of() : members.stream().distinct().toList();
        if (members != null && unique.size() != members.size()) {
            throw new PurchaseGroupInvalidException("a mesma compra não entra duas vezes no grupo.");
        }
        if (unique.size() > MAX_MEMBERS) {
            throw new PurchaseGroupInvalidException("no máximo " + MAX_MEMBERS + " compras por grupo.");
        }
        this.members = new ArrayList<>(unique);
    }

    public static PurchaseGroup create(UUID id, UUID userId, String name, boolean interest, Money declaredTotal, List<UUID> members) {
        if (members == null || members.isEmpty()) {
            throw new PurchaseGroupInvalidException("o grupo precisa de ao menos uma compra.");
        }
        return new PurchaseGroup(id, userId, name, interest, declaredTotal, members);
    }

    public static PurchaseGroup restore(UUID id, UUID userId, String name, boolean interest, Money declaredTotal, List<UUID> members) {
        return new PurchaseGroup(id, userId, name, interest, declaredTotal, members);
    }

    public void revise(String newName, boolean newInterest) {
        this.name = validName(newName);
        this.interest = newInterest;
    }

    public void addMember(UUID transactionId) {
        if (transactionId == null) {
            throw new PurchaseGroupMemberInvalidException("a compra é obrigatória.");
        }
        if (members.contains(transactionId)) {
            throw new PurchaseGroupMemberInvalidException("a compra já está neste grupo.");
        }
        if (members.size() >= MAX_MEMBERS) {
            throw new PurchaseGroupMemberInvalidException("o grupo já tem " + MAX_MEMBERS + " compras.");
        }
        members.add(transactionId);
    }

    public void removeMember(UUID transactionId) {
        if (!members.remove(transactionId)) {
            throw new PurchaseGroupMemberInvalidException("a compra não está neste grupo.");
        }
    }

    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new PurchaseGroupNotFoundException(id);
        }
    }

    private static String validName(String value) {
        String valid = value == null ? "" : value.strip();
        if (valid.isEmpty() || valid.length() > NAME_MAX_LENGTH) {
            throw new PurchaseGroupInvalidException("o nome tem de 1 a " + NAME_MAX_LENGTH + " caracteres.");
        }
        return valid;
    }

    private static Money validDeclaredTotal(Money value) {
        if (value == null) {
            return null;
        }
        if (value.isNegative() || !CurrencyCode.BRL.equals(value.currency())) {
            throw new PurchaseGroupInvalidException("o total informado é em BRL e não negativo.");
        }
        return AmountLimit.ensureWithinLimit(value, "declaredTotal");
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String name() {
        return name;
    }

    public boolean interest() {
        return interest;
    }

    public Money declaredTotal() {
        return declaredTotal;
    }

    public List<UUID> members() {
        return List.copyOf(members);
    }
}
