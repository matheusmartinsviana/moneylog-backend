package com.moneylog.core.ai.domain;

import java.util.List;
import java.util.Map;

/**
 * Insight determinístico (S-188): vale sem IA. O backend <b>não monta a frase</b>: devolve o {@code type}, a chave de i18n, os parâmetros e as
 * referências de fato; o front traduz e formata.
 */
public record Insight(String type, String key, Map<String, String> params, List<String> factRefs) {

    public Insight {
        params = Map.copyOf(params);
        factRefs = List.copyOf(factRefs);
    }
}
