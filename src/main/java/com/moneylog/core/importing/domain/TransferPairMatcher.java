package com.moneylog.core.importing.domain;

import java.time.temporal.ChronoUnit;
import java.util.Collection;

/**
 * Procura a perna oposta de uma possível transferência (S-167): outra conta do usuário com o mesmo valor, direção oposta e data a no máximo 1 dia.
 * <b>Não decide</b>: só avisa. Quem decide é o usuário (a regra de pareamento automático está em aberto).
 */
public final class TransferPairMatcher {

    public static final long WINDOW_DAYS = 1;

    private TransferPairMatcher() {
    }

    public static boolean hasOppositeLeg(ImportLine line, Collection<ExistingEntry> otherSources) {
        return otherSources.stream()
                .filter(ExistingEntry::active)
                .filter(e -> !e.sourceId().equals(line.sourceId()))
                .filter(e -> e.direction() != line.direction())
                .filter(e -> e.amount().equals(line.amount()))
                .anyMatch(e -> Math.abs(ChronoUnit.DAYS.between(e.date(), line.date())) <= WINDOW_DAYS);
    }
}
