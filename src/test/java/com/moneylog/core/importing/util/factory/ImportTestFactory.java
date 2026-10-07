package com.moneylog.core.importing.util.factory;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.importing.domain.DedupKey;
import com.moneylog.core.importing.domain.ExistingEntry;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.domain.ImportLine;
import com.moneylog.core.sk.money.Money;

public final class ImportTestFactory {

    public static final UUID NUBANK_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    public static final UUID XP_ID = UUID.fromString("00000000-0000-0000-0000-00000000a002");
    public static final UUID BATCH_ID = UUID.fromString("00000000-0000-0000-0000-0000000b0001");
    public static final UUID OTHER_BATCH_ID = UUID.fromString("00000000-0000-0000-0000-0000000b0002");
    public static final UUID TRANSACTION_ID = UUID.fromString("00000000-0000-0000-0000-0000000e0001");
    public static final LocalDate DATE = LocalDate.of(2026, 10, 10);

    private ImportTestFactory() {
    }

    /** Café de 8,50 no Nubank em 10/10/2026. */
    public static ImportLine umaLinhaDeCafe() {
        return ImportLine.of(NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, "Padaria Pão & Café");
    }

    public static ImportLine umaLinha(UUID source, LocalDate date, String amount, ImportDirection direction, String description) {
        return ImportLine.of(source, date, Money.of(amount), direction, description);
    }

    public static ExistingEntry umaMovimentacaoImportada(DedupKey key) {
        return new ExistingEntry(TRANSACTION_ID, NUBANK_ID, DATE, Money.of("8.50"), ImportDirection.OUT, key, key, false, true);
    }

    public static ExistingEntry umaMovimentacaoManual(LocalDate date, String amount, ImportDirection direction) {
        return new ExistingEntry(TRANSACTION_ID, NUBANK_ID, date, Money.of(amount), direction, null, null, true, true);
    }
}
