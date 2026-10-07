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

/**
 * Compra no cartão, à vista ou parcelada (T53). A alocação das parcelas nas faturas, com lançamento tardio, é da fábrica
 * do domínio ({@link CardTransactions#purchase}); aqui se carrega o cartão e a cadeia de faturas <b>só dele</b>
 * (S-135) e se declara a trava do cartão, que serializa toda escrita na cadeia (ADR-006 §5).
 */
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

    /**
     * A compra que o cartão, a cadeia e o "hoje" determinam. É o <b>único</b> ponto onde uma compra é montada: a criação
     * passa por aqui e a prévia ({@code PreviewCardPurchaseUseCaseImpl}) também, então os valores da prévia são os que a
     * criação grava (S-124).
     */
    public static Transaction plan(TransactionHeader header, Money amount, CreditCard card, Category category, int installments,
                                   InvoiceChain chain, LocalDate today) {
        return CardTransactions.purchase(header, amount, card, category, installments, chain, today);
    }

    /**
     * Edição limitada da compra (S-121): só descrição, categoria e observação mudam. Valor, data, número de parcelas e cartão
     * têm de ser os da compra; senão {@code TRANSACTION_NOT_EDITABLE} com a explicação (o caminho é estornar, se a guarda de
     * fatura permitir, e lançar de novo, ou cancelar as parcelas restantes e registrar um reembolso).
     *
     * @param activeRefunds Σ dos reembolsos ativos da compra: com reembolso ativo a categoria não muda
     */
    public Transaction edit(CardPurchase c, Transaction current, Money activeRefunds) {
        if (!c.common().amount().equals(current.amount()) || !c.common().date().equals(current.date())
                || c.installments() != current.installments().size() || !c.cardId().equals(current.cardId())) {
            throw new TransactionNotEditableException("o valor, a data, o número de parcelas e o cartão de uma compra no cartão não mudam; "
                    + "estorne a compra (se nenhuma parcela estiver em fatura fechada) e lance de novo, ou cancele as parcelas restantes "
                    + "e registre um reembolso.");
        }
        if (current.categoryId() == null) {
            // compra em andamento sem categoria (contrapartida EQUITY, ADR-013): só descrição e observação mudam
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
