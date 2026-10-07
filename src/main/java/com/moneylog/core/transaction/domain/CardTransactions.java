package com.moneylog.core.transaction.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InstallmentPlan;
import com.moneylog.core.card.domain.InvoiceAllocator;
import com.moneylog.core.card.domain.InvoiceAllocator.Allocation;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.money.MoneyExceptions.CurrencyMismatchException;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvalidTransactionOperationException;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException;

/**
 * Fábricas e operações das movimentações de cartão (D1 §2.4, §2.5): {@code CARD_PURCHASE}, {@code INVOICE_PAYMENT},
 * {@code REFUND} no cartão e cancelamento das parcelas restantes.
 */
public final class CardTransactions {

    private CardTransactions() {
    }

    /**
     * Compra no cartão com alocação pelo status das faturas (S-14): item datado num ciclo de fatura fechada e não paga
     * entra nela como lançamento tardio; se ela já foi paga, entra na corrente. As parcelas seguintes seguem a
     * anterior. Para cada parcela k: {@code EXPENSE(cat) +p_k / CARD −p_k} na data efetiva da parcela, origem
     * {@code TRANSACTION} com o id da parcela. Nenhum posting em {@code ACCOUNT}: caixa 0 (PR 01 caso 2).
     *
     * <p>É a única forma de criar uma compra no cartão (A2): não existe alocação só pelo calendário.
     */
    public static Transaction purchase(TransactionHeader header, Money amount, CreditCard card, Category category, int installments,
                                       InvoiceChain chain, LocalDate today) {
        AccountTransactions.requireHeaderAndAmount(header, amount);
        requireUsableCard(header, amount, card);
        requireChain(card, chain, today);
        InstallmentPlan.validateCount(installments);
        return build(header, amount, card, category, installments,
                InvoiceAllocator.forInstallments(header.date(), installments, chain, today));
    }

    /**
     * Compra parcelada <b>em andamento</b> (ADR-013, P5/P7): o usuário informa o valor de cada parcela, o total de parcelas e quantas
     * faltam pagar (a próxima a vencer inclusive: "parcela 3 de 10" = faltam 8). Grava só as parcelas restantes
     * ({@code total−restantes+1..total}) na fatura da {@code firstInvoiceDate} e nas seguintes; <b>não cria consumo retroativo</b>:
     * sem categoria, cada parcela é {@code CARD −p / EQUITY +p} (consumo 0, dívida +p, como a dívida reconhecida sem movimentação);
     * com categoria, {@code EXPENSE(cat) +p / CARD −p} só na competência de cada parcela (que não pode ser anterior à de hoje).
     * A fatura da próxima parcela não pode estar paga ({@code ONGOING_FIRST_INVOICE_NOT_PAYABLE}); não há lançamento tardio
     * silencioso em fatura paga.
     */
    public static Transaction ongoingPurchase(TransactionHeader header, Money installmentAmount, int totalInstallments,
                                              int remainingInstallments, CreditCard card, Category category, InvoiceChain chain,
                                              LocalDate today) {
        TransactionHeader.required(header, "header");
        TransactionHeader.required(installmentAmount, "installmentAmount");
        if (remainingInstallments < 1 || remainingInstallments > totalInstallments) {
            throw new com.moneylog.core.card.exception.CardExceptions.OngoingInvalidCountException(totalInstallments, remainingInstallments);
        }
        Money amount = installmentAmount.times(remainingInstallments);
        AccountTransactions.requireHeaderAndAmount(header, installmentAmount);
        AccountTransactions.requireHeaderAndAmount(header, amount);
        requireUsableCard(header, installmentAmount, card);
        requireChain(card, chain, today);
        InstallmentPlan.validateCount(totalInstallments);
        if (category != null) {
            AccountTransactions.requireCategory(header, category, CategoryKind.EXPENSE);
            if (com.moneylog.core.sk.time.Competence.from(header.date()).isBefore(com.moneylog.core.sk.time.Competence.from(today))) {
                throw new com.moneylog.core.card.exception.CardExceptions.OngoingCategoryRetroactiveException();
            }
        }
        var firstPeriod = chain.calendar().periodFor(header.date());
        if (chain.statusOf(firstPeriod.closingDate(), today) == com.moneylog.core.card.domain.InvoiceStatus.PAID) {
            throw new com.moneylog.core.card.exception.CardExceptions.OngoingFirstInvoiceNotPayableException(firstPeriod.closingDate());
        }
        List<Allocation> allocations = InvoiceAllocator.forInstallments(header.date(), remainingInstallments, chain, today);
        List<TransactionInstallment> plan = InstallmentPlan.generateOngoing(header.id(), installmentAmount, totalInstallments,
                remainingInstallments, header.date(), allocations);
        BookRef counterpart = category == null ? BookRef.equity() : category.book();
        // sem categoria a contrapartida é EQUITY: o CARD fica negativo (dívida) e o EQUITY positivo, como no reconhecimento de dívida
        List<LedgerEntry> entries = new ArrayList<>();
        for (TransactionInstallment installment : plan) {
            entries.addAll(LedgerEntry.activeGroup(PostingSource.installment(header.id(), installment.id()), List.of(
                    new Posting(counterpart, installment.amount(), installment.effectiveDate()),
                    new Posting(card.book(), installment.amount().negate(), installment.effectiveDate()))));
        }
        return Transaction.builder(header, TransactionType.CARD_PURCHASE, amount)
                .card(card.id()).category(category == null ? null : category.id()).installments(plan).entries(entries).build();
    }

