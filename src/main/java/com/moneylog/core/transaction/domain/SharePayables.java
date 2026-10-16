package com.moneylog.core.transaction.domain;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.money.Money;

public final class SharePayables {

    private SharePayables() {
    }

    public static Map<UUID, Money> owedByPerson(Transaction transaction) {
        Map<UUID, Money> owed = new TreeMap<>();
        for (LedgerEntry entry : transaction.entries()) {
            if (entry.active() && entry.posting().bookType() == BookType.RECEIVABLE && entry.source().installmentId() != null) {
                owed.merge(entry.posting().book().id(), entry.posting().amount(), Money::add);
            }
        }
        return owed;
    }

    public static Map<UUID, Money> refundedByPerson(UUID originalId, Collection<Transaction> refunds) {
        Map<UUID, Money> refunded = new TreeMap<>();
        for (Transaction refund : refunds) {
            if (refund.type() != TransactionType.REFUND || !refund.isActive() || !originalId.equals(refund.refundOfId())) {
                continue;
            }
            for (LedgerEntry entry : refund.entries()) {
                if (entry.active() && entry.posting().bookType() == BookType.RECEIVABLE) {
                    refunded.merge(entry.posting().book().id(), entry.posting().amount().negate(), Money::add);
                }
            }
        }
        return refunded;
    }

    public static Map<UUID, Money> owedNet(Transaction original, Collection<Transaction> refunds) {
        Map<UUID, Money> owed = owedByPerson(original);
        refundedByPerson(original.id(), refunds).forEach((personId, amount) -> owed.computeIfPresent(personId, (id, value) -> value.subtract(amount)));
        return owed;
    }

    public static InstallmentSplit refundSplit(Transaction original, Money amount, Money alreadyRefunded,
                                               Map<UUID, Money> thirdsAlreadyRefunded) {
        Money zero = Money.zero(amount.currency());
        Map<UUID, Money> owed = owedByPerson(original);
        long charged = original.chargedAmount().cents();
        long cumulative = alreadyRefunded.add(amount).cents();
        long remainingForMine = amount.cents();
        List<PersonAmount> thirds = new ArrayList<>();
        for (Map.Entry<UUID, Money> entry : owed.entrySet()) {
            long total = entry.getValue().cents();
            long already = thirdsAlreadyRefunded.getOrDefault(entry.getKey(), zero).cents();
            long target = BigInteger.valueOf(total).multiply(BigInteger.valueOf(cumulative))
                    .divide(BigInteger.valueOf(charged)).longValueExact();
            long part = Math.max(0, Math.min(target - already, Math.min(total - already, remainingForMine)));
            remainingForMine -= part;
            if (part > 0) {
                thirds.add(new PersonAmount(entry.getKey(), Money.ofCents(part, amount.currency())));
            }
        }
        return new InstallmentSplit(Money.ofCents(remainingForMine, amount.currency()), thirds);
    }

    public static List<Posting> refundPostings(Transaction original, Money amount, Money alreadyRefunded,
                                               Map<UUID, Money> thirdsAlreadyRefunded, BookRef categoryBook, BookRef counterpartBook,
                                               LocalDate on) {
        List<Posting> postings = new ArrayList<>();
        postings.add(new Posting(counterpartBook, amount, on));
        if (original.purchaseShares() == null) {
            postings.add(new Posting(categoryBook, amount.negate(), on));
            return postings;
        }
        InstallmentSplit split = refundSplit(original, amount, alreadyRefunded, thirdsAlreadyRefunded);
        if (split.mine().isPositive()) {
            postings.add(new Posting(categoryBook, split.mine().negate(), on));
        }
        for (PersonAmount part : split.byPerson()) {
            postings.add(new Posting(BookRef.personReceivable(part.personId()), part.amount().negate(), on));
        }
        return postings;
    }

    public static Map<UUID, Money> alreadyConverted(UUID transactionId, List<LedgerEntry> entries) {
        PostingSource source = PostingSource.sharePayable(transactionId);
        Map<UUID, Money> converted = new TreeMap<>();
        for (LedgerEntry entry : entries) {
            if (entry.active() && entry.source().equals(source) && entry.posting().bookType() == BookType.PAYABLE) {
                converted.merge(entry.posting().book().id(), entry.posting().amount().negate(), Money::add);
            }
        }
        return converted;
    }

    public static List<PersonAmount> excess(Map<UUID, Money> received, Map<UUID, Money> owedAfter, Map<UUID, Money> converted) {
        List<PersonAmount> excess = new ArrayList<>();
        for (Map.Entry<UUID, Money> entry : new TreeMap<>(received).entrySet()) {
            Money zero = Money.zero(entry.getValue().currency());
            Money covered = owedAfter.getOrDefault(entry.getKey(), zero).add(converted.getOrDefault(entry.getKey(), zero));
            if (entry.getValue().isGreaterThan(covered)) {
                excess.add(new PersonAmount(entry.getKey(), entry.getValue().subtract(covered)));
            }
        }
        return excess;
    }

    public static List<LedgerEntry> entries(UUID transactionId, List<PersonAmount> excess, LocalDate on) {
        if (excess.isEmpty()) {
            return List.of();
        }
        List<Posting> postings = new ArrayList<>();
        for (PersonAmount part : excess) {
            postings.add(new Posting(BookRef.personReceivable(part.personId()), part.amount(), on));
            postings.add(new Posting(BookRef.payable(part.personId()), part.amount().negate(), on));
        }
        return LedgerEntry.activeGroup(PostingSource.sharePayable(transactionId), postings);
    }

    public static String note(List<PersonAmount> excess) {
        StringBuilder note = new StringBuilder("excedente recebido virou a pagar à pessoa:");
        excess.forEach(part -> note.append(' ').append(part.personId()).append('=').append(part.amount().toPlainString()).append(';'));
        return note.toString();
    }
}
