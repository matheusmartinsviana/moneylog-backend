package com.moneylog.core.finance.app.dashboard;

import java.util.List;

import com.moneylog.core.finance.domain.Commitment;
import com.moneylog.core.sk.money.Money;

/**
 * Compromissos futuros do período (S-64). {@code out} e {@code in} somam só os itens datados de primeiro nível; as
 * parcelas aninhadas são informativas e nunca entram na soma.
 *
 * @param items   itens datados, por vencimento: os do período e todos os vencidos
 * @param undated itens sem data (dívida sem previsão)
 * @param overdueTruncated {@code true} quando havia mais ocorrências de recorrência vencidas e pendentes que o limite por chamada (S-240):
 *                         só as mais recentes vêm em {@code items}; {@code out} e {@code in} continuam completos
 */
public record UpcomingView(List<Commitment> items, List<Commitment> undated, Money out, Money in, boolean overdueTruncated) {

    public UpcomingView {
        items = List.copyOf(items);
        undated = List.copyOf(undated);
    }

    public UpcomingView(List<Commitment> items, List<Commitment> undated, Money out, Money in) {
        this(items, undated, out, in, false);
    }
}
