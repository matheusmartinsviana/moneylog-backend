package com.moneylog.core.note.app;

import static com.moneylog.core.note.util.NoteTestKit.NOW;
import static com.moneylog.core.note.util.NoteTestKit.OTHER_USER;
import static com.moneylog.core.note.util.NoteTestKit.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetInfo;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetRef;
import com.moneylog.core.note.app.gateways.NoteTargetGateway.TargetStatus;
import com.moneylog.core.note.app.usecase.CreateNoteUseCase.CreateNoteCommand;
import com.moneylog.core.note.app.usecase.CreateNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.EmptyTrashUseCase.EmptyTrashCommand;
import com.moneylog.core.note.app.usecase.EmptyTrashUseCaseImpl;
import com.moneylog.core.note.app.usecase.GetNoteUseCase.GetNoteQuery;
import com.moneylog.core.note.app.usecase.GetNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.ListBacklinksUseCase.ListBacklinksQuery;
import com.moneylog.core.note.app.usecase.ListBacklinksUseCaseImpl;
import com.moneylog.core.note.app.usecase.ListNotesUseCaseImpl;
import com.moneylog.core.note.app.usecase.ListTagsUseCase.ListTagsQuery;
import com.moneylog.core.note.app.usecase.ListTagsUseCaseImpl;
import com.moneylog.core.note.app.usecase.MoveNoteUseCase.MoveNoteCommand;
import com.moneylog.core.note.app.usecase.MoveNoteUseCase.MoveNoteResult;
import com.moneylog.core.note.app.usecase.MoveNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.RestoreNoteUseCase.RestoreNoteCommand;
import com.moneylog.core.note.app.usecase.RestoreNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.SetNoteLinksUseCase.SetNoteLinksCommand;
import com.moneylog.core.note.app.usecase.SetNoteLinksUseCaseImpl;
import com.moneylog.core.note.app.usecase.TrashNoteUseCase.TrashNoteCommand;
import com.moneylog.core.note.app.usecase.TrashNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.UpdateNoteUseCase.UpdateNoteCommand;
import com.moneylog.core.note.app.usecase.UpdateNoteUseCaseImpl;
import com.moneylog.core.note.domain.LinkOrigin;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.core.note.exception.NoteExceptions.NoteLimitReachedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLinkInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLinkLimitReachedException;
import com.moneylog.core.note.exception.NoteExceptions.NoteLinkTargetNotFoundException;
import com.moneylog.core.note.exception.NoteExceptions.NoteNotFoundException;
import com.moneylog.core.note.exception.NoteExceptions.NotePathInvalidException;
import com.moneylog.core.note.exception.NoteExceptions.NotePathTakenException;
import com.moneylog.core.note.exception.NoteExceptions.NoteTrashedException;
import com.moneylog.core.note.util.InMemoryNoteGateway;
import com.moneylog.core.note.util.InMemoryNoteTargetGateway;
import com.moneylog.core.note.util.NoteTestKit;
import com.moneylog.core.note.util.NoteTestKit.InMemoryIdempotency;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;

@DisplayName("Casos de uso das notas (T108 a T110): criar, editar, mover com reescrita, lixeira e vínculos")
class NoteUseCasesTest {

    private static final UUID TRANSACTION = NoteTestKit.id(900);
    private static final UUID GOAL = NoteTestKit.id(901);
    private static final UUID KEY1 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private InMemoryNoteGateway notes;
    private InMemoryNoteTargetGateway targets;
    private NoteViewAssembler views;
    private ExplicitLinkValidator validator;
    private InMemoryIdempotency idempotency;
    private CreateNoteUseCaseImpl create;
    private UpdateNoteUseCaseImpl update;
    private MoveNoteUseCaseImpl move;
    private TrashNoteUseCaseImpl trash;
    private RestoreNoteUseCaseImpl restore;
    private SetNoteLinksUseCaseImpl setLinks;

    @BeforeEach
    void setUp() {
        notes = new InMemoryNoteGateway(NOW);
        targets = new InMemoryNoteTargetGateway(notes);
        targets.add(USER, new TargetRef(NoteLinkType.TRANSACTION, TRANSACTION), new TargetInfo("Notebook", TargetStatus.ACTIVE));
        targets.add(USER, new TargetRef(NoteLinkType.GOAL, GOAL), new TargetInfo("Reserva", TargetStatus.ARCHIVED));
        views = new NoteViewAssembler(targets);
        validator = new ExplicitLinkValidator(targets);
        idempotency = new InMemoryIdempotency();
        wire(notes);
    }