    /**
     * Edição da compra em andamento sem categoria (contrapartida EQUITY): só descrição e observação mudam; os lançamentos e as
     * parcelas ficam como estão. Com categoria, a edição é a da compra comum ({@link #editPurchaseDetails}).
     */
    public static Transaction editOngoingDetails(Transaction current, TransactionHeader newHeader) {
        java.util.Objects.requireNonNull(current, "current");
        AccountTransactions.requireHeaderAndAmount(newHeader, current.amount());
        if (current.type() != TransactionType.CARD_PURCHASE || !current.isActive() || current.categoryId() != null) {
            throw new com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException(
                    "só uma compra em andamento ativa e sem categoria tem esta edição.");
        }
        if (!current.id().equals(newHeader.id()) || !current.date().equals(newHeader.date())) {
            throw new com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException(
                    "o id e a data da compra não mudam.");
        }
        Transaction replacement = Transaction.builder(newHeader, TransactionType.CARD_PURCHASE, current.amount())
                .card(current.cardId()).installments(current.installments()).entries(current.entries()).build();
        return TransactionEditing.replace(current, replacement, null);
    }

    /**
     * Pagamento de fatura: {@code CARD +X / ACCOUNT −X}, nenhum posting de {@code EXPENSE} (PR 01 caso 3). Valor livre
     * &gt; 0 em fatura {@code OPEN}/{@code CLOSED}/{@code OVERDUE} (S-15, S-16); o excedente vira crédito transportado.
     */
    public static Transaction invoicePayment(TransactionHeader header, Money amount, Account account, CreditCard card,
                                             LocalDate invoiceClosingDate, InvoiceChain chain, LocalDate today) {
        AccountTransactions.requireHeaderAndAmount(header, amount);
        AccountTransactions.requireCashAccount(header, amount, account, TransactionType.INVOICE_PAYMENT);
        TransactionHeader.required(card, "card");
        card.ensureBelongsTo(header.userId());
        TransactionHeader.required(invoiceClosingDate, "invoiceClosingDate");
        requireChain(card, chain, today);
        chain.ensurePayable(invoiceClosingDate, today);
        return Transaction.builder(header, TransactionType.INVOICE_PAYMENT, amount)
                .account(account.id()).card(card.id()).invoice(invoiceClosingDate, false)
                .entries(AccountTransactions.entries(header,
                        new Posting(card.book(), amount, header.date()),
                        new Posting(account.book(), amount.negate(), header.date())))
                .build();
    }

