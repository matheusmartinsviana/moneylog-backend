package com.moneylog.core.search.app.gateways;

import java.util.UUID;

import com.moneylog.core.search.domain.SearchResults;

public interface SearchGateway {

    /**
     * Busca o termo (já normalizado) nos dados do usuário, até {@code limit} itens por grupo, na ordem determinística do contrato. Cada
     * grupo tem o próprio limite de tempo: o que estourar volta vazio e marcado em {@code truncated}, nunca como erro.
     */
    SearchResults search(UUID userId, String q, int limit);
}
