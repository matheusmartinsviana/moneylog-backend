package com.moneylog.core.transaction.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.money.Money;

/**
 * O que o estorno precisa (M1): motivo, instante técnico, "hoje" do usuário e, conforme o tipo da movimentação, o
 * cartão com a cadeia de faturas dele ou a dívida. O caso de uso monta o contexto; {@link TransactionVoiding} escolhe
 * a guarda pelo tipo e recusa contexto incompleto.
 *
 * @param reason motivo do estorno (obrigatório)
 * @param now    instante técnico (UTC)
 * @param today  data de hoje no fuso do usuário (status da fatura, encerramento da dívida)
 * @param card   cartão da movimentação ({@code null} se não for de cartão)
 * @param chain  cadeia de faturas do cartão ({@code null} se não for de cartão)
 * @param debt   dívida da movimentação ({@code null} se não for de dívida)
 * @param accounts contas que a movimentação toca (movimentação em conta): conta arquivada recusa o estorno (S-73, S-88)
 * @param activeRefunds Σ dos reembolsos ativos ligados à despesa ({@code null} = nenhum): despesa com reembolso ativo não
 *                      é estornada (S-89)
 * @param shareDebts dívidas que nasceram da despesa dividida (D1 §2.6.1): o estorno da despesa só vale se nenhuma tem quitação
 *                   ativa nem foi perdoada, e todas viram {@code VOIDED} junto
 */
public record VoidContext(String reason, Instant now, LocalDate today, CreditCard card, InvoiceChain chain, Debt debt,
                          Collection<Account> accounts, Money activeRefunds, Collection<Debt> shareDebts) {

    public VoidContext {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(today, "today");
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        shareDebts = shareDebts == null ? List.of() : List.copyOf(shareDebts);
    }

    public VoidContext(String reason, Instant now, LocalDate today, CreditCard card, InvoiceChain chain, Debt debt,
                       Collection<Account> accounts, Money activeRefunds) {
        this(reason, now, today, card, chain, debt, accounts, activeRefunds, List.of());
    }

    public VoidContext(String reason, Instant now, LocalDate today, CreditCard card, InvoiceChain chain, Debt debt) {
        this(reason, now, today, card, chain, debt, List.of(), null, List.of());
    }

    /** Contexto de movimentação que só envolve contas. */
    public static VoidContext of(String reason, Instant now, LocalDate today) {
        return new VoidContext(reason, now, today, null, null, null, List.of(), null, List.of());
    }

    /** Acrescenta o cartão e a cadeia de faturas dele (movimentações de cartão). */
    public VoidContext withCard(CreditCard newCard, InvoiceChain newChain) {
        return new VoidContext(reason, now, today, newCard, newChain, debt, accounts, activeRefunds, shareDebts);
    }

    /** Acrescenta a dívida (movimentações de dívida). */
    public VoidContext withDebt(Debt newDebt) {
        return new VoidContext(reason, now, today, card, chain, newDebt, accounts, activeRefunds, shareDebts);
    }

    /** Acrescenta as contas que a movimentação toca (a conta arquivada recusa o estorno, S-73). */
    public VoidContext withAccounts(Collection<Account> touched) {
        return new VoidContext(reason, now, today, card, chain, debt, touched, activeRefunds, shareDebts);
    }

    /** Acrescenta a soma dos reembolsos ativos da despesa (S-89). */
    public VoidContext withActiveRefunds(Money refunds) {
        return new VoidContext(reason, now, today, card, chain, debt, accounts, refunds, shareDebts);
    }

    /** Acrescenta as dívidas que nasceram da despesa dividida (D1 §2.6.1). */
    public VoidContext withShareDebts(Collection<Debt> debts) {
        return new VoidContext(reason, now, today, card, chain, debt, accounts, activeRefunds, debts);
    }
}
