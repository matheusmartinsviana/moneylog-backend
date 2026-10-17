package com.moneylog.core.transaction.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.card.exception.CardExceptions.InvoiceChainInconsistentException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.ledger.Postings;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.exception.TransactionExceptions.IncomeCompetenceInvalidException;
import com.moneylog.core.transaction.exception.TransactionExceptions.NonPositiveAmountException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionAlreadyVoidedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionFieldRequiredException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNatureInvalidException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionStateInconsistentException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidReasonRequiredException;

public final class Transaction {

    public static final int VOID_REASON_MAX_LENGTH = 500;

    private final UUID id;
    private final UUID userId;
    private final TransactionType type;
    private final Money amount;
    private final LocalDate date;
    private final String description;
    private final String notes;
    private final TransactionOrigin origin;
    private final PaymentMethod paymentMethod;
    private final UUID accountId;
    private final UUID counterpartAccountId;
    private final UUID cardId;
    private final UUID categoryId;
    private final UUID debtId;
    private final UUID refundOfId;
    private final InvestmentDirection investmentDirection;
    private final AdjustmentDirection adjustmentDirection;
    private final AdjustmentKind adjustmentKind;
    private final String reason;
    private LocalDate invoiceClosingDate;
    private final boolean lateEntry;
    private final Competence incomeCompetence;
    private final PurchaseShares purchaseShares;
    private final TransactionNature nature;
    private ForeignCharge foreignCharge;
    private String lastDigits;
    private final List<TransactionInstallment> installments;
    private List<LedgerEntry> entries;
    private TransactionStatus status;
    private String voidReason;
    private Instant voidedAt;
    private long version;

    private Transaction(Builder b) {
        TransactionHeader header = Objects.requireNonNull(b.header, "header");
        this.id = header.id();
        this.userId = header.userId();
        this.date = header.date();
        this.description = header.description();
        this.notes = header.notes();
        this.origin = header.origin();
        this.type = required(b.type, "type");
        this.amount = requirePositive(b.amount);
        this.paymentMethod = b.paymentMethod;
        this.accountId = b.accountId;
        this.counterpartAccountId = b.counterpartAccountId;
        this.cardId = b.cardId;
        this.categoryId = b.categoryId;
        this.debtId = b.debtId;
        this.refundOfId = b.refundOfId;
        this.investmentDirection = b.investmentDirection;
        this.adjustmentDirection = b.adjustmentDirection;
        this.adjustmentKind = b.adjustmentKind;
        this.reason = b.reason;
        this.invoiceClosingDate = b.invoiceClosingDate;
        this.lateEntry = b.lateEntry;
        if (b.incomeCompetence != null && b.type != TransactionType.INCOME) {
            throw new IncomeCompetenceInvalidException("só a receita pode pertencer a outro mês.");
        }
        this.incomeCompetence = b.incomeCompetence;
        if (b.purchaseShares != null && b.type != TransactionType.CARD_PURCHASE) {
            throw new TransactionStateInconsistentException("só a compra no cartão tem rateio.");
        }
        this.purchaseShares = b.purchaseShares;
        if (b.nature != null && b.type != TransactionType.EXPENSE) {
            throw new TransactionNatureInvalidException("só a despesa tem natureza.");
        }
        this.nature = b.type == TransactionType.EXPENSE ? TransactionNature.forExpense(b.nature) : null;
        this.installments = new ArrayList<>(b.installments);
        this.entries = List.copyOf(b.entries);
        if (entries.isEmpty()) {
            throw new TransactionFieldRequiredException("postings");
        }
        Postings.assertBalanced(postingsOf(entries));
        Competence expected = incomeCompetence == null ? Competence.from(date) : incomeCompetence;
        for (LedgerEntry entry : entries) {
            if (entry.active() && entry.posting().bookType() == BookType.INCOME && !entry.posting().competence().equals(expected)) {
                throw new TransactionStateInconsistentException("a competência da receita (" + entry.posting().competence()
                        + ") não é a declarada (" + expected + ").");
            }
        }
        this.status = TransactionStatus.ACTIVE;
        this.version = 0L;
    }

    static Builder builder(TransactionHeader header, TransactionType type, Money amount) {
        return new Builder(header, type, amount);
    }

