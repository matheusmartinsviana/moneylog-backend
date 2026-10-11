package com.moneylog.core.ai.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.ai.app.usecase.AiUseCases.InsightsView;
import com.moneylog.core.ai.domain.Redactor;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.MonthlySummary;
import com.moneylog.core.insights.domain.Insight;
import com.moneylog.core.sk.time.Competence;

@DisplayName("AiFactsAssembler: todo texto livre enviado ao provedor passa pelo redator")
class AiFactsAssemblerTest {

    private static final Competence MONTH = Competence.parse("2026-10");

    private InsightsView viewWith(Map<String, String> params) {
        FinancialRecords empty = new FinancialRecords(List.of(), List.of(), List.of(), List.of());
        MonthlySummary summary = MonthlySummary.of(empty, empty.ledger(), MONTH, LocalDate.of(2026, 10, 7));
        Insight insight = new Insight("recurring", "k", params, List.of());
        return new InsightsView(MONTH, summary, List.of(insight), Map.of(UUID.randomUUID(), "Mercado"));
    }

    @Test
    @DisplayName("description, cardName, goalName e categoryName saem sem CPF, e-mail e operação PIX")
    void deveRedigirOsTextosLivres() {
        InsightsView view = viewWith(Map.of(
                "description", "PIX JOAO SILVA 123.456.789-00",
                "cardName", "Cartao de maria@exemplo.test",
                "goalName", "Reserva 4111 1111 1111 1111",
                "categoryName", "Mercado",
                "amount", "10.00"));

        AiFactsAssembler.Assembled assembled = AiFactsAssembler.assemble(view, new Redactor(List.of()));

        Map<String, String> facts = assembled.facts().values();
        assertThat(facts.get("insight.1.description")).isEqualTo("[PIX]");
        assertThat(facts.get("insight.1.cardName")).contains("[EMAIL]").doesNotContain("maria");
        assertThat(facts.get("insight.1.goalName")).contains("[CARTAO]").doesNotContain("4111");
        assertThat(facts.get("insight.1.amount")).isEqualTo("10.00");
        assertThat(assembled.redactions()).contains("PAYEE", "EMAIL", "CARD");
    }
}
