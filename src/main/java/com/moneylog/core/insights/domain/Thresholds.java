package com.moneylog.core.insights.domain;

import java.math.BigDecimal;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/**
 * Limiares dos insights como constantes nomeadas (decisões do dono, plano 2026-10-16 §3): nada é configurável pelo usuário no MVP.
 * Todos os limites são <b>inclusivos</b> (no limite exato, emite), como sempre foi no {@code CATEGORY_DELTA}.
 */
public final class Thresholds {

    private Thresholds() {
    }

    /** Janela de histórico para baselines e mínimo para emitir (Q1). */
    public static final int HISTORY_MONTHS = 6;
    public static final int MIN_HISTORY_MONTHS = 3;
    public static final int MIN_INCOME_STABILITY_MONTHS = 4;

    public static final Money MATERIAL_50 = brl(5_000);
    public static final Money MATERIAL_100 = brl(10_000);
    public static final Money MATERIAL_200 = brl(20_000);

    // poupança
    public static final int SAVINGS_WINDOW = 3;
    public static final BigDecimal SAVINGS_DELTA_PP = BigDecimal.valueOf(5);

    // desvio contra o baseline
    public static final BigDecimal Z_ATTENTION = new BigDecimal("2.5");
    public static final BigDecimal Z_ALERT = new BigDecimal("3.5");
    public static final BigDecimal SPEND_MIN_RELATIVE = new BigDecimal("0.10");
    public static final int SPEND_MIN_DAYS = 10;

    // categorias
    public static final int CATEGORY_WINDOW = 3;
    public static final BigDecimal CATEGORY_MIN_PERCENT = BigDecimal.valueOf(20);
    public static final BigDecimal CATEGORY_LEGACY_MIN_PERCENT = BigDecimal.TEN;

    // projeção de fechamento
    public static final int PROJECTION_MIN_DAYS = 7;
    public static final int PROJECTION_MIN_SPEND_DAYS = 5;
    /** z do intervalo de 80% (normal bicaudal). */
    public static final BigDecimal INTERVAL_80_Z = new BigDecimal("1.28");

    // dinheiro livre
    public static final BigDecimal FREE_LOW_RATIO = new BigDecimal("0.15");

    // comprometimento e dívida (Q2)
    public static final BigDecimal COMMITMENT_ATTENTION = new BigDecimal("0.50");
    public static final BigDecimal COMMITMENT_ALERT = new BigDecimal("0.70");
    public static final BigDecimal DEBT_SERVICE_ATTENTION = new BigDecimal("0.30");
    public static final BigDecimal DEBT_SERVICE_ALERT = new BigDecimal("0.40");

    // parcelas
    public static final int INSTALLMENT_HORIZON_MONTHS = 12;
    public static final BigDecimal INSTALLMENT_PEAK_RATIO = new BigDecimal("1.25");
    public static final BigDecimal INSTALLMENT_DROP_RATIO = new BigDecimal("0.70");
    public static final int INSTALLMENT_DROP_MONTHS = 3;

    // reserva de emergência
    public static final BigDecimal COVERAGE_ALERT_MONTHS = BigDecimal.ONE;
    public static final BigDecimal COVERAGE_ATTENTION_MONTHS = BigDecimal.valueOf(3);
    public static final BigDecimal COVERAGE_OK_MONTHS = BigDecimal.valueOf(6);

    // estabilidade e concentração
    public static final BigDecimal INCOME_CV_ATTENTION = new BigDecimal("0.30");
    public static final BigDecimal CONCENTRATION_MIN_SHARE = new BigDecimal("0.60");
    public static final int CONCENTRATION_MIN_CATEGORIES = 4;
    public static final int GOAL_STREAK_AHEAD = 3;

    // cartão
    public static final BigDecimal CARD_LIMIT_ATTENTION = new BigDecimal("0.70");
    public static final BigDecimal CARD_LIMIT_ALERT = new BigDecimal("0.90");

    // do dia
    public static final BigDecimal TODAY_VS_AVERAGE_RATIO = BigDecimal.valueOf(2);
    public static final int TODAY_VS_AVERAGE_MIN_DAYS = 7;
    public static final int DUE_SOON_DAYS = 7;
    public static final int FORECAST_DAYS = 30;
    public static final int FORECAST_ALERT_DAYS = 7;
    public static final int ATYPICAL_MIN_N = 8;
    public static final int ATYPICAL_P95_MIN_N = 20;
    public static final int ATYPICAL_P = 95;
    public static final BigDecimal ATYPICAL_MIN_MULTIPLE = BigDecimal.valueOf(2);
    public static final int CLOSING_SOON_DAYS = 7;
    public static final int CLOSING_ATTENTION_DAYS = 2;

    // prioridades (desempate dentro da severidade)
    public static final int PRIORITY_MAX = 100;

    private static Money brl(long cents) {
        return Money.ofCents(cents, CurrencyCode.BRL);
    }
}
