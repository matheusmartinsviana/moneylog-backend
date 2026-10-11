package com.moneylog.core.insights.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Insight calculado (ADR-019): vale sem IA, sem provedor e offline. O backend <b>não monta a frase</b>: devolve o {@code type}, a chave de
 * i18n, os parâmetros (decimais em texto) e as referências de fato; o front traduz e formata, sem calcular.
 *
 * <p>Os quatro primeiros campos são o contrato original (2.8.0) e não mudam; o restante é acréscimo (2.20.0). O {@code id} público
 * ({@code ins_<fingerprint>}) é derivado de {@code (usuário, type, period, subjectId)} pelo agregador, não mora aqui.
 *
 * @param severity       ALERT &gt; ATTENTION &gt; POSITIVE &gt; INFO
 * @param priority       desempate dentro da severidade (maior primeiro)
 * @param period         mês ou dia a que o insight se refere; {@code null} só no insight legado do motor de categorias (sem período)
 * @param basis          como o número nasceu (agenda conhecida, histórico ou projeção); {@code null} nos que não são estatísticos
 * @param confidence     {@code n}, janela e {@code z} dos estatísticos; {@code null} nos demais
 * @param subjectId      o que o insight descreve dentro do tipo (categoria, meta, cartão, lançamento); {@code null} = único no período
 * @param magnitudeCents tamanho do insight em centavos, só para ordenar (maior primeiro)
 */
public record Insight(String type, String key, Map<String, String> params, List<String> factRefs, Severity severity, int priority,
                      Period period, Basis basis, Confidence confidence, String subjectId, long magnitudeCents) {

    public Insight {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(severity, "severity");
        params = Map.copyOf(params);
        factRefs = List.copyOf(factRefs);
    }

    /** Insight no formato original (sem período): severidade {@code INFO}. Usado pelo motor legado de categorias da IA. */
    public Insight(String type, String key, Map<String, String> params, List<String> factRefs) {
        this(type, key, params, factRefs, Severity.INFO, 0, null, null, null, null, 0L);
    }

    /** Severidade, ordem de exibição: o menor ordinal vem primeiro. */
    public enum Severity {
        ALERT,
        ATTENTION,
        POSITIVE,
        INFO
    }

    /** Como o número nasceu. */
    public enum Basis {
        /** Só o que já está agendado (faturas, parcelas, recorrências, lançamentos futuros): não prevê gasto novo. */
        KNOWN_SCHEDULE,
        /** Estatística sobre os meses fechados. */
        HISTORY,
        /** Extrapolação do mês corrente. */
        PROJECTION
    }

    /** Mês ({@code AAAA-MM}) ou dia ({@code AAAA-MM-DD}) a que o insight se refere. */
    public record Period(Kind kind, String value) {

        public Period {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
        }

        public enum Kind {
            MONTH,
            DAY
        }
    }

    /**
     * @param n      observações usadas
     * @param window janela pedida (meses, ou dias do mês na projeção)
     * @param z      z robusto, quando houver (texto decimal)
     */
    public record Confidence(int n, int window, String z) {
    }
}