    private void wire(InMemoryNoteGateway gateway) {
        notes = gateway;
        views = new NoteViewAssembler(targets);
        create = new CreateNoteUseCaseImpl(gateway, validator, views, idempotency, NoteTestKit.ids(), NoteTestKit.time());
        update = new UpdateNoteUseCaseImpl(gateway, views);
        move = new MoveNoteUseCaseImpl(gateway, views);
        trash = new TrashNoteUseCaseImpl(gateway, views, NoteTestKit.time());
        restore = new RestoreNoteUseCaseImpl(gateway, views);
        setLinks = new SetNoteLinksUseCaseImpl(gateway, validator, views);
    }

    private NoteView created(String path, String body) {
        return create.handle(new CreateNoteCommand(USER, path, body, List.of(), null));
    }

    private static UUID idOf(NoteView view) {
        return view.note().value().id();
    }

    @Test
    @DisplayName("T108 CA1: cria a nota (versão 0) com tags e wikilinks; a trava do vault vem antes de qualquer gravação")
    void deveCriar() {
        NoteView view = created("reviews/2026-10.md", "Veja [[MacBook]] #revisao-mensal");
        Note note = view.note().value();
        assertThat(view.note().version()).isZero();
        assertThat(note.path().value()).isEqualTo("reviews/2026-10.md");
        assertThat(note.tags()).containsExactly("revisao-mensal");
        assertThat(view.links()).hasSize(1);
        assertThat(view.links().get(0).link().state()).isEqualTo(NoteLink.State.PENDING);
        assertThat(notes.calls().get(0)).isEqualTo("lock");
    }

    @Test
    @DisplayName("T108 CA1: criar uma nota resolve os links pendentes das outras ([[MacBook]] passa a apontar para ela)")
    void deveResolverPendentesAoCriar() {
        NoteView review = created("reviews/2026-10.md", "Veja [[MacBook]]");
        assertThat(notes.get(idOf(review)).wikilinks().get(0).targetId()).isNull();
        NoteView macbook = created("MacBook.md", "notebook");
        assertThat(notes.get(idOf(review)).wikilinks().get(0).targetId()).isEqualTo(idOf(macbook));
    }

    @Test
    @DisplayName("T108 CA2: caminho usado por nota ativa, inclusive em outra caixa, é NOTE_PATH_TAKEN; depois da lixeira o caminho volta a ficar livre")
    void deveRecusarCaminhoOcupado() {
        NoteView first = created("Reviews/2026-10.md", "a");
        assertThatThrownBy(() -> created("reviews/2026-10.md", "b")).isInstanceOf(NotePathTakenException.class).extracting("code").isEqualTo("NOTE_PATH_TAKEN");
        trash.handle(new TrashNoteCommand(USER, idOf(first)));
        NoteView again = created("reviews/2026-10.md", "b");
        assertThat(again.note().value().path().value()).isEqualTo("reviews/2026-10.md");
    }

    @Test
    @DisplayName("T108 CA3: 5.000 notas é o limite (NOTE_LIMIT_REACHED); caminho inválido é NOTE_PATH_INVALID")
    void deveLimitarENotas() {
        wire(new InMemoryNoteGateway(NOW) {
            @Override
            public int countAll(UUID userId) {
                return NoteLimits.MAX_NOTES;
            }
        });
        assertThatThrownBy(() -> created("a.md", "x")).isInstanceOf(NoteLimitReachedException.class);
        wire(new InMemoryNoteGateway(NOW));
        assertThatThrownBy(() -> created("../x.md", "x")).isInstanceOf(NotePathInvalidException.class);
        assertThat(notes.saves()).isZero();
    }

