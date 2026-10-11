package com.moneylog.core.note.app;

import static com.moneylog.core.note.util.NoteTestKit.NOW;
import static com.moneylog.core.note.util.NoteTestKit.OTHER_USER;
import static com.moneylog.core.note.util.NoteTestKit.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetInfo;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetRef;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetStatus;
import com.moneylog.core.note.app.usecase.CreateNoteUseCase.CreateNoteCommand;
import com.moneylog.core.note.app.usecase.CreateNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.ExportVaultUseCase.ExportVaultQuery;
import com.moneylog.core.note.app.usecase.ExportVaultUseCase.VaultExport;
import com.moneylog.core.note.app.usecase.ExportVaultUseCaseImpl;
import com.moneylog.core.note.app.usecase.ImportVaultUseCase.ConflictMode;
import com.moneylog.core.note.app.usecase.ImportVaultUseCase.ImportReport;
import com.moneylog.core.note.app.usecase.ImportVaultUseCase.ImportVaultCommand;
import com.moneylog.core.note.app.usecase.ImportVaultUseCaseImpl;
import com.moneylog.core.note.app.usecase.TrashNoteUseCase.TrashNoteCommand;
import com.moneylog.core.note.app.usecase.TrashNoteUseCaseImpl;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.note.exception.NoteExceptions.VaultExportTooLargeException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLimitReachedException;
import com.moneylog.core.note.exception.NoteExceptions.VaultImportInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.VaultImportTooLargeException;
import com.moneylog.core.note.util.InMemoryNoteGateway;
import com.moneylog.core.note.util.InMemoryNoteTargetGateway;
import com.moneylog.core.note.util.NoteTestKit;
import com.moneylog.core.note.util.NoteTestKit.InMemoryIdempotency;
import com.moneylog.core.note.util.NoteTestKit.Zip;

@DisplayName("Exportar e importar o vault (T112, S-157, S-162, S-166)")
class VaultImportExportTest {

    private static final UUID TRANSACTION = NoteTestKit.id(900);
    private static final UUID GOAL = NoteTestKit.id(901);

    private InMemoryNoteGateway notes;
    private InMemoryNoteTargetGateway targets;
    private ImportVaultUseCaseImpl importer;
    private ExportVaultUseCaseImpl exporter;
    private CreateNoteUseCaseImpl create;
    private TrashNoteUseCaseImpl trash;

    @BeforeEach
    void setUp() {
        notes = new InMemoryNoteGateway(NOW);
        targets = new InMemoryNoteTargetGateway(notes);
        targets.add(USER, new TargetRef(NoteLinkType.TRANSACTION, TRANSACTION), new TargetInfo("Notebook", TargetStatus.ACTIVE));
        targets.add(USER, new TargetRef(NoteLinkType.GOAL, GOAL), new TargetInfo("Reserva", TargetStatus.ACTIVE));
        importer = new ImportVaultUseCaseImpl(notes, targets, NoteTestKit.ids());
        exporter = new ExportVaultUseCaseImpl(notes, NoteTestKit.time());
        NoteViewAssembler views = new NoteViewAssembler(targets);
        create = new CreateNoteUseCaseImpl(notes, new ExplicitLinkValidator(targets), views, new InMemoryIdempotency(), NoteTestKit.ids(), NoteTestKit.time());
        trash = new TrashNoteUseCaseImpl(notes, views, NoteTestKit.time());
    }

    private ImportReport importZip(Zip zip, ConflictMode mode) {
        return importer.handle(new ImportVaultCommand(USER, zip.source(), mode));
    }

    private Note byPath(String path) {
        return notes.all().stream().filter(n -> n.path().value().equals(path)).findFirst().orElseThrow();
    }

    private Map<String, String> exported() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (VaultExport export = exporter.handle(new ExportVaultQuery(USER))) {
            export.writeTo(out);
            assertThat(export.sizeBytes()).isEqualTo(out.size());
        }
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    @DisplayName("CA2/CA3: importa as notas, resolve os wikilinks entre elas, lista o que ignorou (foto.png) e mantém ![[foto.png]] no texto como link pendente")
    void deveImportar() {
        ImportReport report = importZip(new Zip().add("reviews/2026-10.md", "Veja [[MacBook]] e ![[foto.png]] #revisao-mensal").add("MacBook.md", "notebook")
                .add("foto.png", new byte[] {1}).add(".obsidian/app.json", "{}"), ConflictMode.SKIP);
        assertThat(report.created()).isEqualTo(2);
        assertThat(report.skipped()).isZero();
        assertThat(report.ignoredFiles()).containsExactly("foto.png", ".obsidian/app.json");
        assertThat(report.pendingLinks()).isEqualTo(1);
        Note review = byPath("reviews/2026-10.md");
        assertThat(review.body()).isEqualTo("Veja [[MacBook]] e ![[foto.png]] #revisao-mensal");
        assertThat(review.tags()).containsExactly("revisao-mensal");
        assertThat(review.wikilinks()).extracting(NoteLink::state).containsExactly(NoteLink.State.RESOLVED, NoteLink.State.PENDING);
        assertThat(review.wikilinks().get(0).targetId()).isEqualTo(byPath("MacBook.md").id());
    }

