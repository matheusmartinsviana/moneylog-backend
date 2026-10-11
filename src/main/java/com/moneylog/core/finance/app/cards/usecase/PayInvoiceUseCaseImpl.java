package com.moneylog.core.finance.app.cards.usecase;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.app.gateways.InvoiceLookupGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.cards.InvoiceDetails;
import com.moneylog.core.finance.app.cards.InvoiceViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.CanonicalCommandHash;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

public class PayInvoiceUseCaseImpl implements PayInvoiceUseCase {

    public static final Duration IDEMPOTENCY_VALIDITY = Duration.ofHours(24);

    private final InvoiceLookupGateway lookup;
    private final CardGateway cards;
    private final AccountGateway accounts;
    private final TransactionGateway transactions;
    private final LedgerWriteGateway ledger;
    private final FinancialRecordsGateway records;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public PayInvoiceUseCaseImpl(InvoiceLookupGateway lookup, CardGateway cards, AccountGateway accounts, TransactionGateway transactions,
                                 LedgerWriteGateway ledger, FinancialRecordsGateway records, IdempotencyGateway idempotency,
                                 IdGenerator ids, TimeProvider time) {
        this.lookup = lookup;
        this.cards = cards;
        this.accounts = accounts;
        this.transactions = transactions;
        this.ledger = ledger;
        this.records = records;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public InvoiceDetails handle(PayInvoiceCommand command, UUID idempotencyKey) {
        UUID paymentId = ids.next();
        if (idempotencyKey != null) {
            Claim existing = claimOrFind(command, idempotencyKey, paymentId);
            if (existing != null) {
                return replay(command, existing);
            }
        }
        UserDay day = new UserDay(command.userId(), time.today());
        InvoiceResolver.Resolved resolved = InvoiceResolver.resolve(command.userId(), command.invoiceId(), lookup,
                records.load(command.userId()), day.today());
        cards.lockForUpdate(command.userId(), resolved.card().id());
        accounts.lockForUpdate(command.userId(), command.accountId());
        cards.discardReadState();
        CreditCard card = cards.findById(command.userId(), resolved.card().id()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(resolved.card().id()));
        Account account = accounts.findById(command.userId(), command.accountId()).map(Persisted::value)
                .orElseThrow(() -> new AccountNotFoundException(command.accountId()));
        InvoiceChain chain = CardInvoices.chainFor(card, transactions.findAllOfCard(command.userId(), card.id()));
        Transaction payment = plan(header(paymentId, command.userId(), command.date(), card, chain, resolved.closingDate()),
                command.amount(), account, card, resolved.closingDate(), chain, day.today());
        transactions.insert(payment);
        ledger.append(command.userId(), payment.entries());
        return detailsOf(command.userId(), card.id(), resolved.closingDate(), day.today());
    }

    static Transaction plan(TransactionHeader header, Money amount, Account account, CreditCard card, LocalDate closingDate,
                            InvoiceChain chain, LocalDate today) {
        return CardTransactions.invoicePayment(header, amount, account, card, closingDate, chain, today);
    }

    static TransactionHeader header(UUID id, UUID userId, LocalDate date, CreditCard card, InvoiceChain chain, LocalDate closingDate) {
        String description = "Fatura " + card.name() + " " + chain.calendar().periodClosingIn(java.time.YearMonth.from(closingDate))
                .referenceMonth();
        return TransactionHeader.of(id, userId, date, description);
    }

    private Claim claimOrFind(PayInvoiceCommand command, UUID key, UUID resourceId) {
        String hash = CanonicalCommandHash.ofInvoicePayment(command.invoiceId(), command.accountId(), command.amount(), command.date());
        Instant now = time.now();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (idempotency.claim(command.userId(), IDEMPOTENCY_SCOPE, key, hash, resourceId, now, now.minus(IDEMPOTENCY_VALIDITY))) {
                return null;
            }
            var found = idempotency.find(command.userId(), IDEMPOTENCY_SCOPE, key);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new IllegalStateException("não foi possível reivindicar nem ler a chave de idempotência");
    }

    private InvoiceDetails replay(PayInvoiceCommand command, Claim existing) {
        String hash = CanonicalCommandHash.ofInvoicePayment(command.invoiceId(), command.accountId(), command.amount(), command.date());
        if (!existing.requestHash().equals(hash)) {
            throw new IdempotencyKeyReusedException();
        }
        Transaction payment = transactions.findById(command.userId(), existing.resourceId()).map(Persisted::value)
                .orElseThrow(() -> new TransactionNotFoundException(existing.resourceId()));
        return detailsOf(command.userId(), payment.cardId(), payment.invoiceClosingDate(), time.today());
    }

    private InvoiceDetails detailsOf(UUID userId, UUID cardId, LocalDate closingDate, LocalDate today) {
        FinancialRecords loaded = records.load(userId);
        CreditCard card = loaded.cards().stream().filter(c -> c.id().equals(cardId)).findFirst()
                .orElseThrow(() -> new CardNotFoundException(cardId));
        return InvoiceViews.details(cardId, CardViews.chainOf(card, loaded).invoice(closingDate), InvoiceViews.index(loaded), today);
    }

    private record UserDay(UUID userId, LocalDate today) {
    }
}
