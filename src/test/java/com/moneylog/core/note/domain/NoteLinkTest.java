package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.note.exception.NoteExceptions.NoteLinkInvalidException;

@DisplayName("NoteLink (T109, S-160): nunca há dupla referência")
class NoteLinkTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    @DisplayName("CA2 (T109): resumo mensal aceita AAAA-MM de 2000-01 a 2100-12; 2026-13 e outros formatos não")
    void deveValidarOMes() {
        assertThat(NoteLink.explicitMonth("2026-10").state()).isEqualTo(NoteLink.State.RESOLVED);
        assertThat(NoteLink.isValidMonth("2000-01")).isTrue();
        assertThat(NoteLink.isValidMonth("2100-12")).isTrue();
        for (String bad : new String[] {"2026-13", "2026-00", "1999-12", "2101-01", "2026-1", "26-10", "2026/10", "2026-10-01", "", " 2026-10", null}) {
            assertThat(NoteLink.isValidMonth(bad)).as(String.valueOf(bad)).isFalse();
        }
        assertThatThrownBy(() -> NoteLink.explicitMonth("2026-13")).isInstanceOf(NoteLinkInvalidException.class);
    }

    @Test
    @DisplayName("um vínculo explícito tem id OU chave OU texto pendente, nunca mais de um")
    void deveTerUmaReferencia() {
        assertThat(NoteLink.explicit(NoteLinkType.GOAL, ID).state()).isEqualTo(NoteLink.State.RESOLVED);
        assertThat(NoteLink.pending(NoteLinkType.GOAL, "x").state()).isEqualTo(NoteLink.State.PENDING);
        assertThatThrownBy(() -> new NoteLink(LinkOrigin.EXPLICIT, NoteLinkType.GOAL, ID, null, "x", null, null, false, null)).isInstanceOf(NoteLinkInvalidException.class);
        assertThatThrownBy(() -> new NoteLink(LinkOrigin.EXPLICIT, NoteLinkType.GOAL, null, null, null, null, null, false, null)).isInstanceOf(NoteLinkInvalidException.class);
        assertThatThrownBy(() -> new NoteLink(LinkOrigin.EXPLICIT, NoteLinkType.MONTHLY_SUMMARY, ID, "2026-10", null, null, null, false, null)).isInstanceOf(NoteLinkInvalidException.class);
        assertThatThrownBy(() -> new NoteLink(LinkOrigin.EXPLICIT, NoteLinkType.MONTHLY_SUMMARY, ID, null, null, null, null, false, null)).isInstanceOf(NoteLinkInvalidException.class);
        assertThatThrownBy(() -> new NoteLink(LinkOrigin.EXPLICIT, NoteLinkType.GOAL, null, "2026-10", null, null, null, false, null)).isInstanceOf(NoteLinkInvalidException.class);
        assertThatThrownBy(() -> new NoteLink(LinkOrigin.WIKILINK, NoteLinkType.GOAL, null, null, "x", null, null, false, null)).isInstanceOf(NoteLinkInvalidException.class);
        assertThatThrownBy(() -> new NoteLink(LinkOrigin.WIKILINK, NoteLinkType.NOTE, null, null, null, null, null, false, null)).isInstanceOf(NoteLinkInvalidException.class);
        assertThatThrownBy(() -> NoteLink.pending(NoteLinkType.GOAL, "x".repeat(256))).isInstanceOf(NoteLinkInvalidException.class);
    }

    @Test
    @DisplayName("tipos: só MONTHLY_SUMMARY usa chave; entidade financeira exclui NOTE e o resumo")
    void deveClassificarTipos() {
        assertThat(NoteLinkType.MONTHLY_SUMMARY.usesKey()).isTrue();
        assertThat(NoteLinkType.GOAL.usesKey()).isFalse();
        assertThat(NoteLinkType.GOAL.isFinancialEntity()).isTrue();
        assertThat(NoteLinkType.NOTE.isFinancialEntity()).isFalse();
        assertThat(NoteLinkType.MONTHLY_SUMMARY.isFinancialEntity()).isFalse();
    }

    @Test
    @DisplayName("identity separa wikilink de explícito e ignora caixa/acento/.md no wikilink")
    void deveTerIdentidade() {
        NoteLink a = NoteLink.wikilink(WikilinkParser.parse("[[Revisão.md]]").get(0), null, "c");
        NoteLink b = NoteLink.wikilink(WikilinkParser.parse("[[revisao]]").get(0), ID, "c");
        assertThat(a.identity()).isEqualTo(b.identity());
        assertThat(b.withTarget(null).targetId()).isNull();
        assertThat(NoteLink.explicit(NoteLinkType.NOTE, ID).identity()).isNotEqualTo(b.identity());
        assertThat(NoteLink.explicitMonth("2026-10").identity()).isEqualTo("E:MONTHLY_SUMMARY:2026-10");
        assertThat(NoteLink.pending(NoteLinkType.GOAL, "x").identity()).isEqualTo("E:GOAL:?x");
    }
}
