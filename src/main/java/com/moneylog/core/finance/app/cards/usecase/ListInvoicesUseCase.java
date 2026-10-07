package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.cards.InvoiceView;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListInvoicesUseCase {

    /**
     * Faturas do cartão em ordem de fechamento decrescente (D2 §4.3). Com {@code from}/{@code to}: as que fecham no
     * intervalo (inclusive), com ou sem item, dentro da janela que o id resolve (ADR-006 §6). Sem eles, a janela padrão
     * (S-131): da primeira fatura com item até a última fatura {@code FUTURE} com item, no máximo 12 meses à frente da
     * corrente, e sempre com a corrente.
     *
     * @throws com.moneylog.core.card.exception.CardExceptions.CardNotFoundException cartão inexistente ou de outro usuário
     */
    Slice<InvoiceView> handle(ListInvoicesQuery query);

    record ListInvoicesQuery(UUID userId, UUID cardId, LocalDate from, LocalDate to, PageQuery page) {
    }
}
