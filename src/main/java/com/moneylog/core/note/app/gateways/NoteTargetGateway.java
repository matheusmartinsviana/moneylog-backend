package com.moneylog.core.note.app.gateways;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.note.domain.NoteLinkType;

/**
 * Leitura dos alvos de vínculo (transação, conta, cartão, fatura, pessoa, dívida, meta e nota) do usuário. Só lê: o vault nunca grava nem
 * trava nenhuma entidade financeira. O que não existe, ou é de outro usuário, não vem no resultado.
 */
public interface NoteTargetGateway {

    record TargetRef(NoteLinkType type, UUID id) {
    }

    /** Estado do alvo: movimentação {@code ACTIVE}/{@code VOIDED}; conta, cartão, pessoa e meta {@code ACTIVE}/{@code ARCHIVED}; dívida {@code ACTIVE}/{@code CANCELED}/{@code VOIDED}; nota {@code ACTIVE}/{@code TRASHED}. Fatura: {@code null}. */
    enum TargetStatus {
        ACTIVE, VOIDED, ARCHIVED, CANCELED, TRASHED
    }

    record TargetInfo(String label, TargetStatus status) {
    }

    /** Rótulo e estado de cada alvo que existe para o usuário. Tipos sem id ({@code MONTHLY_SUMMARY}) são ignorados. */
    Map<TargetRef, TargetInfo> describe(UUID userId, Collection<TargetRef> refs);
}
