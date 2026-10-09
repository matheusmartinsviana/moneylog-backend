package com.moneylog.infra.ledger.gateways;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.infra.ledger.persistence.LedgerPostingEntity;

/** {@code LedgerEntry} ⇄ {@code ledger_postings} (domínio ⇄ entity). */
public final class LedgerRepositoryMapper {

    private LedgerRepositoryMapper() {
    }

    public static LedgerPostingEntity toEntity(UUID id, UUID userId, LedgerEntry entry, Instant now) {
        PostingSource source = entry.source();
        Posting posting = entry.posting();
        return new LedgerPostingEntity(id, userId, source.type(), source.sourceId(), source.transactionId(),
                source.installmentId(), posting.bookType(), posting.book().id(), posting.competence().toString(),
                posting.effectiveDate(), posting.amount().cents(), posting.amount().currency().value(), entry.active(), now);
    }

    public static LedgerEntry toDomain(LedgerPostingEntity entity) {
        PostingSourceType type = entity.getSourceType();
        PostingSource source = new PostingSource(type, entity.getSourceId(), entity.getInstallmentId());
        Posting posting = new Posting(new BookRef(entity.getBookType(), entity.getBookId()),
                Money.ofCents(entity.getAmountCents(), CurrencyCode.of(entity.getCurrency())), entity.getEffectiveDate(),
                Competence.parse(entity.getCompetence().strip()));
        return new LedgerEntry(source, posting, entity.isActive());
    }
}
