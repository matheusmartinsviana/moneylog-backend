package com.moneylog.core.insights.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Estatística pura (T285): mediana, MAD, z robusto com piso, p95, CV e média móvel contra um oráculo independente")
class StatsTest {

    private static List<BigDecimal> series(long... values) {
        List<BigDecimal> out = new ArrayList<>();
        for (long v : values) {
            out.add(BigDecimal.valueOf(v));
        }
        return out;
    }

    /** Oráculo: mediana por ordenação, escrita de outro jeito (índice do meio em duas metades). */
    private static BigDecimal oracleMedian(List<BigDecimal> values) {
        List<BigDecimal> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        BigDecimal m = n % 2 == 1 ? sorted.get(n / 2) : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2));
        return m.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal oracleZ(BigDecimal x, List<BigDecimal> values) {
        BigDecimal med = oracleMedian(values);
        List<BigDecimal> dev = new ArrayList<>();
        values.forEach(v -> dev.add(v.subtract(med).abs()));
        BigDecimal mad = oracleMedian(dev);
        BigDecimal divisor = mad.max(med.abs().multiply(new BigDecimal("0.05"))).max(BigDecimal.ONE);
        return new BigDecimal("0.6745").multiply(x.subtract(med)).divide(divisor, 6, RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("[100,102,98,101,300]: z de 300 passa de 3,5 e z de 101 é menor que 0,7")
    void deveDetectarOOutlier() {
        List<BigDecimal> s = series(100, 102, 98, 101, 300);

        assertThat(Stats.robustZ(BigDecimal.valueOf(300), s)).isGreaterThanOrEqualTo(new BigDecimal("3.5"));
        assertThat(Stats.robustZ(BigDecimal.valueOf(101), s).abs()).isLessThan(new BigDecimal("0.7"));
        assertThat(Stats.median(s)).isEqualByComparingTo("101");
        assertThat(Stats.mad(s)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("série constante: MAD zero usa o piso de 5% da mediana e não divide por zero")
    void deveUsarOPisoNaSerieConstante() {
        List<BigDecimal> constant = series(1000, 1000, 1000, 1000);

        assertThat(Stats.mad(constant)).isEqualByComparingTo("0");
        assertThat(Stats.robustScale(constant)).isEqualByComparingTo("50");
        assertThat(Stats.robustZ(BigDecimal.valueOf(1000), constant)).isEqualByComparingTo("0");
        assertThat(Stats.robustZ(BigDecimal.valueOf(1100), constant)).isEqualByComparingTo("1.349000");
    }

    @Test
    @DisplayName("série de zeros: piso absoluto de 1 evita a divisão por zero")
    void deveUsarOPisoAbsolutoQuandoTudoEhZero() {
        assertThat(Stats.robustZ(BigDecimal.TEN, series(0, 0, 0))).isEqualByComparingTo("6.745");
    }

    @Test
    @DisplayName("série vazia é erro de programação, não um número")
    void deveRecusarSerieVazia() {
        assertThatThrownBy(() -> Stats.median(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Stats.percentile(List.of(), 95)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Stats.percentile(series(1, 2), 101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("mediana de quantidade par é a média dos dois do meio")
    void deveTirarAMediaDosDoisDoMeio() {
        assertThat(Stats.median(series(10, 40, 20, 30))).isEqualByComparingTo("25");
        assertThat(Stats.median(series(7))).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("percentil 95 por interpolação linear (método 7)")
    void deveInterpolarOPercentil() {
        List<BigDecimal> oneToTwenty = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            oneToTwenty.add(BigDecimal.valueOf(i));
        }

        // posição 0,95 * 19 = 18,05 -> 19 + 0,05 * (20 - 19) = 19,05
        assertThat(Stats.percentile(oneToTwenty, 95)).isEqualByComparingTo("19.05");
        assertThat(Stats.percentile(oneToTwenty, 0)).isEqualByComparingTo("1");
        assertThat(Stats.percentile(oneToTwenty, 100)).isEqualByComparingTo("20");
        assertThat(Stats.percentile(series(5), 95)).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("CV: renda constante tem CV 0; [2000,4000,1500,4500] tem CV de 0,4249; média não positiva não tem CV")
    void deveCalcularOCoeficienteDeVariacao() {
        assertThat(Stats.coefficientOfVariation(series(3000, 3000, 3000, 3000))).isEqualByComparingTo("0");
        // média 3000; variância populacional (1e6 + 1e6 + 2,25e6 + 2,25e6) / 4 = 1,625e6; desvio 1274,754878; CV 0,424918
        assertThat(Stats.coefficientOfVariation(series(2000, 4000, 1500, 4500))).isEqualByComparingTo("0.424918");
        assertThat(Stats.coefficientOfVariation(series(0, 0))).isNull();
    }

    @Test
    @DisplayName("desvio amostral divide por n-1 e vale zero com uma observação")
    void deveCalcularODesvioAmostral() {
        assertThat(Stats.sampleStdDev(series(2, 4, 4, 4, 5, 5, 7, 9))).isEqualByComparingTo("2.138090");
        assertThat(Stats.populationStdDev(series(2, 4, 4, 4, 5, 5, 7, 9))).isEqualByComparingTo("2");
        assertThat(Stats.sampleStdDev(series(5))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("média móvel é a média das últimas N observações")
    void deveCalcularAMediaMovel() {
        assertThat(Stats.movingAverage(series(10, 20, 30, 40), 3)).isEqualByComparingTo("30");
        assertThatThrownBy(() -> Stats.movingAverage(series(1, 2), 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("propriedade (500 séries): mediana, MAD e z batem com o oráculo; permutar a série não muda nada; escalar a série não muda o z")
    void devePassarNaPropriedade() {
        Random random = new Random(20261016L);
        for (int run = 0; run < 500; run++) {
            int n = 3 + random.nextInt(10);
            List<BigDecimal> values = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                values.add(BigDecimal.valueOf(100_000 + random.nextInt(900_000)));
            }
            BigDecimal x = BigDecimal.valueOf(random.nextInt(2_000_000));

            assertThat(Stats.median(values)).isEqualByComparingTo(oracleMedian(values));
            assertThat(Stats.robustZ(x, values)).isEqualByComparingTo(oracleZ(x, values));

            List<BigDecimal> shuffled = new ArrayList<>(values);
            Collections.shuffle(shuffled, random);
            assertThat(Stats.median(shuffled)).isEqualByComparingTo(Stats.median(values));
            assertThat(Stats.mad(shuffled)).isEqualByComparingTo(Stats.mad(values));
            assertThat(Stats.robustZ(x, shuffled)).isEqualByComparingTo(Stats.robustZ(x, values));

            BigDecimal factor = BigDecimal.valueOf(1 + random.nextInt(9));
            List<BigDecimal> scaled = values.stream().map(v -> v.multiply(factor)).toList();
            BigDecimal diff = Stats.robustZ(x.multiply(factor), scaled).subtract(Stats.robustZ(x, values)).abs();
            assertThat(diff).as("o z é invariante à escala (acima do piso absoluto)").isLessThan(new BigDecimal("0.00001"));
        }
    }
}
