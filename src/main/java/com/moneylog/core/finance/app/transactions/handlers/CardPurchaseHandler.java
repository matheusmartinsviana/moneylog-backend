package com.moneylog.core.finance.app.transactions.handlers;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.CardPurchase;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.PurchaseShares;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharePersonArchivedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharesInvalidException;
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
    public List<UUID> personsToLock(CardPurchase command) {
        return command.shares().stream().map(ShareLine::personId).filter(Objects::nonNull).distinct().sorted().toList();
    }

    @Override
    public Transaction build(CardPurchase c, UUID id, TransactionOrigin origin, Transaction editing) {
        CreditCard card = cards.findById(c.userId(), c.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(c.cardId()));
        card.ensureLastDigitsBelong(c.lastDigits());
        InvoiceChain chain = CardInvoices.chainFor(card, support.transactions().findAllOfCard(c.userId(), card.id()));
        requireUsablePersons(c);
        Transaction purchase = CardTransactions.purchase(support.header(c.common(), id, origin), c.common().amount(), card,
                support.category(c.userId(), c.categoryId(), editing, CategoryKind.EXPENSE), c.installments(), chain, time.today(),
                c.splitMode(), c.shares());
        purchase.attachForeignCharge(c.foreignCharge());
        purchase.attachLastDigits(c.lastDigits());
        return purchase;
    }

    private void requireUsablePersons(CardPurchase c) {
        List<UUID> personIds = personsToLock(c);
        if (personIds.isEmpty()) {
            return;
        }
        if (!support.hasDebts()) {
            throw new SharesInvalidException("o cadastro de pessoas não está disponível.");
        }
        for (UUID personId : personIds) {
            Person person = support.person(c.userId(), personId);
            person.ensureBelongsTo(c.userId());
            if (person.isArchived()) {
                throw new SharePersonArchivedException();
            }
        }
    }

    public static Transaction plan(TransactionHeader header, Money amount, CreditCard card, Category category, int installments,
                                   InvoiceChain chain, LocalDate today) {
        return CardTransactions.purchase(header, amount, card, category, installments, chain, today);
    }

    public static Transaction plan(TransactionHeader header, Money amount, CreditCard card, Category category, int installments,
                                   InvoiceChain chain, LocalDate today, SplitMode splitMode, List<ShareLine> shares) {
        return CardTransactions.purchase(header, amount, card, category, installments, chain, today, splitMode, shares);
    }

    public Transaction edit(CardPurchase c, Transaction current, Money activeRefunds) {
        if (!c.common().amount().equals(current.amount()) || !c.common().date().equals(current.date())
                || c.installments() != current.installments().size() || !c.cardId().equals(current.cardId())) {
            throw new TransactionNotEditableException("o valor, a data, o número de parcelas e o cartão de uma compra no cartão não mudam; "
                    + "estorne a compra (se nenhuma parcela estiver em fatura fechada) e lance de novo, ou cancele as parcelas restantes "
                    + "e registre um reembolso.");
        }
        if (!c.shares().isEmpty() && !Objects.equals(PurchaseShares.restore(c.splitMode(), c.shares()), current.purchaseShares())) {
            throw new TransactionNotEditableException("o rateio da compra não muda por aqui; use a alteração de rateio da compra.");
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
