package com.moneylog.core.importing.app.usecase;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.importing.app.ImportBatchView;
import com.moneylog.core.importing.parser.ColumnMapping;

public interface CreateImportBatchUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/imports";

    ImportBatchView handle(CreateImportBatchCommand command);

    record CreateImportBatchCommand(UUID userId, UUID accountId, UUID cardId, byte[] content, String format, ColumnMapping mapping,
                                    UUID idempotencyKey) {
        @Override
        public boolean equals(Object other) {
            return other instanceof CreateImportBatchCommand that && Objects.equals(userId, that.userId) && Objects.equals(accountId, that.accountId)
                    && Objects.equals(cardId, that.cardId) && Arrays.equals(content, that.content) && Objects.equals(format, that.format)
                    && Objects.equals(mapping, that.mapping) && Objects.equals(idempotencyKey, that.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, accountId, cardId, Arrays.hashCode(content), format, mapping, idempotencyKey);
        }

        @Override
        public String toString() {
            return "CreateImportBatchCommand[accountId=" + accountId + ", cardId=" + cardId + ", bytes=" + (content == null ? 0 : content.length)
                    + ", format=" + format + ", mapping=" + mapping + ", idempotencyKey=" + idempotencyKey + "]";
        }
    }
}