    @Test
    @DisplayName("CA5/CA6: conflito de caminho: SKIP pula e relata; reimportar o mesmo zip com SKIP não duplica; RENAME cria 'nome (2).md', '(3)' e relata")
    void deveTratarConflitos() {
        importZip(new Zip().add("a.md", "original"), ConflictMode.SKIP);
        ImportReport again = importZip(new Zip().add("A.md", "outro").add("b.md", "novo"), ConflictMode.SKIP);
        assertThat(again.created()).isEqualTo(1);
        assertThat(again.skipped()).isEqualTo(1);
        assertThat(again.skippedFiles()).containsExactly("A.md");
        assertThat(notes.all()).hasSize(2);
        assertThat(byPath("a.md").body()).isEqualTo("original");

        ImportReport renamed = importZip(new Zip().add("a.md", "r1").add("pasta/a.md", "r2").add("A.md", "r3"), ConflictMode.RENAME);
        assertThat(renamed.created()).isEqualTo(3);
        assertThat(renamed.renamedFiles()).extracting(r -> r.from() + "->" + r.to()).containsExactly("a.md->a (2).md", "A.md->A (3).md");
        assertThat(notes.all()).extracting(n -> n.path().value()).containsExactlyInAnyOrder("a.md", "b.md", "a (2).md", "A (3).md", "pasta/a.md");
    }

    @Test
    @DisplayName("CA7 (S-273): frontmatter malicioso não é frontmatter: a nota entra com o texto inteiro como corpo, sem aviso")
    void deveManterFrontmatterMalicioso() {
        String hostile = "---\nx: !!java.lang.ProcessBuilder [[\"calc\"]]\n---\ncorpo [[a]]";
        String bomb = "---\na: &a [*a]\n---\ncorpo2";
        ImportReport report = importZip(new Zip().add("m.md", hostile).add("bomba.md", bomb), ConflictMode.SKIP);
        assertThat(report.created()).isEqualTo(2);
        assertThat(report.warnings()).isEmpty();
        assertThat(byPath("m.md").body()).isEqualTo(hostile);
        assertThat(byPath("bomba.md").body()).isEqualTo(bomb);
    }

    @Test
    @DisplayName("CA2: moneylog.links: alvo que existe vira vínculo; alvo inexistente ou id ilegível vira pendente e entra em pendingLinks; tipo desconhecido é descartado com aviso")
    void deveLerOsVinculos() {
        UUID missing = UUID.fromString("deadbeef-0000-4000-8000-000000000000");
        String text = "---\ntags: [x]\nmoneylog:\n  links:\n    - { type: TRANSACTION, id: " + TRANSACTION + " }\n    - { type: GOAL, id: " + missing + " }\n"
                + "    - { type: PERSON, id: nao-e-uuid }\n    - { type: MONTHLY_SUMMARY, key: 2026-10 }\n    - { type: MONTHLY_SUMMARY, key: 2026-13 }\n"
                + "    - { type: PLANET, id: x }\n    - { type: GOAL }\n---\ncorpo";
        ImportReport report = importZip(new Zip().add("n.md", text), ConflictMode.SKIP);
        Note note = byPath("n.md");
        assertThat(note.body()).isEqualTo("---\ntags: [x]\n---\ncorpo");
        assertThat(note.explicitLinks()).extracting(NoteLink::type, NoteLink::state).containsExactly(
                org.assertj.core.groups.Tuple.tuple(NoteLinkType.TRANSACTION, NoteLink.State.RESOLVED),
                org.assertj.core.groups.Tuple.tuple(NoteLinkType.GOAL, NoteLink.State.PENDING),
                org.assertj.core.groups.Tuple.tuple(NoteLinkType.PERSON, NoteLink.State.PENDING),
                org.assertj.core.groups.Tuple.tuple(NoteLinkType.MONTHLY_SUMMARY, NoteLink.State.RESOLVED),
                org.assertj.core.groups.Tuple.tuple(NoteLinkType.MONTHLY_SUMMARY, NoteLink.State.PENDING));
        assertThat(note.explicitLinks().get(1).text()).isEqualTo(missing.toString());
        assertThat(report.pendingLinks()).isEqualTo(3);
        assertThat(report.warnings()).extracting(w -> w.code()).containsExactly("LINK_IGNORED", "LINK_IGNORED");
    }

