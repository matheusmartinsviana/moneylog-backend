package com.moneylog.core.debt.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

/**
 * Estado persistido de uma {@link Debt}, sem as quitações e os lançamentos (que vão à parte). É o que o mapper da
 * infra grava ({@link Debt#state()}) e devolve a {@link Debt#restore}.
 *
 * @param personId           pessoa devedora/credora (a infra resolve a entidade; a dívida só guarda o id)
 * @param originTransactionId movimentação de origem ({@code WITH_MOVEMENT}); {@code null} em {@code WITHOUT_MOVEMENT}
 * @param closure            encerramento gravado ({@code CANCELED}/{@code VOIDED}); {@code null} se aberta
 * @param creditorName       credor livre (banco, loja, financiamento) das "outras dívidas" (P1); {@code null} quando há {@code personId}
 * @param categoryId         categoria de despesa da dívida {@code PAYABLE} que nasceu de consumo (S-137); {@code null} nas demais
 */
public record DebtState(UUID id, UUID userId, UUID personId, DebtDirection direction, DebtOrigin origin, Money principal,
                        String description, LocalDate date, LocalDate expectedDate, UUID originTransactionId,
                        DebtClosure closure, LocalDate closureDate, String closureReason, UUID categoryId,
                        String creditorName) {

    /** Dívida com pessoa (sem credor livre). */
    public DebtState(UUID id, UUID userId, UUID personId, DebtDirection direction, DebtOrigin origin, Money principal,
                     String description, LocalDate date, LocalDate expectedDate, UUID originTransactionId,
                     DebtClosure closure, LocalDate closureDate, String closureReason, UUID categoryId) {
        this(id, userId, personId, direction, origin, principal, description, date, expectedDate, originTransactionId, closure,
                closureDate, closureReason, categoryId, null);
    }

    /** Dívida sem categoria (todas, exceto a {@code PAYABLE} sem movimentação que nasce de consumo, S-137). */
    public DebtState(UUID id, UUID userId, UUID personId, DebtDirection direction, DebtOrigin origin, Money principal,
                     String description, LocalDate date, LocalDate expectedDate, UUID originTransactionId,
                     DebtClosure closure, LocalDate closureDate, String closureReason) {
        this(id, userId, personId, direction, origin, principal, description, date, expectedDate, originTransactionId, closure,
                closureDate, closureReason, null);
    }
}
