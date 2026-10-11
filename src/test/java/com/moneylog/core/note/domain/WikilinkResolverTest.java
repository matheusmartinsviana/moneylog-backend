package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WikilinkResolver (T107, S-158): resolve pelo nome do arquivo; empate = caminho mais curto")
class WikilinkResolverTest {

    private static final UUID MACBOOK = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MG_TECH = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REVIEW = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID DEEP = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private static WikilinkResolver resolver(Object... idAndPath) {
        java.util.ArrayList<WikilinkResolver.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < idAndPath.length; i += 2) {
            entries.add(new WikilinkResolver.Entry((UUID) idAndPath[i], NotePath.of((String) idAndPath[i + 1])));
        }
        return new WikilinkResolver(entries);
    }

    @Test
    @DisplayName("resolve pelo nome do arquivo, pelo caminho e pelo final do caminho")
    void deveResolver() {
        WikilinkResolver r = resolver(MACBOOK, "MacBook.md", MG_TECH, "projetos/MG Tech.md", REVIEW, "reviews/2026-10.md");
        assertThat(r.resolve("MacBook")).contains(MACBOOK);
        assertThat(r.resolve("MG Tech")).contains(MG_TECH);
        assertThat(r.resolve("projetos/MG Tech")).contains(MG_TECH);
        assertThat(r.resolve("2026-10")).contains(REVIEW);
        assertThat(r.resolve("reviews/2026-10")).contains(REVIEW);
        assertThat(r.resolve("MacBook.md")).contains(MACBOOK);
        assertThat(r.resolve("/MacBook")).contains(MACBOOK);
        assertThat(r.resolve(WikilinkParser.parse("[[2026-10#Resumo]]").get(0))).contains(REVIEW);
    }

    @Test
    @DisplayName("CA3: sem diferenciar maiúsculas nem acentos")
    void deveIgnorarCaixaEAcento() {
        WikilinkResolver r = resolver(REVIEW, "Revisão de Outubro.md");
        assertThat(r.resolve("revisao de outubro")).contains(REVIEW);
        assertThat(r.resolve("REVISÃO DE OUTUBRO")).contains(REVIEW);
        assertThat(r.resolve("Revisa\u0303o de outubro")).contains(REVIEW);
    }

    @Test
    @DisplayName("CA3: nomes repetidos em pastas diferentes: vale o caminho mais curto; empate de tamanho é determinístico")
    void deveEscolherOCaminhoMaisCurto() {
        WikilinkResolver r = resolver(DEEP, "a/b/c/MacBook.md", MACBOOK, "x/MacBook.md", ROOT, "MacBook.md");
        assertThat(r.resolve("MacBook")).contains(ROOT);
        WikilinkResolver semRaiz = resolver(DEEP, "a/b/c/MacBook.md", MACBOOK, "x/MacBook.md");
        assertThat(semRaiz.resolve("MacBook")).contains(MACBOOK);
        assertThat(semRaiz.resolve("a/b/c/MacBook")).contains(DEEP);
        assertThat(semRaiz.resolve("c/MacBook")).contains(DEEP);
        WikilinkResolver empate = resolver(REVIEW, "b/Nota.md", MG_TECH, "a/Nota.md");
        assertThat(empate.resolve("Nota")).contains(MG_TECH);
    }

    @Test
    @DisplayName("CA3: sem alvo = pendente (vazio); alvo com pasta que não casa é pendente")
    void devePendenteSemAlvo() {
        WikilinkResolver r = resolver(MACBOOK, "x/MacBook.md");
        assertThat(r.resolve("Inexistente")).isEqualTo(Optional.empty());
        assertThat(r.resolve("y/MacBook")).isEmpty();
        assertThat(r.resolve("   ")).isEmpty();
        assertThat(r.resolve("/")).isEmpty();
        assertThat(new WikilinkResolver(List.of()).resolve("x")).isEmpty();
    }

    @Test
    @DisplayName("S-274: empate por acento ou caixa é determinístico: mais curto, depois o escrito exatamente igual, depois a ordem do caminho; nunca o id")
    void deveDesempatarSemDependerDoId() {
        UUID low = new UUID(0, 1);
        UUID high = new UUID(0, 2);
        for (WikilinkResolver r : new WikilinkResolver[] {resolver(low, "Café.md", high, "Cafe.md"), resolver(high, "Café.md", low, "Cafe.md"),
                resolver(high, "Cafe.md", low, "Café.md"), resolver(low, "Cafe.md", high, "Café.md")}) {
            UUID cafe = r.resolve("Café").orElseThrow();
            UUID cafeSemAcento = r.resolve("Cafe").orElseThrow();
            assertThat(cafe).isNotEqualTo(cafeSemAcento);
            assertThat(r.resolve("cafe")).contains(cafeSemAcento);
        }
        WikilinkResolver r = resolver(low, "Café.md", high, "Cafe.md");
        assertThat(r.resolve("Café")).contains(low);
        assertThat(r.resolve("Cafe")).contains(high);
        assertThat(r.resolve("Café.md")).contains(low);
        WikilinkResolver deep = resolver(low, "pasta/Café.md", high, "Cafe.md");
        assertThat(deep.resolve("Café")).contains(high);
        assertThat(deep.resolve("pasta/Café")).contains(low);
        assertThat(WikilinkResolver.nameKey("pasta/CAFÉ.md")).isEqualTo(WikilinkResolver.nameKey(NotePath.of("outra/Cafe.md")));
    }
}
