package com.moneylog.core.finance.app.dashboard;

import com.moneylog.core.finance.domain.DashboardSummary;

/**
 * O resumo do Início com o que a API acrescenta (S-61).
 *
 * @param hasAccounts existe ao menos uma conta do usuário, ativa <b>ou arquivada</b>: o Início mostra o estado vazio só
 *                    quando é {@code false}
 */
public record DashboardView(DashboardSummary summary, boolean hasAccounts) {
}
