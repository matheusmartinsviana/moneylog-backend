package com.moneylog.core.insights.domain;

import static com.moneylog.core.insights.domain.InsightFacts.brl;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.insights.domain.SubscriptionDetector.Detected;
import com.moneylog.core.insights.domain.SubscriptionDetector.Line;

@DisplayName("Detecção de assinaturas (T290): descrição normalizada, ocorrências, intervalo e valor")
class SubscriptionDetectorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 20);

    private static Line line(String date, String description, String amount) {
        return new Line(LocalDate.parse(date), description, brl(amount));
    }

    private static final List<Line> STREAMING = List.of(line("2026-07-02", "Streaming X", "39.90"), line("2026-08-01", "streaming x", "39.90"),
            line("2026-08-31", "STREAMING X 3", "39.90"), line("2026-09-30", "Streaming  X", "39.90"));

    @Test
    @DisplayName("4 lançamentos de 'streaming x' de R$ 39,90 a cada 30 dias e nenhuma recorrência: emite com custo anual de 478,80")
    void deveDetectarAAssinatura() {
        List<Detected> found = SubscriptionDetector.detect(STREAMING, Set.of(), AS_OF);

        assertThat(found).hasSize(1);
        Detected d = found.get(0);
        assertThat(d.key()).isEqualTo("streaming x");
        assertThat(d.typicalAmount()).isEqualTo(brl("39.90"));
        assertThat(d.annualCost()).isEqualTo(brl("478.80"));
        assertThat(d.occurrences()).isEqualTo(4);
        assertThat(d.intervalDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("2 ocorrências: não emite")
    void naoDeveDetectarComDuasOcorrencias() {
        assertThat(SubscriptionDetector.detect(STREAMING.subList(2, 4), Set.of(), AS_OF)).isEmpty();
    }

    @Test
    @DisplayName("valores variando mais que ±10%: não emite; dentro de ±10%: emite")
    void deveRespeitarATolerancia() {
        List<Line> wild = List.of(line("2026-08-01", "Academia", "100.00"), line("2026-08-31", "Academia", "100.00"), line("2026-09-30", "Academia", "125.00"));
        List<Line> near = List.of(line("2026-08-01", "Academia", "100.00"), line("2026-08-31", "Academia", "100.00"), line("2026-09-30", "Academia", "110.00"));

        assertThat(SubscriptionDetector.detect(wild, Set.of(), AS_OF)).isEmpty();
        assertThat(SubscriptionDetector.detect(near, Set.of(), AS_OF)).hasSize(1);
    }

    @Test
    @DisplayName("recorrência já cadastrada com a mesma descrição: não duplica")
    void naoDeveDuplicarRecorrenciaCadastrada() {
        assertThat(SubscriptionDetector.detect(STREAMING, Set.of("streaming x"), AS_OF)).isEmpty();
    }

    @Test
    @DisplayName("intervalo fora de 28 a 32 dias (semanal, trimestral) e assinatura abandonada (última há mais de 45 dias): não emite")
    void deveExigirIntervaloMensalEAtividade() {
        List<Line> weekly = List.of(line("2026-09-01", "Feira", "50.00"), line("2026-09-08", "Feira", "50.00"), line("2026-09-15", "Feira", "50.00"));
        List<Line> stale = List.of(line("2026-03-02", "Antiga", "30.00"), line("2026-04-01", "Antiga", "30.00"), line("2026-05-01", "Antiga", "30.00"));

        assertThat(SubscriptionDetector.detect(weekly, Set.of(), AS_OF)).isEmpty();
        assertThat(SubscriptionDetector.detect(stale, Set.of(), AS_OF)).isEmpty();
    }

    @Test
    @DisplayName("descrição vazia ou curta não agrupa; o mesmo dia duas vezes quebra o intervalo")
    void deveIgnorarDescricoesInuteis() {
        List<Line> blank = List.of(line("2026-07-02", " ", "39.90"), line("2026-08-01", " ", "39.90"), line("2026-08-31", " ", "39.90"));
        List<Line> sameDay = List.of(line("2026-09-01", "Duplo", "30.00"), line("2026-09-01", "Duplo", "30.00"), line("2026-09-30", "Duplo", "30.00"));

        assertThat(SubscriptionDetector.detect(blank, Set.of(), AS_OF)).isEmpty();
        assertThat(SubscriptionDetector.detect(sameDay, Set.of(), AS_OF)).isEmpty();
    }

    @Test
    @DisplayName("normalização: minúsculas, sem acento, sem dígitos nem pontuação")
    void deveNormalizar() {
        assertThat(SubscriptionDetector.normalize("  Café  do Zé #123 - SP ")).isEqualTo("cafe do ze sp");
        assertThat(SubscriptionDetector.normalize(null)).isEmpty();
    }
}
