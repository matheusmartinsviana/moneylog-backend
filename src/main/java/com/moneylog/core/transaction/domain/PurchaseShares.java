package com.moneylog.core.transaction.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharesInvalidException;

public final class PurchaseShares {

    public static final int MAX_LINES = 20;
    public static final int FULL_BPS = 10_000;

    private final SplitMode mode;
    private final List<ShareLine> lines;

    private PurchaseShares(SplitMode mode, List<ShareLine> lines) {
        this.mode = mode;
        this.lines = List.copyOf(lines);
    }

    public static PurchaseShares create(SplitMode mode, List<ShareLine> lines, Money baseInstallment) {
        PurchaseShares shares = restore(mode, lines);
        if (shares == null) {
            return null;
        }
        shares.requireFits(Objects.requireNonNull(baseInstallment, "baseInstallment"));
        return shares;
    }

    public static PurchaseShares restore(SplitMode mode, List<ShareLine> lines) {
        List<ShareLine> given = lines == null ? List.of() : lines;
        if (given.isEmpty()) {
            return null;
        }
        if (mode == null) {
            throw new SharesInvalidException("o modo de divisão é obrigatório quando há partes.");
        }
        if (given.size() > MAX_LINES) {
            throw new SharesInvalidException("no máximo " + MAX_LINES + " partes.");
        }
        Set<UUID> seen = new HashSet<>();
        boolean ownerSeen = false;
        for (ShareLine line : given) {
            if (line == null) {
                throw new SharesInvalidException("parte vazia.");
            }
            if (line.isOwner()) {
                if (ownerSeen) {
                    throw new SharesInvalidException("o dono aparece mais de uma vez.");
                }
                ownerSeen = true;
            } else if (!seen.add(line.personId())) {
                throw new SharesInvalidException("a pessoa " + line.personId() + " aparece mais de uma vez.");
            }
            requireShape(mode, line);
        }
        if (given.stream().allMatch(ShareLine::isOwner)) {
            return null;
        }
        PurchaseShares shares = new PurchaseShares(mode, given);
        if (mode == SplitMode.BPS && shares.lines.stream().mapToInt(ShareLine::bps).sum() != FULL_BPS) {
            throw new SharesInvalidException("os pontos-base das partes precisam somar " + FULL_BPS + ".");
        }
        return shares;
    }

    private static void requireShape(SplitMode mode, ShareLine line) {
        switch (mode) {
            case BPS -> {
                if (line.bps() == null || line.bps() < 1 || line.bps() > FULL_BPS || line.amount() != null) {
                    throw new SharesInvalidException("no modo BPS cada parte informa só bps entre 1 e " + FULL_BPS + ".");
                }
            }
            case AMOUNT -> {
                if (line.amount() == null || !line.amount().isPositive() || line.bps() != null) {
                    throw new SharesInvalidException("no modo AMOUNT cada parte informa só um valor positivo.");
                }
            }
            case EQUAL -> {
                if (line.bps() != null || line.amount() != null) {
                    throw new SharesInvalidException("no modo EQUAL as partes não informam bps nem valor.");
                }
            }
        }
    }

    private void requireFits(Money baseInstallment) {
        if (mode != SplitMode.AMOUNT) {
            return;
        }
        Money total = Money.sum(lines.stream().map(ShareLine::amount).toList(), baseInstallment.currency());
        if (!total.equals(baseInstallment)) {
            throw new SharesInvalidException("os valores das partes precisam somar a parcela (" + baseInstallment.toPlainString() + ").");
        }
    }

    public InstallmentSplit split(Money installment) {
        long cents = installment.cents();
        List<PersonAmount> thirds = new ArrayList<>();
        long thirdsTotal = 0;
        long participants = lines.size();
        for (ShareLine line : lines) {
            if (line.isOwner()) {
                continue;
            }
            long part = switch (mode) {
                case BPS -> Math.multiplyExact(cents, (long) line.bps()) / FULL_BPS;
                case EQUAL -> cents / participants;
                case AMOUNT -> line.amount().cents();
            };
            if (part < 0 || thirdsTotal + part > cents) {
                throw new SharesInvalidException("as partes de terceiros excedem a parcela.");
            }
            thirdsTotal += part;
            if (part > 0) {
                thirds.add(new PersonAmount(line.personId(), Money.ofCents(part, installment.currency())));
            }
        }
        return new InstallmentSplit(Money.ofCents(cents - thirdsTotal, installment.currency()), thirds);
    }

    public SplitMode mode() {
        return mode;
    }

    public List<ShareLine> lines() {
        return lines;
    }

    public List<UUID> personIds() {
        return lines.stream().map(ShareLine::personId).filter(Objects::nonNull).toList();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PurchaseShares other && mode == other.mode && lines.equals(other.lines);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, lines);
    }
}
