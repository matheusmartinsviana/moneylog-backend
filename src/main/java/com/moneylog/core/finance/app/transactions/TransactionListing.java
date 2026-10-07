package com.moneylog.core.finance.app.transactions;

import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.sk.page.Slice;

/**
 * Lista de movimentações com os totais do <b>filtro inteiro</b> (S-74, S-79, S-80).
 *
 * @param count    movimentações <b>ativas</b> que casam com o filtro (S-80); com {@code status = VOIDED}, zero
 * @param cashFlow fluxo de caixa do filtro: {@code CashFlowReport.between} (sem conta) ou {@code forAccount} (com conta)
 */
public record TransactionListing(Slice<TransactionDetails> items, int count, CashFlowReport cashFlow) {
}
