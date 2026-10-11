package com.moneylog.core.insights.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.money.Money;

class InsightEngineTest {

    private static final UUID FOOD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Money ZERO = Money.of("0.00");

    @Test
    @DisplayName("alimentação 500,00 -> 684,50: +184,50 (+36,9%) e cartão de 2.400,00")
    void deveGerarOsInsightsDoCenario() {
        var insights = InsightEngine.compute(Map.of(FOOD, Money.of("684.50")), Map.of(FOOD, Money.of("500.00")), Money.of("2400.00"), id -> "Alimentação");

        assertThat(insights).hasSize(2);
        Insight delta = insights.get(0);
        assertThat(delta.type()).isEqualTo(InsightEngine.CATEGORY_DELTA);
        assertThat(delta.key()).isEqualTo("insight.category.up");
        assertThat(delta.params()).containsEntry("deltaAmount", "184.50").containsEntry("deltaPercent", "36.9").containsEntry("current", "684.50");
        assertThat(delta.factRefs()).isNotEmpty();
        Insight card = insights.get(1);
        assertThat(card.type()).isEqualTo(InsightEngine.CARD_COMMITTED);
        assertThat(card.params()).containsEntry("amount", "2400.00");
    }

    @Test
    @DisplayName("limites exatos: 10% e R$ 50,00 entram; abaixo de qualquer um, não")
    void deveRespeitarOsLimitesExatos() {
        assertThat(InsightEngine.compute(Map.of(FOOD, Money.of("550.00")), Map.of(FOOD, Money.of("500.00")), ZERO, id -> "x")).hasSize(1);
        // 9,98% e R$ 49,90
        assertThat(InsightEngine.compute(Map.of(FOOD, Money.of("549.90")), Map.of(FOOD, Money.of("500.00")), ZERO, id -> "x")).isEmpty();
        // 20% mas R$ 20,00
        assertThat(InsightEngine.compute(Map.of(FOOD, Money.of("120.00")), Map.of(FOOD, Money.of("100.00")), ZERO, id -> "x")).isEmpty();
        // queda de R$ 50,00 e 10% também é insight, com chave "down"
        var down = InsightEngine.compute(Map.of(FOOD, Money.of("450.00")), Map.of(FOOD, Money.of("500.00")), ZERO, id -> "x");
        assertThat(down).singleElement().extracting(Insight::key).isEqualTo("insight.category.down");
    }

    @Test
    @DisplayName("o cenário do plano (150,00 -> 184,50 = +34,50) fica abaixo do piso de R$ 50,00 do S-188: sem insight de categoria")
    void naoDeveGerarInsightAbaixoDoPisoDeValor() {
        assertThat(InsightEngine.compute(Map.of(FOOD, Money.of("184.50")), Map.of(FOOD, Money.of("150.00")), ZERO, id -> "x")).isEmpty();
    }

    @Test
    void naoDeveGerarInsightSemMesAnteriorOuSemCartao() {
        assertThat(InsightEngine.compute(Map.of(FOOD, Money.of("900.00")), Map.of(), ZERO, id -> "x")).isEmpty();
        assertThat(InsightEngine.compute(Map.of(), Map.of(), ZERO, id -> "x")).isEmpty();
    }
}