    /**
     * Reembolso no cartão: {@code CARD +X / EXPENSE(cat) −X} na competência do reembolso (S-10), como crédito na fatura
     * do período (com lançamento tardio, S-14). Pode deixar a fatura negativa: vira crédito transportado (S-18).
     */
    public static Transaction refundToCard(TransactionHeader header, Money amount, CreditCard card, Category category,
                                           RefundOrigin refundOf, InvoiceChain chain, LocalDate today) {
        AccountTransactions.requireHeaderAndAmount(header, amount);
        requireUsableCard(header, amount, card);
        AccountTransactions.requireCategory(header, category, CategoryKind.EXPENSE);
        AccountTransactions.validateRefundOrigin(header, amount, category, refundOf);
        requireChain(card, chain, today);
        Allocation allocation = InvoiceAllocator.targetInvoiceFor(header.date(), chain, today);
        return Transaction.builder(header, TransactionType.REFUND, amount)
                .card(card.id()).category(category.id()).refundOf(refundOf == null ? null : refundOf.original().id())
                .invoice(allocation.invoice().closingDate(), allocation.lateEntry())
                .entries(AccountTransactions.entries(header,
                        new Posting(card.book(), amount, header.date()),
                        new Posting(category.book(), amount.negate(), header.date())))
                .build();
    }

    /**
     * Cancela as parcelas restantes (D1 §2.5.2): as de faturas {@code FUTURE}/{@code OPEN} viram {@code CANCELED} e só
     * os postings delas ficam inativos; as de faturas fechadas não mudam (correção = reembolso no cartão).
     *
     * <p>S-218 (A1): recusa com {@code REFUND_EXCEEDS_ORIGINAL} quando a soma dos reembolsos ativos ligados à compra
     * passaria do valor cobrado <b>depois</b> do cancelamento (o consumo da compra ficaria negativo). Nada é alterado
     * quando a recusa acontece.
     *
     * @param activeRefunds Σ dos reembolsos ativos ligados à compra, lida sob a trava da movimentação; {@code null} = nenhum
     */
    public static void cancelRemainingInstallments(Transaction purchase, InvoiceChain chain, LocalDate today, Instant now,
                                                   Money activeRefunds) {
        if (purchase.type() != TransactionType.CARD_PURCHASE || !purchase.isActive()) {
            throw new InvalidTransactionOperationException("TRANSACTION_NOT_CANCELABLE",
                    "Só uma compra no cartão ativa tem parcelas para cancelar.");
        }
        TransactionHeader.required(chain, "chain").ensureFor(purchase.cardId());
        List<TransactionInstallment> updated = InstallmentPlan.cancelRemaining(purchase.installments(), chain, today, now);
        if (activeRefunds != null && activeRefunds.isPositive()) {
            Money chargedAfter = Money.sum(updated.stream().filter(i -> !i.isCanceled()).map(TransactionInstallment::amount).toList(),
                    purchase.amount().currency());
            if (activeRefunds.isGreaterThan(chargedAfter)) {
                throw new RefundExceedsOriginalException();
            }
        }
        purchase.replaceInstallments(updated);
    }

    /** Cancelamento de uma compra sem reembolso ativo (equivale a {@code activeRefunds = null}). */
    public static void cancelRemainingInstallments(Transaction purchase, InvoiceChain chain, LocalDate today, Instant now) {
        cancelRemainingInstallments(purchase, chain, today, now, null);
    }

