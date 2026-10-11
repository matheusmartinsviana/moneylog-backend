package com.moneylog.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.infra.ai.config.AiProperties;

class AiPropertiesTest {

    private static final String KEY = "sk-ant-SENTINELA-987654";

    @Test
    @DisplayName("padrão (none/noop/vazio) = IA desligada, sem exigir chave")
    void deveFicarDesligadoPorPadrao() {
        for (String provider : new String[] {null, "", "none", "NOOP", " noop "}) {
            AiProperties p = new AiProperties(provider, "", "", "", 30, 50, 500_000, 90);
            assertThat(p.enabled()).as(String.valueOf(provider)).isFalse();
            assertThat(p.provider()).isEqualTo("none");
        }
    }

    @Test
    @DisplayName("anthropic sem chave ou sem modelo: não sobe, e a mensagem não traz a chave")
    void deveFalharSemChaveOuModelo() {
        assertThatThrownBy(() -> new AiProperties("anthropic", "", "claude-x", "", 30, 50, 1, 90)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AI_API_KEY");
        assertThatThrownBy(() -> new AiProperties("anthropic", KEY, "", "", 30, 50, 1, 90)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(KEY).hasMessageNotContaining("SENTINELA");
        assertThat(new AiProperties("anthropic", KEY, "claude-x", "", 30, 50, 1, 90).enabled()).isTrue();
    }

    @Test
    void deveRecusarProvedorDesconhecidoSemEcoarOValor() {
        assertThatThrownBy(() -> new AiProperties(KEY, "", "", "", 30, 50, 1, 90)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(KEY);
    }

    @Test
    @DisplayName("toString mascara a chave")
    void deveMascararAChaveNoToString() {
        AiProperties p = new AiProperties("anthropic", KEY, "claude-x", "https://u:p@host", 30, 50, 1, 90);
        assertThat(p.toString()).contains("****").doesNotContain(KEY).doesNotContain("SENTINELA").doesNotContain("u:p@host");
    }

    @Test
    @DisplayName("AI_BASE_URL exige https, aceitando http só em localhost e 127.0.0.1")
    void deveExigirHttpsNaBaseUrl() {
        assertThatThrownBy(() -> new AiProperties("anthropic", KEY, "claude-x", "http://api.exemplo.test", 30, 50, 1, 90))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("https").hasMessageNotContaining("exemplo.test");
        assertThatThrownBy(() -> new AiProperties("anthropic", KEY, "claude-x", "ftp://localhost", 30, 50, 1, 90))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new AiProperties("anthropic", KEY, "claude-x", "https://api.exemplo.test", 30, 50, 1, 90).enabled()).isTrue();
        assertThat(new AiProperties("anthropic", KEY, "claude-x", "http://localhost:8089", 30, 50, 1, 90).enabled()).isTrue();
        assertThat(new AiProperties("anthropic", KEY, "claude-x", "http://127.0.0.1:8089", 30, 50, 1, 90).enabled()).isTrue();
    }
}
