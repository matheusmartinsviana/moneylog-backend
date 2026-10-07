package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.cards.InvoiceView;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.TimeProvider;

/**
 * Lista as faturas de um cartão sobre a cadeia montada por {@code CardInvoices.chainFor} com as movimentações carregadas
 * (S-79, S-135): nenhum cálculo de fatura em SQL. A paginação é em memória, sobre no máximo algumas dezenas de faturas.
 */
public class ListInvoicesUseCaseImpl implements ListInvoicesUseCase {

    /** Quantos meses à frente da corrente a lista padrão olha (S-131). */
    static final int DEFAULT_MONTHS_AHEAD = 12;

    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public ListInvoicesUseCaseImpl(FinancialRecordsGateway records, TimeProvider time) {
        this.records = records;
        this.time = time;
    }

    @Override
    public Slice<InvoiceView> handle(ListInvoicesQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        CreditCard card = loaded.cards().stream().filter(c -> c.id().equals(query.cardId())).findFirst()
                .orElseThrow(() -> new CardNotFoundException(query.cardId()));
        InvoiceChain chain = CardViews.chainOf(card, loaded);
        LocalDate today = time.today();
        Map<LocalDate, CreditCardInvoice> byClosing = new TreeMap<>();
        if (query.from() == null && query.to() == null) {
            defaultWindow(chain, today).forEach(i -> byClosing.put(i.closingDate(), i));
        } else {
            explicitWindow(chain, today, query.from(), query.to()).forEach(i -> byClosing.put(i.closingDate(), i));
        }
        List<InvoiceView> all = new ArrayList<>();
        byClosing.values().forEach(i -> all.add(InvoiceView.of(card.id(), i, today)));
        java.util.Collections.reverse(all);
        long offset = query.page().offset();
        if (offset >= all.size()) {
            return Slice.empty();
        }
        int from = (int) offset;
        int to = Math.min(all.size(), from + query.page().pageSize());
        return new Slice<>(all.subList(from, to), to < all.size());
    }

    /** Da primeira fatura com item até a última {@code FUTURE} com item (no máximo 12 meses à frente), sempre com a corrente. */
    private static List<CreditCardInvoice> defaultWindow(InvoiceChain chain, LocalDate today) {
        CreditCardInvoice current = chain.current(today);
        LocalDate limit = YearMonth.from(current.closingDate()).plusMonths(DEFAULT_MONTHS_AHEAD).atEndOfMonth();
        List<CreditCardInvoice> result = new ArrayList<>();
        for (CreditCardInvoice invoice : chain.invoices()) {
            boolean future = invoice.statusAt(today) == InvoiceStatus.FUTURE;
            if (!future || !invoice.closingDate().isAfter(limit)) {
                result.add(invoice);
            }
        }
        if (result.stream().noneMatch(i -> i.closingDate().equals(current.closingDate()))) {
            result.add(current);
        }
        return result;
    }

    /**
     * As faturas com movimento no intervalo mais as vazias do calendário dentro da janela que o id resolve
     * (ADR-006 §6: hoje − 24 meses a hoje + 36 meses), para todo id devolvido resolver em {@code GET /v1/invoices/{id}}.
     */
    private static List<CreditCardInvoice> explicitWindow(InvoiceChain chain, LocalDate today, LocalDate from, LocalDate to) {
        Map<LocalDate, CreditCardInvoice> found = new TreeMap<>();
        for (CreditCardInvoice invoice : chain.invoices()) {
            found.put(invoice.closingDate(), invoice);
        }
        YearMonth first = YearMonth.from(today).minusMonths(InvoiceResolver.MONTHS_BEHIND);
        YearMonth last = YearMonth.from(today).plusMonths(InvoiceResolver.MONTHS_AHEAD);
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            LocalDate closing = chain.calendar().periodClosingIn(month).closingDate();
            if (!found.containsKey(closing)) {
                found.put(closing, chain.invoice(closing));
            }
        }
        return found.values().stream()
                .filter(i -> (from == null || !i.closingDate().isBefore(from)) && (to == null || !i.closingDate().isAfter(to)))
                .toList();
    }
}