    @Test
    @DisplayName("vínculo de entidade de OUTRO usuário vira pendente (não vaza existência)")
    void deveTratarAlvoDeOutroUsuarioComoPendente() {
        InMemoryNoteTargetGateway other = new InMemoryNoteTargetGateway();
        other.add(OTHER_USER, new TargetRef(NoteLinkType.GOAL, NoteTestKit.id(5)), new TargetInfo("alheia", TargetStatus.ACTIVE));
        ImportVaultUseCaseImpl otherImporter = new ImportVaultUseCaseImpl(notes, other, NoteTestKit.ids());
        otherImporter.handle(new ImportVaultCommand(USER, new Zip().add("n.md", "---\nmoneylog:\n  links:\n    - { type: GOAL, id: " + NoteTestKit.id(5)
                + " }\n---\n").source(), ConflictMode.SKIP));
        assertThat(byPath("n.md").explicitLinks().get(0).state()).isEqualTo(NoteLink.State.PENDING);
    }

    @Test
    @DisplayName("vínculos NOTE por caminho resolvem depois que todas as notas existem, em qualquer ordem; caminho inexistente fica pendente")
    void deveResolverVinculosDeNota() {
        ImportReport report = importZip(new Zip().add("a.md", "---\nmoneylog:\n  links:\n    - { type: NOTE, path: b.md }\n    - { type: NOTE, path: nada.md }\n"
                + "    - { type: NOTE, path: a.md }\n---\n").add("b.md", "x"), ConflictMode.SKIP);
        Note a = byPath("a.md");
        assertThat(a.explicitLinks()).extracting(NoteLink::state).containsExactly(NoteLink.State.RESOLVED, NoteLink.State.PENDING, NoteLink.State.PENDING);
        assertThat(a.explicitLinks().get(0).targetId()).isEqualTo(byPath("b.md").id());
        assertThat(report.pendingLinks()).isEqualTo(2);
    }

    @Test
    @DisplayName("BOM é removido; arquivo que não é UTF-8, com controle ou grande demais é ignorado com aviso; o resto entra")
    void deveIgnorarArquivosRuins() {
        ImportReport report = importZip(new Zip().add("bom.md", "\uFEFFcom bom").add("latin1.md", new byte[] {'a', (byte) 0xE9, 'b'}).add("ctl.md", "a\u0001b")
                .add("ok.md", "ok").add("so-front.md", "---\nmoneylog:\n  links: []\n---\n"), ConflictMode.SKIP);
        assertThat(report.created()).isEqualTo(3);
        assertThat(byPath("bom.md").body()).isEqualTo("com bom");
        assertThat(byPath("so-front.md").body()).isEmpty();
        assertThat(report.ignoredFiles()).containsExactlyInAnyOrder("latin1.md", "ctl.md");
        assertThat(report.warnings()).extracting(w -> w.code()).containsExactlyInAnyOrder("NOTE_INVALID_ENCODING", "TEXT_INVALID_CHARACTERS");
    }

