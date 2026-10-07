package com.moneylog.core.finance.app.transactions.handlers;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Refund;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;

/**
 * Reembolso/crédito no cartão (T56): {@code CARD +X / EXPENSE(cat) −X} na competência do reembolso (S-10), como crédito na
 * fatura do período, com lançamento tardio e redirecionamento da fatura paga para a corrente (S-14, S-18). O crédito pode
 * deixar a fatura negativa: o excedente vira crédito transportado. A despesa/compra de origem, se houver, é a mesma regra
 * do reembolso em conta (mesma categoria, teto do cobrado, S-9, S-52). Carrega só a cadeia <b>deste</b> cartão (S-135); as
 * travas (movimentação de origem → cartão) são declaradas pelo handler de {@code REFUND}.
 *
 * <p>Não é registrado sozinho: {@code REFUND} é um comando só (conta ou cartão), então o handler de reembolso despacha para
 * este quando o comando traz {@code cardId}.
 */
public class CardRefundHandler {

    private final HandlerSupport support;
    private final CardGateway cards;
    private final TimeProvider time;

    public CardRefundHandler(HandlerSupport support, CardGateway cards, TimeProvider time) {
        this.support = support;
        this.cards = cards;
        this.time = time;
    }

    public Transaction build(Refund c, UUID id, TransactionOrigin origin, Transaction editing) {
        CreditCard card = cards.findById(c.userId(), c.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(c.cardId()));
        InvoiceChain chain = CardInvoices.chainFor(card, support.transactions().findAllOfCard(c.userId(), card.id()));
        var refundOf = support.refundOrigin(c, editing);
        return plan(support.header(c.common(), id, origin), c.common().amount(), card,
                support.refundCategory(c, refundOf, editing), refundOf, chain, time.today());
    }

    /** O crédito que o cartão, a cadeia e o "hoje" determinam: a única forma de montá-lo. */
    public static Transaction plan(TransactionHeader header, Money amount, CreditCard card, Category category, RefundOrigin refundOf,
                                   InvoiceChain chain, LocalDate today) {
        return CardTransactions.refundToCard(header, amount, card, category, refundOf, chain, today);
    }
}
