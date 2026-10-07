package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.infra.exception.InvalidPathValueException;

@DisplayName("Fronteira web da Etapa 5: mês do resumo e idioma do CSV")
class ExportAndSummaryWebHelpersTest {

    @Test
    @DisplayName("o mês do resumo aceita AAAA-MM dentro da janela 2000-01 a 2100-12")
    void deveAceitarOMes() throws Exception {
        assertThat(parse("2026-10").toString()).isEqualTo("2026-10");
        assertThat(parse("2000-01").toString()).isEqualTo("2000-01");
        assertThat(parse("2100-12").toString()).isEqualTo("2100-12");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-13", "2026-00", "1999-12", "2101-01", "abc", "2026-1", "202610", "2026-10-01", ""})
    @DisplayName("mês inválido ou fora da janela é SUMMARY_MONTH_INVALID (400)")
    void deveRecusarOMes(String month) {
        assertThatThrownBy(() -> parse(month)).isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .satisfies(e -> {
                    InvalidPathValueException cause = (InvalidPathValueException) e.getCause();
                    assertThat(cause.code()).isEqualTo("SUMMARY_MONTH_INVALID");
                    assertThat(cause.field()).isEqualTo("month");
                });
    }

    private static Object parse(String month) throws Exception {
        var method = Class.forName("com.moneylog.infra.summary.controller.SummaryController").getDeclaredMethod("parse", String.class);
        method.setAccessible(true);
        return method.invoke(null, month);
    }

    @Test
    @DisplayName("Accept-Language: o primeiro idioma vale; ausente, vazio ou ilegível cai em português")
    void deveEscolherOIdioma() throws Exception {
        var method = Class.forName("com.moneylog.infra.export.controller.ExportController").getDeclaredMethod("language", String.class);
        method.setAccessible(true);

        assertThat(((Locale) method.invoke(null, "en-US,en;q=0.9")).getLanguage()).isEqualTo("en");
        assertThat(((Locale) method.invoke(null, "pt-BR")).getLanguage()).isEqualTo("pt");
        assertThat(((Locale) method.invoke(null, (Object) null)).getLanguage()).isEqualTo("pt");
        assertThat(((Locale) method.invoke(null, " "))).isEqualTo(Locale.forLanguageTag("pt-BR"));
        assertThat(((Locale) method.invoke(null, ";;;q=x"))).isEqualTo(Locale.forLanguageTag("pt-BR"));
    }
}
