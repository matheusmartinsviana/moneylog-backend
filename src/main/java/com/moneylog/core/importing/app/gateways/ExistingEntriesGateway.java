package com.moneylog.core.importing.app.gateways;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.importing.domain.ExistingEntry;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.sk.money.Money;

/** Movimentações já registradas, vistas como {@link ExistingEntry}, para classificar as linhas de um extrato. */
public interface ExistingEntriesGateway {

    /**
     * Movimentações <b>ativas</b> da conta/cartão entre {@code from} e {@code to}. Transferência que toca a conta entra com a direção do lado dela e
     * como {@code manual} (a perna espelhada vira possível duplicata, nunca despesa/receita sozinha).
     */
    List<ExistingEntry> findForSource(UUID userId, UUID sourceId, boolean card, LocalDate from, LocalDate to);

    /**
     * Entradas e saídas <b>de outras contas</b> do usuário no período: movimentações ativas e linhas ainda não aplicadas de outros lotes em
     * revisão. Serve só para marcar {@code POSSIBLE_TRANSFER}.
     */
    List<ExistingEntry> findInOtherAccounts(UUID userId, UUID excludingAccountId, LocalDate from, LocalDate to);

    /**
     * Existe, em outra conta do usuário, uma receita ou despesa <b>ativa</b> que é a perna oposta da linha (mesmo valor, direção oposta, data a no
     * máximo 1 dia)? Se existe, registrar uma TRANSFER contaria o valor duas vezes (PR 01).
     */
    boolean hasActiveOppositeLeg(UUID userId, UUID accountId, ImportDirection direction, Money amount, LocalDate date);
}
