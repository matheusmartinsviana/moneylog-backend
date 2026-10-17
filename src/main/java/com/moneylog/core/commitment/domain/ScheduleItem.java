package com.moneylog.core.commitment.domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.commitment.exception.CommitmentExceptions.InvalidCommitmentException;
import com.moneylog.core.commitment.exception.CommitmentExceptions.SharesInvalidException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.sk.time.FinancialDates;

public record ScheduleItem(int position, String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid, LocalDate paidOn,
                           List<ItemShare> shares, UUID coBuyerPersonId, Provenance provenance) {

    public static final int LABEL_MAX_LENGTH = 100;
    public static final int MAX_SHARES = 10;

    public ScheduleItem {
        Objects.requireNonNull(type, "type");
        if (position < 1) {
            throw new InvalidCommitmentException("a posição do item começa em 1.");
        }
        if (label == null || label.isBlank() || label.strip().length() > LABEL_MAX_LENGTH) {
            throw new InvalidCommitmentException("o rótulo do item é obrigatório e tem até " + LABEL_MAX_LENGTH + " caracteres.");
        }
        label = label.strip();
        if (amount == null || !amount.isPositive()) {
            throw new InvalidCommitmentException("o valor do item precisa ser maior que zero.");
        }
        AmountLimit.ensureWithinLimit(amount, "amount");
        FinancialDates.requireInWindow(dueDate, "dueDate");
        FinancialDates.requireInWindow(paidOn, "paidOn");
        if (paidOn != null && !paid) {
            throw new InvalidCommitmentException("item com data de pagamento precisa estar pago.");
        }
        shares = shares == null ? List.of() : List.copyOf(shares);
        provenance = Provenance.orDefault(provenance);
        validateShares(amount, shares, coBuyerPersonId);
    }

    public ScheduleItem(int position, String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid, LocalDate paidOn) {
        this(position, label, type, dueDate, amount, paid, paidOn, List.of(), null, null);
    }

    public ScheduleItem(int position, String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid, LocalDate paidOn,
                        List<ItemShare> shares, UUID coBuyerPersonId) {
        this(position, label, type, dueDate, amount, paid, paidOn, shares, coBuyerPersonId, null);
    }

    private static void validateShares(Money amount, List<ItemShare> shares, UUID coBuyerPersonId) {
        if (shares.isEmpty()) {
            if (coBuyerPersonId != null) {
                throw new SharesInvalidException("o co-comprador exige as cotas do item.");
            }
            return;
        }
        if (shares.size() > MAX_SHARES) {
            throw new SharesInvalidException("no máximo " + MAX_SHARES + " cotas por item.");
        }
        Set<UUID> seen = new HashSet<>();
        boolean ownerSeen = false;
        for (ItemShare share : shares) {
            if (share == null || share.amount() == null || !share.amount().isPositive()
                    || !CurrencyCode.BRL.equals(share.amount().currency())) {
                throw new SharesInvalidException("cada cota precisa ser maior que zero e em BRL.");
            }
            boolean duplicated = share.isOwner() ? ownerSeen : !seen.add(share.personId());
            if (duplicated) {
                throw new SharesInvalidException("a pessoa não pode repetir nas cotas do item.");
            }
            ownerSeen |= share.isOwner();
        }
        Money sum = Money.sum(shares.stream().map(ItemShare::amount).toList(), CurrencyCode.BRL);
        if (!sum.equals(amount)) {
            throw new SharesInvalidException("a soma das cotas (" + sum.toPlainString() + ") precisa fechar com o valor do item ("
                    + amount.toPlainString() + ").");
        }
        if (coBuyerPersonId != null && !seen.contains(coBuyerPersonId)) {
            throw new SharesInvalidException("o co-comprador precisa estar nas cotas do item.");
        }
    }

    public boolean isPaid() {
        return paid;
    }

    public boolean sharesDefined() {
        return !shares.isEmpty();
    }

    public List<ItemShare> effectiveShares() {
        return shares.isEmpty() ? List.of(new ItemShare(null, amount)) : shares;
    }

    public Money ownerShare() {
        return effectiveShares().stream().filter(ItemShare::isOwner).map(ItemShare::amount).findFirst()
                .orElse(Money.zero(CurrencyCode.BRL));
    }

    public Set<UUID> personIds() {
        Set<UUID> ids = new HashSet<>();
        shares.stream().map(ItemShare::personId).filter(Objects::nonNull).forEach(ids::add);
        return ids;
    }

    public ScheduleItem markPaid(LocalDate date) {
        return new ScheduleItem(position, label, type, dueDate, amount, true, date, shares, coBuyerPersonId, provenance);
    }

    public ScheduleItem withShares(List<ItemShare> newShares, UUID newCoBuyerPersonId) {
        if (paid) {
            throw new InvalidCommitmentException("a cota do item pago fica como foi registrada no pagamento.");
        }
        return new ScheduleItem(position, label, type, dueDate, amount, paid, paidOn, newShares, newCoBuyerPersonId, provenance);
    }
}
