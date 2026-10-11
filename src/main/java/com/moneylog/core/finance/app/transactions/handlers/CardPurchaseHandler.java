package com.moneylog.core.finance.app.transactions.handlers;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.CardPurchase;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;

public class CardPurchaseHandler implements TransactionTypeHandler<CardPurchase> {

    private final HandlerSupport support;
    private final CardGateway cards;
    private final TimeProvider time;

    public CardPurchaseHandler(HandlerSupport support, CardGateway cards, TimeProvider time) {
        this.support = support;
        this.cards = cards;
        this.time = time;
    }

    @Override
    public Class<CardPurchase> commandType() {
        return CardPurchase.class;
    }

    @Override
    public UUID cardToLock(CardPurchase command) {
        return command.cardId();
    }

    @Override
    public Transaction build(CardPurchase c, UUID id, TransactionOrigin origin, Transaction editing) {
        CreditCard card = cards.findById(c.userId(), c.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(c.cardId()));
        InvoiceChain chain = CardInvoices.chainFor(card, support.transactions().findAllOfCard(c.userId(), card.id()));
        return plan(support.header(c.common(), id, origin), c.common().amount(), card,
                support.category(c.userId(), c.categoryId(), editing, CategoryKind.EXPENSE), c.installments(), chain, time.today());
    }

    public static Transaction plan(TransactionHeader header, Money amount, CreditCard card, Category category, int installments,
                                   InvoiceChain chain, LocalDate today) {
        return CardTransactions.purchase(header, amount, card, category, installments, chain, today);
    }

    public Transaction edit(CardPurchase c, Transaction current, Money activeRefunds) {
        if (!c.common().amount().equals(current.amount()) || !c.common().date().equals(current.date())
                || c.installments() != current.installments().size() || !c.cardId().equals(current.cardId())) {
            throw new TransactionNotEditableException("o valor, a data, o número de parcelas e o cartão de uma compra no cartão não mudam; "
                    + "estorne a compra (se nenhuma parcela estiver em fatura fechada) e lance de novo, ou cancele as parcelas restantes "
                    + "e registre um reembolso.");
        }
        if (current.categoryId() == null) {
            if (c.categoryId() != null) {
                throw new TransactionNotEditableException("a compra parcelada em andamento sem categoria não ganha categoria depois "
                        + "(isso criaria consumo retroativo); estorne e cadastre de novo com a categoria.");
            }
            return CardTransactions.editOngoingDetails(current, support.header(c.common(), current.id(), current.origin()));
        }
        Category category = support.category(c.userId(), c.categoryId(), current, CategoryKind.EXPENSE);
        return CardTransactions.editPurchaseDetails(current, support.header(c.common(), current.id(), current.origin()), category,
                activeRefunds);
    }
}