    @Test
    @DisplayName("o relatório avisa de nota grande e de nome sem suporte, e as notas boas entram")
    void deveAvisarDeNotaGrandeENomeSemSuporte() {
        ImportReport report = importZip(new Zip().add("grande.md", "a".repeat(NoteLimits.NOTE_MAX_BYTES + 1)).add("con.md", "x").add("ok.md", "x"), ConflictMode.SKIP);
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.warnings()).extracting(w -> w.code()).containsExactly("NOTE_TOO_LARGE", "NOTE_PATH_INVALID");
    }

    @Test
    @DisplayName("CA4: zip hostil ou acima dos limites: exceção e NADA gravado (nem as notas boas que vinham antes)")
    void naoDeveGravarNadaSeOZipForRecusado() {
        Zip hostile = new Zip().add("boa.md", "x").add("../../etc/passwd.md", "x");
        assertThatThrownBy(() -> importZip(hostile, ConflictMode.SKIP)).isInstanceOf(VaultImportInvalidException.class);
        assertThat(notes.all()).isEmpty();
        assertThat(notes.saves()).isZero();
        Zip many = new Zip();
        for (int i = 0; i <= NoteLimits.IMPORT_MAX_ENTRIES; i++) {
            many.add("n" + i + ".md", "");
        }
        assertThatThrownBy(() -> importZip(many, ConflictMode.SKIP)).isInstanceOf(VaultImportTooLargeException.class);
        assertThat(notes.saves()).isZero();
    }

    @Test
    @DisplayName("limite de 5.000 notas: estourar no meio da importação lança NOTE_LIMIT_REACHED (a transação desfaz tudo)")
    void deveRespeitarOLimiteDeNotas() {
        InMemoryNoteGateway nearLimit = new InMemoryNoteGateway(NOW) {
            @Override
            public int countAll(UUID userId) {
                return NoteLimits.MAX_NOTES - 1;
            }
        };
        ImportVaultUseCaseImpl limited = new ImportVaultUseCaseImpl(nearLimit, targets, NoteTestKit.ids());
        assertThatThrownBy(() -> limited.handle(new ImportVaultCommand(USER, new Zip().add("a.md", "x").add("b.md", "y").source(), ConflictMode.SKIP)))
                .isInstanceOf(NoteLimitReachedException.class);
    }

    @Test
    @DisplayName("S-271 (A2): vínculo com controle (\\0, ESC) ou formatação bidi (RLO) em type, id, key ou path é descartado com LINK_IGNORED; a nota entra e nada vira 500")
    void deveDescartarVinculoComCaractereInvalido() {
        String text = "---\nmoneylog:\n  links:\n    - { type: GOAL, id: \"a\\x00b\" }\n    - { type: GOAL, id: \"a\\x1bb\" }\n"
                + "    - { type: GOAL, id: \"a\\u202eb\" }\n    - { type: NOTE, path: \"x\\x00.md\" }\n    - { type: NOTE, path: \"x\\u202e.md\" }\n"
                + "    - { type: MONTHLY_SUMMARY, key: \"2026-1\\x000\" }\n    - { type: \"GO\\x1bAL\", id: x }\n    - { type: GOAL, id: " + GOAL + " }\n---\ncorpo";
        ImportReport report = importZip(new Zip().add("n.md", text), ConflictMode.SKIP);
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.warnings()).hasSize(7).allSatisfy(w -> {
            assertThat(w.code()).isEqualTo("LINK_IGNORED");
            assertThat(w.message()).doesNotContainPattern("[\\p{Cntrl}\\p{Cf}]");
        });
        Note note = byPath("n.md");
        assertThat(note.explicitLinks()).extracting(NoteLink::type, NoteLink::targetId).containsExactly(org.assertj.core.groups.Tuple.tuple(NoteLinkType.GOAL, GOAL));
        assertThat(note.body()).isEqualTo("corpo");
    }

    @Test
    @DisplayName("S-271 (A2): título do frontmatter com controle ou bidi: a nota é ignorada com aviso (nunca 500) e as demais entram")
    void deveIgnorarNotaComTituloInvalido() {
        ImportReport report = importZip(new Zip().add("a.md", "---\ntitle: \"a\\x00b\"\n---\ncorpo").add("b.md", "---\ntitle: \"a\\u202eb\"\n---\n")
                .add("c.md", "---\ntitle: \"a\\x1bb\"\n---\n").add("ok.md", "---\ntitle: Normal\n---\n"), ConflictMode.SKIP);
        assertThat(report.created()).isEqualTo(1);
        assertThat(report.ignoredFiles()).containsExactly("a.md", "b.md", "c.md");
        assertThat(report.warnings()).extracting(w -> w.code()).containsOnly("TEXT_INVALID_CHARACTERS");
    }

    @Test
    @DisplayName("S-271 (BUG-QA-12): nota com mais de 500 wikilinks distintos é pulada com NOTE_TOO_MANY_LINKS; as outras entram e nenhum link fica sem índice")
    void devePularNotaComLinksDemais() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= NoteLimits.MAX_WIKILINKS; i++) {
            many.append("[[n").append(i).append("]] ");
        }
        StringBuilder exact = new StringBuilder();
        for (int i = 0; i < NoteLimits.MAX_WIKILINKS; i++) {
            exact.append("[[n").append(i).append("]] ");
        }
        ImportReport report = importZip(new Zip().add("demais.md", many.toString()).add("exato.md", exact.toString()).add("ok.md", "[[exato]]"), ConflictMode.SKIP);
        assertThat(report.created()).isEqualTo(2);
        assertThat(report.ignoredFiles()).containsExactly("demais.md");
        assertThat(report.warnings()).extracting(w -> w.path() + ":" + w.code()).containsExactly("demais.md:NOTE_TOO_MANY_LINKS");
        assertThat(byPath("exato.md").wikilinks()).hasSize(NoteLimits.MAX_WIKILINKS);
    }

    @Test
    @DisplayName("S-273 (BUG-QA-03/06, M6): texto que começa com --- sem ser frontmatter entra byte a byte; chave moneylog entre aspas é lida e tirada; frontmatter vazio volta igual")
    void deveTratarFrontmatterEmCasosDeBorda() {
        String rule = "---\nAta da reunião\n---\nConteúdo depois da régua";
        String quoted = "---\ntitle: x\n\"moneylog\":\n  links:\n    - { type: GOAL, id: " + GOAL + " }\n---\ncorpo";
        String spaced = "---\nmoneylog :\n  links:\n    - { type: MONTHLY_SUMMARY, key: 2026-10 }\n---\ncorpo2";
        String flow = "---\n{ moneylog: { links: [] }, a: 1 }\n---\ncorpo3";
        ImportReport report = importZip(new Zip().add("regua.md", rule).add("aspas.md", quoted).add("espaco.md", spaced).add("fluxo.md", flow), ConflictMode.SKIP);
        assertThat(report.created()).isEqualTo(4);
        assertThat(byPath("regua.md").body()).isEqualTo(rule);
        assertThat(byPath("aspas.md").body()).isEqualTo("---\ntitle: x\n---\ncorpo");
        assertThat(byPath("aspas.md").explicitLinks()).hasSize(1);
        assertThat(byPath("espaco.md").body()).isEqualTo("corpo2");
        assertThat(byPath("espaco.md").explicitLinks()).hasSize(1);
        assertThat(byPath("fluxo.md").body()).isEqualTo("corpo3");
        assertThat(report.warnings()).extracting(w -> w.path() + ":" + w.code()).containsExactly("fluxo.md:FRONTMATTER_INVALID");
    }

    @Test
    @DisplayName("S-273 (BUG-QA-06): frontmatter vazio ou em branco e régua com vínculo explícito sobrevivem à exportação e à importação, byte a byte")
    void deveFecharORoundTripDeFrontmatterVazio() throws IOException {
        String[] texts = {"---\n---\ncorpo", "---\n---\n", "---\n\n---\ncorpo", "---\nAta\n---\ncorpo", "---\r\n---\r\ncorpo\r\n"};
        for (int i = 0; i < texts.length; i++) {
            setUp();
            create.handle(new CreateNoteCommand(USER, "n.md", texts[i], List.of(new ExplicitLinkSpec(NoteLinkType.MONTHLY_SUMMARY, null, "2026-10")), null));
            Map<String, String> files = exported();
            setUp();
            ImportReport report = importZip(zipOf(files), ConflictMode.SKIP);
            assertThat(report.created()).as(texts[i]).isEqualTo(1);
            assertThat(byPath("n.md").body()).as(texts[i]).isEqualTo(texts[i]);
            assertThat(byPath("n.md").explicitLinks()).as(texts[i]).hasSize(1);
        }
    }

    private static Zip zipOf(Map<String, String> files) {
        Zip zip = new Zip();
        files.forEach(zip::add);
        return zip;
    }

    @Test
    @DisplayName("S-278: o recálculo parcial da importação também resolve os wikilinks das notas novas para notas que JÁ existiam no vault (fora do zip)")
    void deveResolverLinksParaNotasQueJaExistiam() {
        var existing = create.handle(new CreateNoteCommand(USER, "pasta/Antiga.md", "x", List.of(), null));
        create.handle(new CreateNoteCommand(USER, "pendente.md", "[[Nova]]", List.of(), null));
        java.util.concurrent.atomic.AtomicLong next = new java.util.concurrent.atomic.AtomicLong(500);
        ImportVaultUseCaseImpl other = new ImportVaultUseCaseImpl(notes, targets, () -> NoteTestKit.id(next.incrementAndGet()));
        ImportReport report = other.handle(new ImportVaultCommand(USER, new Zip().add("nova.md", "veja [[Antiga]] e [[inexistente]]").source(), ConflictMode.SKIP));
        assertThat(report.created()).isEqualTo(1);
        assertThat(byPath("nova.md").wikilinks()).extracting(NoteLink::targetId).containsExactly(existing.note().value().id(), null);
        assertThat(report.pendingLinks()).isEqualTo(1);
        assertThat(byPath("pendente.md").wikilinks().get(0).targetId()).isEqualTo(byPath("nova.md").id());
    }

    @Test
    @DisplayName("M1: nomes e mensagens do relatório são cortados em 200 caracteres (um nome de entrada pode ter 64 KB); o nome é cortado por ponto de código")
    void deveCortarNomesDoRelatorio() {
        String longName = "x".repeat(300) + "😀";
        ImportReport report = importZip(new Zip().add(longName + ".png", "x").add("a".repeat(250) + "?.md", "x").add("ok.md", "x"), ConflictMode.SKIP);
        assertThat(report.ignoredFiles()).hasSize(2).allSatisfy(n -> assertThat(n.codePointCount(0, n.length())).isLessThanOrEqualTo(200));
        assertThat(report.warnings()).allSatisfy(w -> {
            assertThat(w.path().codePointCount(0, w.path().length())).isLessThanOrEqualTo(200);
            assertThat(w.message().codePointCount(0, w.message().length())).isLessThanOrEqualTo(200);
        });
    }

    @Test
    @DisplayName("M3: RENAME avisa em cada nota renomeada (NOTE_RENAMED) que reenviar o mesmo zip cria outra cópia")
    void deveAvisarQueRenameNaoEIdempotente() {
        importZip(new Zip().add("a.md", "um"), ConflictMode.SKIP);
        ImportReport report = importZip(new Zip().add("a.md", "dois"), ConflictMode.RENAME);
        assertThat(report.renamedFiles()).extracting(r -> r.to()).containsExactly("a (2).md");
        assertThat(report.warnings()).extracting(w -> w.code()).containsExactly("NOTE_RENAMED");
        assertThat(report.warnings().get(0).message()).contains("a (2).md").contains("SKIP");
        assertThat(importZip(new Zip().add("a.md", "dois"), ConflictMode.SKIP).warnings()).isEmpty();
    }

    @Test
    @DisplayName("BUG-QA-04: nome sem flag UTF-8 importa como CP437 com NAME_ENCODING_GUESSED; as demais notas entram")
    void deveAvisarDeNomeAdivinhado() throws IOException {
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(raw, java.nio.charset.Charset.forName("IBM437"))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("café.md"));
            zip.write("x".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        ImportReport report = importer.handle(new ImportVaultCommand(USER, NoteTestKit.source(raw.toByteArray()), ConflictMode.SKIP));
        assertThat(report.created()).isEqualTo(1);
        assertThat(byPath("café.md").path().value()).isEqualTo("café.md");
        assertThat(report.warnings()).extracting(w -> w.code()).containsExactly("NAME_ENCODING_GUESSED");
    }

    @Test
    @DisplayName("CA1: exporta um .md por nota ativa, no caminho dela, com o corpo intacto; a lixeira não vai; nome do arquivo usa o dia do usuário")
    void deveExportar() throws IOException {
        create.handle(new CreateNoteCommand(USER, "reviews/2026-10.md", "# Outubro\nveja [[MacBook]] #revisao-mensal", List.of(), null));
        create.handle(new CreateNoteCommand(USER, "MacBook.md", "---\ntitle: Meu MacBook\n---\ncorpo", List.of(), null));
        var gone = create.handle(new CreateNoteCommand(USER, "lixo.md", "x", List.of(), null));
        trash.handle(new TrashNoteCommand(USER, gone.note().value().id()));
        try (VaultExport export = exporter.handle(new ExportVaultQuery(USER))) {
            assertThat(export.fileName()).isEqualTo("moneylog-vault-2026-10-05.zip");
            assertThat(export.noteCount()).isEqualTo(2);
        }
        Map<String, String> files = exported();
        assertThat(files).containsOnlyKeys("reviews/2026-10.md", "MacBook.md");
        assertThat(files.get("reviews/2026-10.md")).isEqualTo("# Outubro\nveja [[MacBook]] #revisao-mensal");
        assertThat(files.get("MacBook.md")).isEqualTo("---\ntitle: Meu MacBook\n---\ncorpo");
    }

    @Test
    @DisplayName("CA1: vínculos explícitos saem em moneylog.links (criando o frontmatter ou acrescentando ao existente); NOTE vai por caminho")
    void deveExportarOsVinculos() throws IOException {
        var b = create.handle(new CreateNoteCommand(USER, "b.md", "x", List.of(), null));
        create.handle(new CreateNoteCommand(USER, "a.md", "corpo", List.of(new ExplicitLinkSpec(NoteLinkType.TRANSACTION, TRANSACTION, null),
                new ExplicitLinkSpec(NoteLinkType.MONTHLY_SUMMARY, null, "2026-10"),
                new ExplicitLinkSpec(NoteLinkType.NOTE, b.note().value().id(), null)), null));
        create.handle(new CreateNoteCommand(USER, "c.md", "---\ntags: [x]\n---\ncorpo", List.of(new ExplicitLinkSpec(NoteLinkType.GOAL, GOAL, null)), null));
        Map<String, String> files = exported();
        assertThat(files.get("a.md")).isEqualTo("---\nmoneylog:\n  links:\n    - { type: TRANSACTION, id: " + TRANSACTION + " }\n"
                + "    - { type: MONTHLY_SUMMARY, key: 2026-10 }\n    - { type: NOTE, path: \"b.md\" }\n---\ncorpo");
        assertThat(files.get("c.md")).isEqualTo("---\ntags: [x]\nmoneylog:\n  links:\n    - { type: GOAL, id: " + GOAL + " }\n---\ncorpo");
        assertThat(files.get("b.md")).isEqualTo("x");
    }

    @Test
    @DisplayName("CA2: exportar e importar em outro usuário recria as mesmas notas, tags e vínculos; alvo que não existe lá vira pendente")
    void deveFazerRoundTrip() throws IOException {
        var b = create.handle(new CreateNoteCommand(USER, "pasta/b.md", "x #tag-b", List.of(), null));
        create.handle(new CreateNoteCommand(USER, "a.md", "veja [[b]] e [[Inexistente]] #tag-a", List.of(new ExplicitLinkSpec(NoteLinkType.TRANSACTION, TRANSACTION, null),
                new ExplicitLinkSpec(NoteLinkType.GOAL, GOAL, null), new ExplicitLinkSpec(NoteLinkType.MONTHLY_SUMMARY, null, "2026-10"),
                new ExplicitLinkSpec(NoteLinkType.NOTE, b.note().value().id(), null)), null));
        VaultExport export = exporter.handle(new ExportVaultQuery(USER));
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        export.writeTo(zip);

        InMemoryNoteGateway otherVault = new InMemoryNoteGateway(NOW);
        InMemoryNoteTargetGateway otherTargets = new InMemoryNoteTargetGateway();
        otherTargets.add(OTHER_USER, new TargetRef(NoteLinkType.GOAL, GOAL), new TargetInfo("Reserva", TargetStatus.ACTIVE));
        ImportReport report = new ImportVaultUseCaseImpl(otherVault, otherTargets, NoteTestKit.ids())
                .handle(new ImportVaultCommand(OTHER_USER, NoteTestKit.source(zip.toByteArray()), ConflictMode.SKIP));
        assertThat(report.created()).isEqualTo(2);
        Note a = otherVault.all().stream().filter(n -> n.path().value().equals("a.md")).findFirst().orElseThrow();
        Note bb = otherVault.all().stream().filter(n -> n.path().value().equals("pasta/b.md")).findFirst().orElseThrow();
        assertThat(a.body()).isEqualTo("veja [[b]] e [[Inexistente]] #tag-a");
        assertThat(a.tags()).containsExactly("tag-a");
        assertThat(bb.tags()).containsExactly("tag-b");
        assertThat(a.wikilinks()).extracting(NoteLink::state).containsExactly(NoteLink.State.RESOLVED, NoteLink.State.PENDING);
        assertThat(a.explicitLinks()).extracting(NoteLink::type, NoteLink::state).containsExactly(
                org.assertj.core.groups.Tuple.tuple(NoteLinkType.TRANSACTION, NoteLink.State.PENDING),
                org.assertj.core.groups.Tuple.tuple(NoteLinkType.GOAL, NoteLink.State.RESOLVED),
                org.assertj.core.groups.Tuple.tuple(NoteLinkType.MONTHLY_SUMMARY, NoteLink.State.RESOLVED),
                org.assertj.core.groups.Tuple.tuple(NoteLinkType.NOTE, NoteLink.State.RESOLVED));
        assertThat(a.explicitLinks().get(3).targetId()).isEqualTo(bb.id());
        assertThat(report.pendingLinks()).isEqualTo(2);
    }

    @Test
    @DisplayName("exportar vault vazio gera um zip válido sem entradas; muitas notas saem em lotes sem perder nenhuma")
    void deveExportarVazioEEmLotes() throws IOException {
        assertThat(exported()).isEmpty();
        for (int i = 0; i < 120; i++) {
            create.handle(new CreateNoteCommand(USER, "n" + i + ".md", "corpo " + i, List.of(), null));
        }
        assertThat(exported()).hasSize(120);
    }

    @Test
    @DisplayName("S-275 (BUG-QA-07): vínculo explícito para nota na lixeira vai no export com state: trashed; a importação o mantém pendente")
    void deveExportarVinculoParaNotaDaLixeira() throws IOException {
        var gone = create.handle(new CreateNoteCommand(USER, "lixo.md", "x", List.of(), null));
        create.handle(new CreateNoteCommand(USER, "fica.md", "corpo", List.of(new ExplicitLinkSpec(NoteLinkType.NOTE, gone.note().value().id(), null)), null));
        trash.handle(new TrashNoteCommand(USER, gone.note().value().id()));
        assertThat(byPath("fica.md").explicitLinks()).hasSize(1);
        Map<String, String> files = exported();
        assertThat(files).containsOnlyKeys("fica.md");
        assertThat(files.get("fica.md")).contains("type: NOTE").contains("path: \"lixo.md\"").contains("state: trashed");
        setUp();
        ImportReport report = importZip(zipOf(files), ConflictMode.SKIP);
        assertThat(report.created()).isEqualTo(1);
        assertThat(byPath("fica.md").body()).isEqualTo("corpo");
        assertThat(byPath("fica.md").explicitLinks()).extracting(NoteLink::state).containsExactly(NoteLink.State.PENDING);
        assertThat(byPath("fica.md").explicitLinks().get(0).text()).isEqualTo("lixo.md");
    }

    @Test
    @DisplayName("S-272 (BUG-QA-11): a exportação recusa com EXPORT_TOO_LARGE o que a importação não aceitaria e não deixa arquivo temporário")
    void deveRecusarExportacaoQueAImportacaoRecusaria() throws IOException {
        java.nio.file.Path tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        for (int i = 0; i < 4; i++) {
            create.handle(new CreateNoteCommand(USER, "n" + i + ".md", "a".repeat(NoteLimits.NOTE_MAX_BYTES), List.of(), null));
        }
        long before = exportDirs(tmp);
        ExportVaultUseCaseImpl tooBig = new ExportVaultUseCaseImpl(new RepeatingGateway(NOW, 1_600), NoteTestKit.time());
        assertThatThrownBy(() -> tooBig.handle(new ExportVaultQuery(USER))).isInstanceOf(VaultExportTooLargeException.class).extracting("code")
                .isEqualTo("EXPORT_TOO_LARGE");
        assertThat(exportDirs(tmp)).isEqualTo(before);
        assertThat(exported()).hasSize(4);
        assertThat(exportDirs(tmp)).isEqualTo(before);
    }

    @Test
    @DisplayName("M9: falha no meio da montagem do zip é um erro (nunca um zip cortado entregue como 200) e não deixa arquivo temporário")
    void falhaNoMeioDaExportacaoEDetectavel() throws IOException {
        java.nio.file.Path tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        long before = exportDirs(tmp);
        InMemoryNoteGateway failing = new RepeatingGateway(NOW, 120) {
            @Override
            public List<Versioned<Note>> findActiveBatch(UUID userId, UUID afterId, int limit) {
                if (afterId != null) {
                    throw new IllegalStateException("o banco caiu no segundo lote");
                }
                return super.findActiveBatch(userId, afterId, limit);
            }
        };
        ExportVaultUseCaseImpl broken = new ExportVaultUseCaseImpl(failing, NoteTestKit.time());
        assertThatThrownBy(() -> broken.handle(new ExportVaultQuery(USER))).isInstanceOf(IllegalStateException.class).hasMessageContaining("segundo lote");
        assertThat(exportDirs(tmp)).isEqualTo(before);
    }

    private static class RepeatingGateway extends InMemoryNoteGateway {
        private final int copies;

        RepeatingGateway(java.time.Instant now, int copies) {
            super(now);
            this.copies = copies;
        }

        @Override
        public List<WikilinkResolver.Entry> activeIndex(UUID userId) {
            return List.of();
        }

        @Override
        public List<Versioned<Note>> findActiveBatch(UUID userId, UUID afterId, int limit) {
            int start = afterId == null ? 0 : (int) afterId.getLeastSignificantBits();
            List<Versioned<Note>> out = new java.util.ArrayList<>();
            for (int i = start; i < Math.min(copies, start + limit); i++) {
                Note note = Note.create(NoteTestKit.id(i + 1), userId, NotePath.of("n" + i + ".md"), "a".repeat(NoteLimits.NOTE_MAX_BYTES), List.of(),
                        new WikilinkResolver(List.of()));
                out.add(new Versioned<>(note, 0, NOW, NOW));
            }
            return out;
        }
    }

    private static long exportDirs(java.nio.file.Path tmp) throws IOException {
        try (var stream = java.nio.file.Files.list(tmp)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("moneylog-export-")).count();
        }
    }
}
