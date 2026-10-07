package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;

/**
 * Compromisso futuro calculado (D1 §2.8). As parcelas só aparecem em {@code items} de uma fatura, com
 * {@code informational = true}, e nunca são somadas de novo.
 *
 * @param dueDate {@code null} só nos itens "sem data" (dívida sem previsão)
 * @param refId   fatura: id do cartão; dívida: id da dívida; recorrência: id da recorrência; parcela: id da movimentação
 * @param refDate fatura: data de fechamento (identidade, S-13); demais: {@code null}
 * @param installmentNumber número da parcela (só no item {@code INSTALLMENT}); demais: {@code null}
 * @param installmentTotal  total de parcelas (só no item {@code INSTALLMENT}); demais: {@code null}
 */
public record Commitment(CommitmentKind kind, LocalDate dueDate, Money amount, FlowDirection direction, Bucket bucket, String label,
                         UUID refId, LocalDate refDate, List<Commitment> items, boolean informational,
                         Integer installmentNumber, Integer installmentTotal) {

    public Commitment {
        items = List.copyOf(items);
    }

    /** Item sem número de parcela (tudo, menos {@code INSTALLMENT}). */
    public Commitment(CommitmentKind kind, LocalDate dueDate, Money amount, FlowDirection direction, Bucket bucket, String label,
                      UUID refId, LocalDate refDate, List<Commitment> items, boolean informational) {
        this(kind, dueDate, amount, direction, bucket, label, refId, refDate, items, informational, null, null);
    }
}
