package com.moneylog.core.card.app.gateways;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceLookupGateway {

    record InvoiceKey(UUID cardId, LocalDate closingDate) {
    }

    Optional<InvoiceKey> findById(UUID userId, UUID invoiceId);
}
