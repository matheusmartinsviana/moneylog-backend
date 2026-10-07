package com.moneylog.core.card.app.gateways;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolução de uma fatura pelo id (ADR-006 §6, passo 1): o id determinístico não é invertível, então, para a fatura que
 * já tem linha gravada, a linha dá o par {@code (cartão, fechamento)}. Todo método filtra pelo {@code userId}.
 */
public interface InvoiceLookupGateway {

    /** A identidade de domínio de uma fatura: o cartão e a data de fechamento (S-13). */
    record InvoiceKey(UUID cardId, LocalDate closingDate) {
    }

    /** O par da linha com esse id, ou vazio se não há linha (ou ela é de outro usuário). */
    Optional<InvoiceKey> findById(UUID userId, UUID invoiceId);
}
