package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.card.app.gateways.InvoiceLookupGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.finance.app.cards.PaymentPreviewView;
import com.moneylog.core.finance.app.cards.PaymentPreviewView.Kind;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Prévia do pagamento (S-136). Monta o mesmo {@code INVOICE_PAYMENT} que o pagamento montaria
 * ({@link PayInvoiceUseCaseImpl#plan}, a mesma fábrica do domínio, com a mesma guarda de status e de valor), acrescenta-o
 * à cadeia em memória e descarta. A conta é uma conta líquida transitória (a conta real não muda o resultado, só o caixa).
 * Não trava nada: roda em transação somente leitura.
 */
public class PreviewInvoicePaymentUseCaseImpl implements PreviewInvoicePaymentUseCase {

    private final InvoiceLookupGateway lookup;
    private final FinancialRecordsGateway records;
    private final IdGenerator ids;
    private final TimeProvider time;

    public PreviewInvoicePaymentUseCaseImpl(InvoiceLookupGateway lookup, FinancialRecordsGateway records, IdGenerator ids,
                                            TimeProvider time) {
        this.lookup = lookup;
        this.records = records;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public PaymentPreviewView handle(PreviewInvoicePaymentQuery query) {
        LocalDate today = time.today();
        FinancialRecords loaded = records.load(query.userId());
        InvoiceResolver.Resolved resolved = InvoiceResolver.resolve(query.userId(), query.invoiceId(), lookup, loaded, today);
        CreditCard card = resolved.card();
        InvoiceChain chain = CardViews.chainOf(card, loaded);
        CreditCardInvoice before = chain.invoice(resolved.closingDate());
        InvoiceStatus status = before.statusAt(today);
        Account stand = Account.open(ids.next(), query.userId(), "Prévia", AccountType.CHECKING, null, Money.zero(CurrencyCode.BRL), today);
        Transaction payment = PayInvoiceUseCaseImpl.plan(PayInvoiceUseCaseImpl.header(ids.next(), query.userId(), today, card, chain,
                resolved.closingDate()), query.amount(), stand, card, resolved.closingDate(), chain, today);
        List<Transaction> after = new ArrayList<>(loaded.transactions().stream().filter(t -> card.id().equals(t.cardId())).toList());
        after.add(payment);
        CreditCardInvoice invoice = CardInvoices.chainFor(card, after).invoice(resolved.closingDate());
        Kind kind;
        if (status == InvoiceStatus.OPEN) {
            kind = Kind.PREPAY;
        } else if (query.amount().isGreaterThan(before.outstanding())) {
            kind = Kind.OVERPAY;
        } else {
            kind = query.amount().equals(before.outstanding()) ? Kind.FULL : Kind.PARTIAL;
        }
        return new PaymentPreviewView(kind, invoice.outstanding(), invoice.creditCarry());
    }
}