    public static Transaction restore(TransactionState state, List<LedgerEntry> entries) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(entries, "entries");
        Transaction transaction;
        try {
            Builder builder = new Builder(new TransactionHeader(state.id(), state.userId(), state.date(), state.description(),
                    state.notes(), state.origin()), state.type(), state.amount())
                    .paymentMethod(state.paymentMethod()).account(state.accountId())
                    .counterpartAccount(state.counterpartAccountId()).card(state.cardId()).category(state.categoryId())
                    .debt(state.debtId()).refundOf(state.refundOfId()).investmentDirection(state.investmentDirection())
                    .adjustment(state.adjustmentDirection(), state.adjustmentKind(), state.reason())
                    .invoice(state.invoiceClosingDate(), state.lateEntry()).installments(state.installments())
                    .incomeCompetence(state.incomeCompetence()).purchaseShares(state.purchaseShares()).nature(state.nature())
                    .entries(entries);
            transaction = new Transaction(builder);
        } catch (DomainException e) {
            throw new TransactionStateInconsistentException(e.getMessage(), e);
        }
        transaction.status = state.status();
        transaction.voidReason = state.voidReason();
        transaction.voidedAt = state.voidedAt();
        transaction.version = state.version();
        transaction.ensureConsistentState();
        return transaction;
    }

    public TransactionState state() {
        return new TransactionState(id, userId, type, amount, date, description, notes, origin, paymentMethod, accountId,
                counterpartAccountId, cardId, categoryId, debtId, refundOfId, investmentDirection, adjustmentDirection,
                adjustmentKind, reason, invoiceClosingDate, lateEntry, List.copyOf(installments), status, voidReason, voidedAt,
                version, incomeCompetence, purchaseShares, nature);
    }

    private void ensureConsistentState() {
        if (status == null || version < 0) {
            throw inconsistent("status e versão (≥ 0) são obrigatórios.");
        }
        for (LedgerEntry entry : entries) {
            if (entry.source().type() != PostingSourceType.TRANSACTION || !id.equals(entry.source().sourceId())) {
                throw inconsistent("lançamento de outra origem: " + entry.source() + ".");
            }
        }
        Map<UUID, Money> sumByGroup = new HashMap<>();
        for (LedgerEntry entry : entries) {
            sumByGroup.merge(entry.source().installmentId() == null ? id : entry.source().installmentId(),
                    entry.posting().amount(), Money::add);
        }
        sumByGroup.forEach((group, sum) -> {
            if (!sum.isZero()) {
                throw inconsistent("os lançamentos do grupo " + group + " somam " + sum + " (deveria ser zero).");
            }
        });
        boolean cardPurchase = type == TransactionType.CARD_PURCHASE;
        if (!cardPurchase && !installments.isEmpty()) {
            throw inconsistent("só a compra no cartão tem parcelas.");
        }
        for (TransactionInstallment installment : installments) {
            if (!id.equals(installment.transactionId())) {
                throw inconsistent("a parcela " + installment.id() + " é de outra movimentação.");
            }
        }
        if (status == TransactionStatus.VOIDED) {
            if (voidedAt == null || voidReason == null || voidReason.isBlank()) {
                throw inconsistent("movimentação estornada sem voidedAt ou sem motivo.");
            }
            if (entries.stream().anyMatch(LedgerEntry::active)) {
                throw inconsistent("movimentação estornada com lançamento ativo.");
            }
            if (installments.stream().anyMatch(i -> !i.isCanceled())) {
                throw inconsistent("movimentação estornada com parcela não cancelada.");
            }
            return;
        }
        if (voidedAt != null || voidReason != null) {
            throw inconsistent("movimentação ativa com dados de estorno.");
        }
        if (!cardPurchase && entries.stream().anyMatch(e -> !e.active())) {
            throw inconsistent("movimentação ativa com lançamento inativo.");
        }
        for (LedgerEntry entry : entries) {
            UUID installmentId = entry.source().installmentId();
            if (cardPurchase && installmentId == null) {
                throw inconsistent("lançamento da compra no cartão sem parcela.");
            }
            if (installmentId != null) {
                TransactionInstallment installment = installments.stream().filter(i -> i.id().equals(installmentId))
                        .findFirst().orElseThrow(() -> inconsistent("lançamento da parcela inexistente " + installmentId + "."));
                if (entry.active() == installment.isCanceled()) {
                    throw inconsistent("o lançamento da parcela " + installmentId + " "
                            + (entry.active() ? "está ativo" : "está inativo") + " mas a parcela "
                            + (installment.isCanceled() ? "está cancelada" : "não está cancelada") + ".");
                }
            }
        }
    }

    private static TransactionStateInconsistentException inconsistent(String reason) {
        return new TransactionStateInconsistentException(reason);
    }

    String prepareVoid(String reason, Instant now, TransactionVoidPolicy policy) {
        if (status == TransactionStatus.VOIDED) {
            throw new TransactionAlreadyVoidedException();
        }
        String validReason = TransactionHeader.optionalText(reason, "voidReason", VOID_REASON_MAX_LENGTH);
        if (validReason == null) {
            throw new VoidReasonRequiredException();
        }
        required(now, "now");
        VoidDecision decision = required(policy, "policy").evaluate(this);
        if (!decision.allowed()) {
            throw new TransactionVoidRefusedException(decision);
        }
        return validReason;
    }

    void applyVoid(String validReason, Instant now) {
        this.status = TransactionStatus.VOIDED;
        this.voidReason = validReason;
        this.voidedAt = now;
        this.entries = entries.stream().map(LedgerEntry::deactivate).toList();
        installments.replaceAll(installment -> installment.isCanceled() ? installment : installment.cancel(now));
    }

    void voidTransaction(String reason, Instant now, TransactionVoidPolicy policy) {
        applyVoid(prepareVoid(reason, now, policy), now);
    }

    public Money chargedAmount() {
        if (type != TransactionType.CARD_PURCHASE) {
            return amount;
        }
        return Money.sum(installments.stream().filter(i -> !i.isCanceled()).map(TransactionInstallment::amount).toList(),
                amount.currency());
    }

    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new TransactionNotFoundException(id);
        }
    }

    void replaceInstallments(List<TransactionInstallment> updated) {
        List<UUID> canceled = updated.stream().filter(TransactionInstallment::isCanceled)
                .map(TransactionInstallment::id).toList();
        installments.clear();
        installments.addAll(updated);
        this.entries = entries.stream()
                .map(e -> e.source().installmentId() != null && canceled.contains(e.source().installmentId()) ? e.deactivate() : e)
                .toList();
    }

    void reallocateInstallments(List<TransactionInstallment> reallocated) {
        if (reallocated.size() != installments.size()) {
            throw new InvoiceChainInconsistentException("realocação com " + reallocated.size() + " parcelas para " + installments.size() + ".");
        }
        for (TransactionInstallment updated : reallocated) {
            TransactionInstallment current = installments.stream().filter(i -> i.id().equals(updated.id())).findFirst()
                    .orElseThrow(() -> new InvoiceChainInconsistentException("parcela desconhecida na realocação: " + updated.id() + "."));
            if (!current.amount().equals(updated.amount()) || !current.effectiveDate().equals(updated.effectiveDate())
                    || current.isCanceled() != updated.isCanceled()) {
                throw new InvoiceChainInconsistentException("a realocação só pode mudar a fatura da parcela " + updated.id() + ".");
            }
        }
        installments.clear();
        installments.addAll(reallocated);
    }

    void inheritVersion(Transaction current) {
        this.version = current.version;
    }

    void reallocateInvoice(LocalDate newInvoiceClosingDate) {
        this.invoiceClosingDate = required(newInvoiceClosingDate, "invoiceClosingDate");
    }

    public List<LedgerEntry> entries() {
        return entries;
    }

    public List<Posting> postings() {
        return postingsOf(entries.stream().filter(LedgerEntry::active).toList());
    }

    public boolean isCardCashOut() {
        return type == TransactionType.CARD_PURCHASE && categoryId != null
                && entries.stream().anyMatch(entry -> entry.posting().bookType() == BookType.EQUITY);
    }

    public boolean isActive() {
        return status == TransactionStatus.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public TransactionType type() {
        return type;
    }

    public Money amount() {
        return amount;
    }

    public LocalDate date() {
        return date;
    }

    public String description() {
        return description;
    }

    public String notes() {
        return notes;
    }

    public TransactionOrigin origin() {
        return origin;
    }

    public PaymentMethod paymentMethod() {
        return paymentMethod;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID counterpartAccountId() {
        return counterpartAccountId;
    }

    public UUID cardId() {
        return cardId;
    }

    public UUID categoryId() {
        return categoryId;
    }

    public UUID debtId() {
        return debtId;
    }

    public UUID refundOfId() {
        return refundOfId;
    }

    public InvestmentDirection investmentDirection() {
        return investmentDirection;
    }

    public AdjustmentDirection adjustmentDirection() {
        return adjustmentDirection;
    }

    public AdjustmentKind adjustmentKind() {
        return adjustmentKind;
    }

    public String reason() {
        return reason;
    }

    public LocalDate invoiceClosingDate() {
        return invoiceClosingDate;
    }

    public boolean lateEntry() {
        return lateEntry;
    }

    public Competence incomeCompetence() {
        return incomeCompetence;
    }

    public PurchaseShares purchaseShares() {
        return purchaseShares;
    }

    public TransactionNature nature() {
        return nature;
    }

    public ForeignCharge foreignCharge() {
        return foreignCharge;
    }

    public void attachForeignCharge(ForeignCharge charge) {
        if (charge != null && !charge.brl().equals(amount)) {
            throw new com.moneylog.core.transaction.exception.FxExceptions.FxInvalidException("o valor em BRL diverge do câmbio informado.");
        }
        this.foreignCharge = charge;
    }

    public String lastDigits() {
        return lastDigits;
    }

    public void attachLastDigits(String digits) {
        if (digits != null && type != TransactionType.CARD_PURCHASE) {
            throw new com.moneylog.core.card.exception.CardExceptions.PurchaseLastDigitsNotFromCardException(digits);
        }
        this.lastDigits = digits == null || digits.isBlank() ? null : digits.strip();
    }

    public Competence effectiveIncomeCompetence() {
        return type != TransactionType.INCOME ? null : incomeCompetence == null ? Competence.from(date) : incomeCompetence;
    }

    public List<TransactionInstallment> installments() {
        return List.copyOf(installments);
    }

    public TransactionStatus status() {
        return status;
    }

    public String voidReason() {
        return voidReason;
    }

    public Instant voidedAt() {
        return voidedAt;
    }

    public long version() {
        return version;
    }

    private static List<Posting> postingsOf(List<LedgerEntry> entries) {
        return entries.stream().map(LedgerEntry::posting).toList();
    }

    private static Money requirePositive(Money amount) {
        if (amount == null || !amount.isPositive()) {
            throw new NonPositiveAmountException();
        }
        return AmountLimit.ensureWithinLimit(amount, "amount");
    }

    private static <T> T required(T value, String field) {
        return TransactionHeader.required(value, field);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Transaction other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Transaction[" + id + ", " + type + ", " + amount + ", " + date + ", " + status + "]";
    }

    static final class Builder {
        private final TransactionHeader header;
        private final TransactionType type;
        private final Money amount;
        private PaymentMethod paymentMethod;
        private UUID accountId;
        private UUID counterpartAccountId;
        private UUID cardId;
        private UUID categoryId;
        private UUID debtId;
        private UUID refundOfId;
        private InvestmentDirection investmentDirection;
        private AdjustmentDirection adjustmentDirection;
        private AdjustmentKind adjustmentKind;
        private String reason;
        private LocalDate invoiceClosingDate;
        private boolean lateEntry;
        private Competence incomeCompetence;
        private PurchaseShares purchaseShares;
        private TransactionNature nature;
        private List<TransactionInstallment> installments = List.of();
        private List<LedgerEntry> entries = List.of();

        private Builder(TransactionHeader header, TransactionType type, Money amount) {
            this.header = header;
            this.type = type;
            this.amount = amount;
        }

        Builder paymentMethod(PaymentMethod value) { this.paymentMethod = value; return this; }
        Builder account(UUID value) { this.accountId = value; return this; }
        Builder counterpartAccount(UUID value) { this.counterpartAccountId = value; return this; }
        Builder card(UUID value) { this.cardId = value; return this; }
        Builder category(UUID value) { this.categoryId = value; return this; }
        Builder debt(UUID value) { this.debtId = value; return this; }
        Builder refundOf(UUID value) { this.refundOfId = value; return this; }
        Builder investmentDirection(InvestmentDirection value) { this.investmentDirection = value; return this; }
        Builder adjustment(AdjustmentDirection direction, AdjustmentKind kind, String why) {
            this.adjustmentDirection = direction;
            this.adjustmentKind = kind;
            this.reason = why;
            return this;
        }
        Builder invoice(LocalDate closingDate, boolean late) { this.invoiceClosingDate = closingDate; this.lateEntry = late; return this; }
        Builder incomeCompetence(Competence value) { this.incomeCompetence = value; return this; }
        Builder purchaseShares(PurchaseShares value) { this.purchaseShares = value; return this; }
        Builder nature(TransactionNature value) { this.nature = value; return this; }
        Builder installments(List<TransactionInstallment> value) { this.installments = value; return this; }
        Builder entries(List<LedgerEntry> value) { this.entries = value; return this; }

        Transaction build() {
            return new Transaction(this);
        }
    }
}