    /**
     * Edição limitada da compra no cartão (S-121): só descrição, observação e categoria. Valor, data, número de parcelas e
     * cartão não mudam (reescrever parcelas e faturas arriscaria dupla contagem e mexeria em fatura fechada). A substituta
     * tem o mesmo id, as mesmas parcelas (faturas e cancelamentos intactos) e os lançamentos regravados na categoria nova:
     * os das parcelas ativas ficam ativos e os das canceladas ficam inativos (histórico).
     *
     * @param newHeader     descrição e observação novas; id, dono, data e origem têm de ser os da compra
     * @param activeRefunds Σ dos reembolsos ativos ligados à compra: com reembolso ativo a categoria não muda
     *                      ({@code REFUND_CATEGORY_LOCKED}, o reembolso volta para a categoria da compra, S-9)
     */
    public static Transaction editPurchaseDetails(Transaction current, TransactionHeader newHeader, Category category,
                                                  Money activeRefunds) {
        java.util.Objects.requireNonNull(current, "current");
        AccountTransactions.requireHeaderAndAmount(newHeader, current.amount());
        AccountTransactions.requireCategory(newHeader, category, CategoryKind.EXPENSE);
        if (current.type() != TransactionType.CARD_PURCHASE || !current.isActive()) {
            throw new com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException(
                    "só uma compra no cartão ativa tem edição limitada.");
        }
        if (!current.id().equals(newHeader.id()) || !current.date().equals(newHeader.date())) {
            throw new com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException(
                    "o id e a data da compra não mudam.");
        }
        if (activeRefunds != null && activeRefunds.isPositive() && !category.id().equals(current.categoryId())) {
            throw new com.moneylog.core.transaction.exception.TransactionExceptions.RefundCategoryLockedException();
        }
        BookRef cardBook = BookRef.card(current.cardId());
        List<LedgerEntry> entries = new ArrayList<>();
        for (TransactionInstallment installment : current.installments()) {
            List<LedgerEntry> group = LedgerEntry.activeGroup(PostingSource.installment(current.id(), installment.id()), List.of(
                    new Posting(category.book(), installment.amount(), installment.effectiveDate()),
                    new Posting(cardBook, installment.amount().negate(), installment.effectiveDate())));
            group.stream().map(entry -> installment.isCanceled() ? entry.deactivate() : entry).forEach(entries::add);
        }
        Transaction replacement = Transaction.builder(newHeader, TransactionType.CARD_PURCHASE, current.amount())
                .card(current.cardId()).category(category.id()).installments(current.installments()).entries(entries).build();
        return TransactionEditing.replace(current, replacement, null);
    }

    private static Transaction build(TransactionHeader header, Money amount, CreditCard card, Category category,
                                     int installments, List<Allocation> allocations) {
        AccountTransactions.requireCategory(header, category, CategoryKind.EXPENSE);
        List<TransactionInstallment> plan = InstallmentPlan.generate(header.id(), amount, installments, header.date(), allocations);
        List<LedgerEntry> entries = new ArrayList<>();
        for (TransactionInstallment installment : plan) {
            entries.addAll(LedgerEntry.activeGroup(PostingSource.installment(header.id(), installment.id()), List.of(
                    new Posting(category.book(), installment.amount(), installment.effectiveDate()),
                    new Posting(card.book(), installment.amount().negate(), installment.effectiveDate()))));
        }
        return Transaction.builder(header, TransactionType.CARD_PURCHASE, amount)
                .card(card.id()).category(category.id()).installments(plan).entries(entries)
                .build();
    }

    /** A cadeia é obrigatória e precisa ser do cartão da movimentação (A3). */
    static void requireChain(CreditCard card, InvoiceChain chain, LocalDate today) {
        TransactionHeader.required(chain, "chain").ensureFor(card.id());
        TransactionHeader.required(today, "today");
    }

    static void requireUsableCard(TransactionHeader header, Money amount, CreditCard card) {
        TransactionHeader.required(card, "card");
        card.ensureBelongsTo(header.userId());
        card.ensureCanReceiveTransactions();
        if (!card.currency().equals(amount.currency())) {
            throw new CurrencyMismatchException(card.currency(), amount.currency());
        }
    }
}
