package com.moneylog.core.insights.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Estatística pura sobre {@link BigDecimal} (ADR-019): nenhum {@code double} (dinheiro e razões são exatos). As séries vêm em centavos ou em
 * razões; a escala de saída é {@value #SCALE}, arredondamento {@code HALF_UP}, e o arredondamento para exibição fica com quem apresenta.
 *
 * <p>z robusto (Iglewicz-Hoaglin): {@code z = 0,6745 (x − mediana) / max(MAD, 5% |mediana|, 1)}. O piso evita divisão por zero numa série
 * constante e impede que uma série quase constante transforme R$ 1 de variação num "desvio enorme".
 */
public final class Stats {

    public static final int SCALE = 6;
    public static final BigDecimal MAD_CONSISTENCY = new BigDecimal("0.6745");
    public static final BigDecimal MAD_FLOOR_RATIO = new BigDecimal("0.05");
    /** Piso absoluto do divisor do z (uma unidade da série; um centavo quando a série está em centavos). */
    public static final BigDecimal ABSOLUTE_FLOOR = BigDecimal.ONE;

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private Stats() {
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            total = total.add(v);
        }
        return total;
    }

    public static BigDecimal mean(List<BigDecimal> values) {
        requireNotEmpty(values);
        return sum(values).divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal median(List<BigDecimal> values) {
        requireNotEmpty(values);
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2).setScale(SCALE, RoundingMode.HALF_UP);
        }
        return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(TWO, SCALE, RoundingMode.HALF_UP);
    }

    /** Desvio absoluto mediano: {@code mediana(|xi − mediana|)}. */
    public static BigDecimal mad(List<BigDecimal> values) {
        BigDecimal median = median(values);
        return median(values.stream().map(v -> v.subtract(median).abs()).toList());
    }

    /** Divisor do z robusto: {@code max(MAD, 5% |mediana|, 1)}. */
    public static BigDecimal robustScale(List<BigDecimal> series) {
        BigDecimal floor = median(series).abs().multiply(MAD_FLOOR_RATIO);
        return mad(series).max(floor).max(ABSOLUTE_FLOOR);
    }

    public static BigDecimal robustZ(BigDecimal x, List<BigDecimal> series) {
        BigDecimal median = median(series);
        return MAD_CONSISTENCY.multiply(x.subtract(median)).divide(robustScale(series), SCALE, RoundingMode.HALF_UP);
    }

    /** Percentil por interpolação linear entre as posições vizinhas (método 7), {@code p} de 0 a 100. */
    public static BigDecimal percentile(List<BigDecimal> values, int p) {
        requireNotEmpty(values);
        if (p < 0 || p > 100) {
            throw new IllegalArgumentException("percentil fora de 0..100: " + p);
        }
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        if (sorted.size() == 1) {
            return sorted.get(0).setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal rank = BigDecimal.valueOf(p).multiply(BigDecimal.valueOf(sorted.size() - 1)).divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
        int lower = rank.setScale(0, RoundingMode.FLOOR).intValueExact();
        int upper = Math.min(lower + 1, sorted.size() - 1);
        BigDecimal fraction = rank.subtract(BigDecimal.valueOf(lower));
        BigDecimal a = sorted.get(lower);
        BigDecimal b = sorted.get(upper);
        return a.add(b.subtract(a).multiply(fraction)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Desvio padrão populacional (divide por n). */
    public static BigDecimal populationStdDev(List<BigDecimal> values) {
        requireNotEmpty(values);
        return variance(values, values.size()).sqrt(new MathContext(20, RoundingMode.HALF_UP)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Desvio padrão amostral (divide por n − 1); zero com uma observação só. */
    public static BigDecimal sampleStdDev(List<BigDecimal> values) {
        requireNotEmpty(values);
        if (values.size() == 1) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return variance(values, values.size() - 1).sqrt(new MathContext(20, RoundingMode.HALF_UP)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Coeficiente de variação {@code desvio populacional / média}; {@code null} se a média não é positiva. */
    public static BigDecimal coefficientOfVariation(List<BigDecimal> values) {
        BigDecimal mean = mean(values);
        if (mean.signum() <= 0) {
            return null;
        }
        return populationStdDev(values).divide(mean, SCALE, RoundingMode.HALF_UP);
    }

    /** Média das últimas {@code window} observações (média móvel, ponto final da série). */
    public static BigDecimal movingAverage(List<BigDecimal> values, int window) {
        if (window < 1 || values.size() < window) {
            throw new IllegalArgumentException("janela " + window + " para " + values.size() + " observações");
        }
        return mean(values.subList(values.size() - window, values.size()));
    }

    private static BigDecimal variance(List<BigDecimal> values, int divisor) {
        BigDecimal mean = sum(values).divide(BigDecimal.valueOf(values.size()), 20, RoundingMode.HALF_UP);
        BigDecimal squares = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal d = v.subtract(mean);
            squares = squares.add(d.multiply(d));
        }
        return squares.divide(BigDecimal.valueOf(divisor), 20, RoundingMode.HALF_UP);
    }

    private static void requireNotEmpty(Collection<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("série vazia");
        }
    }
}