    @Test
    @DisplayName("idempotência: a mesma chave e o mesmo comando devolvem a mesma nota; outro comando com a chave é IDEMPOTENCY_KEY_REUSED")
    void deveSerIdempotente() {
        CreateNoteCommand command = new CreateNoteCommand(USER, "a.md", "corpo", List.of(new ExplicitLinkSpec(NoteLinkType.GOAL, GOAL, null)), KEY1);
        NoteView first = create.handle(command);
        NoteView replay = create.handle(command);
        assertThat(idOf(replay)).isEqualTo(idOf(first));
        assertThat(notes.all()).hasSize(1);
        assertThatThrownBy(() -> create.handle(new CreateNoteCommand(USER, "a.md", "outro corpo", List.of(), KEY1)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    @DisplayName("replay de nota que sumiu (esvaziada) é NOTE_NOT_FOUND")
    void deveFalharOReplayDeNotaApagada() {
        CreateNoteCommand command = new CreateNoteCommand(USER, "a.md", "corpo", List.of(), KEY1);
        NoteView first = create.handle(command);
        trash.handle(new TrashNoteCommand(USER, idOf(first)));
        new EmptyTrashUseCaseImpl(notes).handle(new EmptyTrashCommand(USER));
        assertThatThrownBy(() -> create.handle(command)).isInstanceOf(NoteNotFoundException.class);
    }

    @Test
    @DisplayName("T109 CA1/CA2: vínculo a transação do usuário, meta arquivada (S-164) e mês válido; a visão traz rótulo e estado do alvo")
    void deveVincularAEntidades() {
        NoteView view = create.handle(new CreateNoteCommand(USER, "a.md", "x", List.of(new ExplicitLinkSpec(NoteLinkType.TRANSACTION, TRANSACTION, null),
                new ExplicitLinkSpec(NoteLinkType.GOAL, GOAL, null), new ExplicitLinkSpec(NoteLinkType.MONTHLY_SUMMARY, null, "2026-10")), null));
        assertThat(view.links()).hasSize(3);
        assertThat(view.links().get(0).target().label()).isEqualTo("Notebook");
        assertThat(view.links().get(1).target().status()).isEqualTo(TargetStatus.ARCHIVED);
        assertThat(view.links().get(2).target()).isNull();
        assertThat(view.links().get(2).link().targetKey()).isEqualTo("2026-10");
    }

    @Test
    @DisplayName("T109 CA1: alvo de outro usuário ou inexistente é NOTE_LINK_TARGET_NOT_FOUND; nada é gravado")
    void deveRecusarAlvoInexistente() {
        InMemoryNoteTargetGateway other = new InMemoryNoteTargetGateway();
        other.add(OTHER_USER, new TargetRef(NoteLinkType.TRANSACTION, NoteTestKit.id(777)), new TargetInfo("alheia", TargetStatus.ACTIVE));
        assertThatThrownBy(() -> create.handle(new CreateNoteCommand(USER, "a.md", "x",
                List.of(new ExplicitLinkSpec(NoteLinkType.TRANSACTION, NoteTestKit.id(777), null)), null))).isInstanceOf(NoteLinkTargetNotFoundException.class);
        assertThatThrownBy(() -> create.handle(new CreateNoteCommand(USER, "a.md", "x",
                List.of(new ExplicitLinkSpec(NoteLinkType.PERSON, UUID.randomUUID(), null)), null))).isInstanceOf(NoteLinkTargetNotFoundException.class);
        assertThat(notes.saves()).isZero();
    }

    @Test
    @DisplayName("T109 CA2: mês 2026-13, tipo sem id/chave e id junto com chave são NOTE_LINK_INVALID; mais de 100 vínculos é NOTE_LINK_LIMIT_REACHED")
    void deveValidarAForma() {
        NoteView note = created("a.md", "x");
        for (ExplicitLinkSpec bad : List.of(new ExplicitLinkSpec(NoteLinkType.MONTHLY_SUMMARY, null, "2026-13"),
                new ExplicitLinkSpec(NoteLinkType.MONTHLY_SUMMARY, null, null), new ExplicitLinkSpec(NoteLinkType.MONTHLY_SUMMARY, GOAL, "2026-10"),
                new ExplicitLinkSpec(NoteLinkType.GOAL, null, null), new ExplicitLinkSpec(NoteLinkType.GOAL, GOAL, "2026-10"), new ExplicitLinkSpec(null, GOAL, null))) {
            assertThatThrownBy(() -> setLinks.handle(new SetNoteLinksCommand(USER, idOf(note), List.of(bad), 0))).as(bad.toString())
                    .isInstanceOf(NoteLinkInvalidException.class);
        }
        List<ExplicitLinkSpec> many = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(new ExplicitLinkSpec(NoteLinkType.GOAL, GOAL, null));
        }
        assertThatThrownBy(() -> setLinks.handle(new SetNoteLinksCommand(USER, idOf(note), many, 0))).isInstanceOf(NoteLinkLimitReachedException.class);
    }

    @Test
    @DisplayName("T109 CA5/CA6: PUT /links troca só os EXPLICIT, os WIKILINK ficam; versão sobe; versão velha é VERSION_CONFLICT; nota na lixeira é NOTE_TRASHED")
    void deveTrocarSoOsExplicitos() {
        NoteView note = created("a.md", "veja [[b]]");
        NoteView linked = setLinks.handle(new SetNoteLinksCommand(USER, idOf(note), List.of(new ExplicitLinkSpec(NoteLinkType.GOAL, GOAL, null)), 0));
        assertThat(linked.note().version()).isEqualTo(1);
        assertThat(linked.note().value().wikilinks()).hasSize(1);
        assertThat(linked.note().value().explicitLinks()).hasSize(1);
        assertThatThrownBy(() -> setLinks.handle(new SetNoteLinksCommand(USER, idOf(note), List.of(), 0))).isInstanceOf(VersionConflictException.class);
        NoteView cleared = setLinks.handle(new SetNoteLinksCommand(USER, idOf(note), List.of(), 1));
        assertThat(cleared.note().value().explicitLinks()).isEmpty();
        assertThat(cleared.note().value().wikilinks()).hasSize(1);
        trash.handle(new TrashNoteCommand(USER, idOf(note)));
        assertThatThrownBy(() -> setLinks.handle(new SetNoteLinksCommand(USER, idOf(note), List.of(), 3))).isInstanceOf(NoteTrashedException.class);
        assertThatThrownBy(() -> setLinks.handle(new SetNoteLinksCommand(OTHER_USER, idOf(note), List.of(), 3))).isInstanceOf(NoteNotFoundException.class);
    }

    @Test
    @DisplayName("T109 CA6: vínculo explícito para nota (NOTE) só vale para nota ativa e não duplica o wikilink; nota na lixeira não é alvo")
    void deveVincularANota() {
        NoteView a = created("a.md", "x");
        NoteView b = created("b.md", "x");
        NoteView linked = setLinks.handle(new SetNoteLinksCommand(USER, idOf(a), List.of(new ExplicitLinkSpec(NoteLinkType.NOTE, idOf(b), null)), 0));
        assertThat(linked.links().get(0).target().label()).isEqualTo("b");
        trash.handle(new TrashNoteCommand(USER, idOf(b)));
        assertThatThrownBy(() -> setLinks.handle(new SetNoteLinksCommand(USER, idOf(a), List.of(new ExplicitLinkSpec(NoteLinkType.NOTE, idOf(b), null)), 1)))
                .isInstanceOf(NoteLinkTargetNotFoundException.class);
        assertThatThrownBy(() -> setLinks.handle(new SetNoteLinksCommand(USER, idOf(a), List.of(new ExplicitLinkSpec(NoteLinkType.NOTE, idOf(a), null)), 1)))
                .isInstanceOf(NoteLinkInvalidException.class);
    }

    @Test
    @DisplayName("T108 CA3: PUT com a versão certa grava e sobe a versão; versão velha é VERSION_CONFLICT; recalcula tags e wikilinks e mantém os explícitos")
    void deveEditarComVersao() {
        NoteView note = create.handle(new CreateNoteCommand(USER, "a.md", "[[x]] #um", List.of(new ExplicitLinkSpec(NoteLinkType.GOAL, GOAL, null)), null));
        NoteView edited = update.handle(new UpdateNoteCommand(USER, idOf(note), "[[y]] #dois", 0));
        assertThat(edited.note().version()).isEqualTo(1);
        assertThat(edited.note().value().tags()).containsExactly("dois");
        assertThat(edited.note().value().wikilinks()).extracting(NoteLink::text).containsExactly("y");
        assertThat(edited.note().value().explicitLinks()).hasSize(1);
        assertThatThrownBy(() -> update.handle(new UpdateNoteCommand(USER, idOf(note), "z", 0))).isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> update.handle(new UpdateNoteCommand(OTHER_USER, idOf(note), "z", 1))).isInstanceOf(NoteNotFoundException.class);
        assertThatThrownBy(() -> update.handle(new UpdateNoteCommand(USER, NoteTestKit.id(404), "z", 0))).isInstanceOf(NoteNotFoundException.class);
    }

    @Test
    @DisplayName("T110 CA1: renomear reviews/2026-10.md para reviews/outubro.md reescreve [[2026-10]], [[reviews/2026-10]] e [[2026-10|alias]], preservando alias e seção; rewrittenNotes lista as notas")
    void deveReescreverOsLinks() {
        NoteView review = created("reviews/2026-10.md", "# Outubro");
        NoteView a = created("a.md", "veja [[2026-10]], [[reviews/2026-10]] e [[2026-10|alias]] e [[2026-10#Resumo]] e ![[2026-10]]");
        NoteView b = created("pasta/b.md", "sem link [[outra]]");
        MoveNoteResult result = move.handle(new MoveNoteCommand(USER, idOf(review), "reviews/outubro.md", 0));
        assertThat(notes.get(idOf(a)).body()).isEqualTo("veja [[outubro]], [[reviews/outubro]] e [[outubro|alias]] e [[outubro#Resumo]] e ![[outubro]]");
        assertThat(notes.get(idOf(b)).body()).isEqualTo("sem link [[outra]]");
        assertThat(result.rewrittenNotes()).extracting(com.moneylog.core.note.app.usecase.MoveNoteUseCase.RewrittenNote::path).containsExactly("a.md");
        assertThat(result.note().note().value().path().value()).isEqualTo("reviews/outubro.md");
        assertThat(result.note().note().value().title()).isEqualTo("outubro");
        assertThat(result.note().note().version()).isEqualTo(1);
        assertThat(notes.get(idOf(a)).wikilinks()).allMatch(l -> idOf(review).equals(l.targetId()));
    }

    @Test
    @DisplayName("S-278 (A4): criar, mover, lixeira e restaurar recalculam só os wikilinks cujo nome coincide com o das notas afetadas, não todos os do vault")
    void deveRecalcularSoOsLinksAfetados() {
        for (int i = 0; i < 50; i++) {
            created("lixo" + i + ".md", "[[nada" + i + "]] [[Alvo]]");
        }
        notes.nameKeyQueries().clear();
        int readBefore = notes.wikilinkRowsRead();
        NoteView alvo = created("pasta/Alvo.md", "x");
        assertThat(notes.nameKeyQueries()).containsExactly(java.util.Set.of("alvo"));
        assertThat(notes.wikilinkRowsRead() - readBefore).isEqualTo(50);
        assertThat(notes.get(NoteTestKit.id(1)).wikilinks().get(1).targetId()).isEqualTo(idOf(alvo));
        MoveNoteResult moved = move.handle(new MoveNoteCommand(USER, idOf(alvo), "pasta/Outro.md", 0));
        assertThat(notes.nameKeyQueries().get(1)).containsExactlyInAnyOrder("alvo", "outro");
        assertThat(moved.rewrittenNotes()).hasSize(50);
        trash.handle(new TrashNoteCommand(USER, idOf(alvo)));
        assertThat(notes.nameKeyQueries().get(2)).containsExactly("outro");
        restore.handle(new RestoreNoteCommand(USER, idOf(alvo)));
        assertThat(notes.nameKeyQueries().get(3)).containsExactly("outro");
        assertThat(notes.get(NoteTestKit.id(1)).wikilinks().get(1).targetId()).isEqualTo(idOf(alvo));
    }

    @Test
    @DisplayName("M4 (S-277): renomear que faria OUTRA nota passar de 200 KB é recusado com NOTE_TOO_LARGE que cita a nota, e nada é gravado")
    void deveIdentificarANotaQueEstouraNoRename() {
        NoteView alvo = created("A.md", "alvo");
        String linkText = "[[A]]";
        String body = linkText.repeat((NoteLimits.NOTE_MAX_BYTES - 10) / linkText.length());
        NoteView grande = created("pasta/grande.md", body);
        int savesBefore = notes.saves();
        assertThatThrownBy(() -> move.handle(new MoveNoteCommand(USER, idOf(alvo), "Um nome bem mais comprido que o antigo.md", 0)))
                .isInstanceOf(com.moneylog.core.note.exception.NoteExceptions.NoteRewriteTooLargeException.class)
                .hasMessageContaining("pasta/grande.md").extracting("code").isEqualTo("NOTE_TOO_LARGE");
        assertThat(notes.saves()).isEqualTo(savesBefore);
        assertThat(notes.get(idOf(grande)).body()).isEqualTo(body);
    }

    @Test
    @DisplayName("S-274 (BUG-QA-08): renomear para um nome que só difere por acento/caixa de outra nota não reaponta o link para a nota equivalente")
    void naoDeveRepontarParaANotaEquivalente() {
        NoteView cafe = created("Café.md", "com acento");
        NoteView leitorA = created("leitorA.md", "[[Café]]");
        NoteView leitorB = created("leitorB.md", "[[Cafe]]");
        NoteView outra = created("Outra.md", "x");
        NoteView leitorC = created("leitorC.md", "[[Outra]]");
        assertThat(notes.get(idOf(leitorB)).wikilinks()).extracting(NoteLink::targetId).containsExactly(idOf(cafe));
        move.handle(new MoveNoteCommand(USER, idOf(outra), "Cafe.md", 0));
        assertThat(notes.get(idOf(leitorC)).body()).isEqualTo("[[Cafe]]");
        assertThat(notes.get(idOf(leitorC)).wikilinks()).extracting(NoteLink::targetId).containsExactly(idOf(outra));
        assertThat(notes.get(idOf(leitorA)).wikilinks()).extracting(NoteLink::targetId).containsExactly(idOf(cafe));
        assertThat(notes.get(idOf(leitorB)).wikilinks()).extracting(NoteLink::targetId).containsExactly(idOf(outra));
    }

    @Test
    @DisplayName("S-271 (BUG-QA-12): criar e editar com mais de 500 wikilinks distintos é recusado (NOTE_LINK_LIMIT_REACHED) e nada é gravado")
    void deveRecusarWikilinksDemais() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i <= NoteLimits.MAX_WIKILINKS; i++) {
            body.append("[[n").append(i).append("]] ");
        }
        assertThatThrownBy(() -> created("demais.md", body.toString())).isInstanceOf(NoteLinkLimitReachedException.class);
        assertThat(notes.all()).isEmpty();
        NoteView ok = created("ok.md", "[[x]]");
        assertThatThrownBy(() -> update.handle(new UpdateNoteCommand(USER, idOf(ok), body.toString(), 0))).isInstanceOf(NoteLinkLimitReachedException.class);
        assertThat(notes.get(idOf(ok)).body()).isEqualTo("[[x]]");
    }

    @Test
    @DisplayName("T110 CA2: com nomes iguais em pastas diferentes, só reescreve os links que apontavam para ESTA nota")
    void deveReescreverSoOsLinksDaquelaNota() {
        NoteView raiz = created("Nota.md", "raiz");
        NoteView fundo = created("a/b/Nota.md", "fundo");
        NoteView leitor = created("leitor.md", "[[Nota]] e [[a/b/Nota]] e [[b/Nota]]");
        assertThat(notes.get(idOf(leitor)).wikilinks()).extracting(NoteLink::targetId).containsExactly(idOf(raiz), idOf(fundo), idOf(fundo));
        move.handle(new MoveNoteCommand(USER, idOf(fundo), "a/b/Renomeada.md", 0));
        assertThat(notes.get(idOf(leitor)).body()).isEqualTo("[[Nota]] e [[a/b/Renomeada]] e [[a/b/Renomeada]]");
        assertThat(notes.get(idOf(leitor)).wikilinks()).extracting(NoteLink::targetId).containsOnly(idOf(raiz), idOf(fundo));
    }

    @Test
    @DisplayName("T110 CA2: se o nome novo ficaria ambíguo, o link vira o caminho completo e continua apontando para a nota movida")
    void deveUsarOCaminhoSeOMesmoNomeFicariaAmbiguo() {
        created("Alvo.md", "outra nota com o nome de destino");
        NoteView movida = created("z/Velha.md", "x");
        NoteView leitor = created("leitor.md", "[[Velha]] [[Velha.md]]");
        move.handle(new MoveNoteCommand(USER, idOf(movida), "z/Alvo.md", 0));
        assertThat(notes.get(idOf(leitor)).body()).isEqualTo("[[z/Alvo]] [[z/Alvo.md]]");
        assertThat(notes.get(idOf(leitor)).wikilinks()).allMatch(l -> idOf(movida).equals(l.targetId()));
    }

    @Test
    @DisplayName("T110 CA5: link dentro de código não é reescrito; link da própria nota para ela mesma é")
    void naoDeveReescreverCodigo() {
        NoteView alvo = created("alvo.md", "eu mesma [[alvo]] e `[[alvo]]`");
        NoteView leitor = created("leitor.md", "[[alvo]] `[[alvo]]`\n```\n[[alvo]]\n```");
        move.handle(new MoveNoteCommand(USER, idOf(alvo), "novo.md", 0));
        assertThat(notes.get(idOf(leitor)).body()).isEqualTo("[[novo]] `[[alvo]]`\n```\n[[alvo]]\n```");
        assertThat(notes.get(idOf(alvo)).body()).isEqualTo("eu mesma [[novo]] e `[[alvo]]`");
    }

    @Test
    @DisplayName("T110 CA3: falha na 3ª nota desfaz tudo (rollback da transação): nenhuma nota fica reescrita")
    void deveDesfazTudoSeFalhar() {
        NoteView alvo = created("alvo.md", "x");
        NoteView n1 = created("n1.md", "[[alvo]]");
        NoteView n2 = created("n2.md", "[[alvo]]");
        NoteView n3 = created("n3.md", "[[alvo]]");
        notes.begin();
        int before = notes.saves();
        notes.failOnSave(before + 3);
        assertThatThrownBy(() -> move.handle(new MoveNoteCommand(USER, idOf(alvo), "novo.md", 0))).isInstanceOf(IllegalStateException.class);
        notes.rollback();
        assertThat(notes.get(idOf(n1)).body()).isEqualTo("[[alvo]]");
        assertThat(notes.get(idOf(n2)).body()).isEqualTo("[[alvo]]");
        assertThat(notes.get(idOf(n3)).body()).isEqualTo("[[alvo]]");
        assertThat(notes.get(idOf(alvo)).path().value()).isEqualTo("alvo.md");
    }

    @Test
    @DisplayName("T110 CA4: versão velha é VERSION_CONFLICT; caminho ocupado é NOTE_PATH_TAKEN; mesmo caminho não faz nada; só a caixa pode mudar; lixeira é NOTE_TRASHED")
    void deveValidarAMudanca() {
        NoteView a = created("a.md", "x");
        created("b.md", "x");
        assertThatThrownBy(() -> move.handle(new MoveNoteCommand(USER, idOf(a), "c.md", 5))).isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> move.handle(new MoveNoteCommand(USER, idOf(a), "B.md", 0))).isInstanceOf(NotePathTakenException.class);
        assertThatThrownBy(() -> move.handle(new MoveNoteCommand(USER, idOf(a), "../c.md", 0))).isInstanceOf(NotePathInvalidException.class);
        int saves = notes.saves();
        MoveNoteResult same = move.handle(new MoveNoteCommand(USER, idOf(a), "a.md", 0));
        assertThat(same.rewrittenNotes()).isEmpty();
        assertThat(notes.saves()).isEqualTo(saves);
        MoveNoteResult caixa = move.handle(new MoveNoteCommand(USER, idOf(a), "A.md", 0));
        assertThat(caixa.note().note().value().path().value()).isEqualTo("A.md");
        trash.handle(new TrashNoteCommand(USER, idOf(a)));
        assertThatThrownBy(() -> move.handle(new MoveNoteCommand(USER, idOf(a), "z.md", 2))).isInstanceOf(NoteTrashedException.class);
        assertThatThrownBy(() -> move.handle(new MoveNoteCommand(OTHER_USER, idOf(a), "z.md", 2))).isInstanceOf(NoteNotFoundException.class);
    }

    @Test
    @DisplayName("mover para um nome que já era alvo de link pendente resolve esse link, sem reescrever o texto dele")
    void deveResolverPendentesAoMover() {
        NoteView leitor = created("leitor.md", "[[Futuro]]");
        NoteView nota = created("rascunho.md", "x");
        assertThat(notes.get(idOf(leitor)).wikilinks().get(0).targetId()).isNull();
        MoveNoteResult result = move.handle(new MoveNoteCommand(USER, idOf(nota), "Futuro.md", 0));
        assertThat(result.rewrittenNotes()).isEmpty();
        assertThat(notes.get(idOf(leitor)).wikilinks().get(0).targetId()).isEqualTo(idOf(nota));
    }

    @Test
    @DisplayName("T108 CA4: trash tira a nota do índice e deixa pendentes os links para ela; restore devolve e resolve de novo; restore com caminho ocupado é NOTE_PATH_TAKEN")
    void deveTratarALixeira() {
        NoteView alvo = created("alvo.md", "x");
        NoteView leitor = created("leitor.md", "[[alvo]]");
        assertThat(notes.get(idOf(leitor)).wikilinks().get(0).targetId()).isEqualTo(idOf(alvo));
        NoteView trashed = trash.handle(new TrashNoteCommand(USER, idOf(alvo)));
        assertThat(trashed.note().value().isTrashed()).isTrue();
        assertThat(notes.get(idOf(leitor)).wikilinks().get(0).targetId()).isNull();
        assertThatThrownBy(() -> trash.handle(new TrashNoteCommand(USER, idOf(alvo)))).extracting("code").isEqualTo("NOTE_ALREADY_TRASHED");
        NoteView occupied = created("alvo.md", "ocupa");
        assertThatThrownBy(() -> restore.handle(new RestoreNoteCommand(USER, idOf(alvo)))).isInstanceOf(NotePathTakenException.class);
        assertThat(notes.get(idOf(alvo)).isTrashed()).isTrue();
        trash.handle(new TrashNoteCommand(USER, idOf(occupied)));
        NoteView back = restore.handle(new RestoreNoteCommand(USER, idOf(alvo)));
        assertThat(back.note().value().isTrashed()).isFalse();
        assertThat(notes.get(idOf(leitor)).wikilinks().get(0).targetId()).isEqualTo(idOf(alvo));
        assertThatThrownBy(() -> restore.handle(new RestoreNoteCommand(USER, idOf(alvo)))).extracting("code").isEqualTo("NOTE_NOT_TRASHED");
        assertThatThrownBy(() -> restore.handle(new RestoreNoteCommand(OTHER_USER, idOf(alvo)))).isInstanceOf(NoteNotFoundException.class);
        assertThatThrownBy(() -> trash.handle(new TrashNoteCommand(OTHER_USER, idOf(alvo)))).isInstanceOf(NoteNotFoundException.class);
    }

    @Test
    @DisplayName("T108 CA5: esvaziar a lixeira remove só as notas já na lixeira do usuário")
    void deveEsvaziarALixeira() {
        NoteView a = created("a.md", "x");
        NoteView b = created("b.md", "x");
        trash.handle(new TrashNoteCommand(USER, idOf(a)));
        int removed = new EmptyTrashUseCaseImpl(notes).handle(new EmptyTrashCommand(USER));
        assertThat(removed).isEqualTo(1);
        assertThat(notes.all()).extracting(Note::id).containsExactly(idOf(b));
        assertThat(new EmptyTrashUseCaseImpl(notes).handle(new EmptyTrashCommand(OTHER_USER))).isZero();
    }

    @Test
    @DisplayName("T108 CA6: GET devolve a nota (também na lixeira); de outro usuário é NOTE_NOT_FOUND")
    void deveLer() {
        NoteView a = created("a.md", "corpo");
        GetNoteUseCaseImpl get = new GetNoteUseCaseImpl(notes, views);
        assertThat(get.handle(new GetNoteQuery(USER, idOf(a))).note().value().body()).isEqualTo("corpo");
        trash.handle(new TrashNoteCommand(USER, idOf(a)));
        assertThat(get.handle(new GetNoteQuery(USER, idOf(a))).note().value().isTrashed()).isTrue();
        assertThatThrownBy(() -> get.handle(new GetNoteQuery(OTHER_USER, idOf(a)))).isInstanceOf(NoteNotFoundException.class);
    }

    @Test
    @DisplayName("lista: normaliza a tag antes de buscar; tag inválida não casa nada; página além do fim é vazia")
    void deveListarComTagNormalizada() {
        NoteGateway gateway = mock(NoteGateway.class);
        when(gateway.search(any())).thenReturn(Slice.empty());
        ListNotesUseCaseImpl list = new ListNotesUseCaseImpl(gateway);
        list.handle(new NoteQuery(USER, "  revisão ", "#Revisão Mensal", null, null, null, null, null, null, PageQuery.first()));
        ArgumentCaptor<NoteQuery> captor = ArgumentCaptor.forClass(NoteQuery.class);
        verify(gateway).search(captor.capture());
        assertThat(captor.getValue().tag()).isEqualTo("revisao-mensal");
        assertThat(captor.getValue().q()).isEqualTo("revisão");
        assertThat(captor.getValue().sort()).isEqualTo(NoteSort.RELEVANCE);
        assertThat(captor.getValue().status()).isEqualTo(NoteStatus.ACTIVE);
        assertThat(list.handle(new NoteQuery(USER, null, "$$$", null, null, null, null, null, null, PageQuery.first())).items()).isEmpty();
        assertThat(list.handle(new NoteQuery(USER, null, null, null, null, null, null, null, null, new PageQuery(Integer.MAX_VALUE, 50))).items()).isEmpty();
        verify(gateway, org.mockito.Mockito.times(1)).search(any());
        assertThat(new NoteQuery(USER, null, null, null, null, null, null, null, null, PageQuery.first()).sort()).isEqualTo(NoteSort.UPDATED);
    }

    @Test
    @DisplayName("backlinks: nota inexistente é NOTE_NOT_FOUND; existente delega ao gateway; tags delegam")
    void deveListarBacklinksETags() {
        NoteGateway gateway = mock(NoteGateway.class);
        UUID id = NoteTestKit.id(5);
        when(gateway.findById(USER, id)).thenReturn(Optional.empty());
        ListBacklinksUseCaseImpl backlinks = new ListBacklinksUseCaseImpl(gateway);
        assertThatThrownBy(() -> backlinks.handle(new ListBacklinksQuery(USER, id, PageQuery.first()))).isInstanceOf(NoteNotFoundException.class);
        verify(gateway, never()).backlinks(any(), any(), any());
        NoteView note = created("a.md", "x");
        when(gateway.findById(USER, id)).thenReturn(notes.findById(USER, idOf(note)));
        when(gateway.backlinks(USER, id, PageQuery.first())).thenReturn(Slice.empty());
        assertThat(backlinks.handle(new ListBacklinksQuery(USER, id, PageQuery.first())).items()).isEmpty();
        assertThat(backlinks.handle(new ListBacklinksQuery(USER, id, new PageQuery(Integer.MAX_VALUE, 50))).items()).isEmpty();
        when(gateway.tagCounts(USER)).thenReturn(List.of(new TagCount("a", 2)));
        assertThat(new ListTagsUseCaseImpl(gateway).handle(new ListTagsQuery(USER))).containsExactly(new TagCount("a", 2));
    }

    @Test
    @DisplayName("regressão financeira: o vault só usa as portas das notas (nenhuma dependência de razão, saldo ou dashboard nos casos de uso)")
    void naoDeveDependerDoFinanceiro() {
        for (Class<?> type : List.of(CreateNoteUseCaseImpl.class, UpdateNoteUseCaseImpl.class, MoveNoteUseCaseImpl.class, TrashNoteUseCaseImpl.class,
                RestoreNoteUseCaseImpl.class, SetNoteLinksUseCaseImpl.class, EmptyTrashUseCaseImpl.class)) {
            for (var constructor : type.getConstructors()) {
                for (Class<?> parameter : constructor.getParameterTypes()) {
                    assertThat(parameter.getName()).as(type.getSimpleName()).doesNotContain(".finance.").doesNotContain("Ledger").doesNotContain("FinancialRecords");
                }
            }
        }
        assertThat(LinkOrigin.values()).hasSize(2);
    }
}
