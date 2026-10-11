package com.moneylog.core.transaction.domain;

import java.time.LocalDate;
import java.util.Objects;

import com.moneylog.core.card.domain.CardVoidRules;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidPolicyNotApplicableException;

public final class CardTransactionVoidPolicy implements TransactionVoidPolicy {

    public static final String HAS_ACTIVE_REFUNDS = TransactionVoidPolicy.HAS_ACTIVE_REFUNDS;

    private final CreditCard card;
    private final InvoiceChain chain;
    private final LocalDate today;
    private final Money activeRefunds;

    public CardTransactionVoidPolicy(CreditCard card, InvoiceChain chain, LocalDate today) {
        this(card, chain, today, null);
    }

    public CardTransactionVoidPolicy(CreditCard card, InvoiceChain chain, LocalDate today, Money activeRefunds) {
        this.card = Objects.requireNonNull(card, "card");
        this.chain = Objects.requireNonNull(chain, "chain");
        chain.ensureFor(card.id());
        this.today = Objects.requireNonNull(today, "today");
        this.activeRefunds = activeRefunds;
    }

    @Override
    public VoidDecision evaluate(Transaction transaction) {
        if (!card.id().equals(transaction.cardId())) {
            throw new VoidPolicyNotApplicableException(transaction.type());
        }
        return switch (transaction.type()) {
            case CARD_PURCHASE -> purchase(transaction);
            case REFUND -> CardVoidRules.forCardRefund(transaction.invoiceClosingDate(), chain, today);
            case INVOICE_PAYMENT -> CardVoidRules.forInvoicePayment();
            default -> throw new VoidPolicyNotApplicableException(transaction.type());
        };
    }

    private VoidDecision purchase(Transaction transaction) {
        VoidDecision decision = CardVoidRules.forPurchase(transaction.installments(), chain, today);
        if (decision.allowed() && activeRefunds != null && activeRefunds.isPositive()) {
            return VoidDecision.refuse(HAS_ACTIVE_REFUNDS, "a compra tem reembolsos ativos; estorne os reembolsos antes.");
        }
        return decision;
    }
}
