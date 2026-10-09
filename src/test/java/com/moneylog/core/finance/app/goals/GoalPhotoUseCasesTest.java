package com.moneylog.core.finance.app.goals;

import static com.moneylog.core.goal.util.GoalTestFactory.GOAL_ID;
import static com.moneylog.core.goal.util.GoalTestFactory.NOW;
import static com.moneylog.core.goal.util.GoalTestFactory.SECOND_GOAL_ID;
import static com.moneylog.core.goal.util.GoalTestFactory.TODAY;
import static com.moneylog.core.goal.util.GoalTestFactory.umaMeta;
import static com.moneylog.core.goal.util.GoalTestFactory.umaMetaDeEmergencia;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.RemoveGoalPhotoUseCase.RemoveGoalPhotoCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.ReorderGoalPhotosUseCase.ReorderGoalPhotosCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.SetGoalFeaturedUseCase.SetGoalFeaturedCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.SetGoalPhotoCoverUseCase.SetGoalPhotoCoverCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.UploadGoalPhotoUseCase.UploadGoalPhotoCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.UploadGoalPhotoUseCase.UploadedPhoto;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCasesImpl;
import com.moneylog.core.finance.app.goals.usecase.SetGoalFeaturedUseCaseImpl;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.app.gateways.GoalSnapshotGateway;
import com.moneylog.core.goal.app.gateways.PhotoStorage;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalPhoto;
import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.core.goal.exception.GoalExceptions.GoalArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoLimitReachedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoQuotaExceededException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoTooLargeException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoUnsupportedTypeException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotosKindNotSupportedException;
import com.moneylog.core.goal.photo.PhotoImagePipeline;
import com.moneylog.core.goal.photo.PhotoLimits;
import com.moneylog.core.goal.util.InMemoryGoalGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;
import com.moneylog.util.SyntheticImages;

@DisplayName("Casos de uso das fotos e do destaque da meta (T255, T257, ADR-018)")
class GoalPhotoUseCasesTest {

    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final InMemoryGoalGateway goals = new InMemoryGoalGateway(NOW);
    private final PhotoStorage storage = mock(PhotoStorage.class);
    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final IdempotencyGateway idempotency = mock(IdempotencyGateway.class);
    private final IdGenerator ids = mock(IdGenerator.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();

    private GoalPhotoUseCasesImpl photos;
    private SetGoalFeaturedUseCaseImpl featured;
    private PhotoLimits limits = PhotoLimits.defaults();
    private final byte[] jpeg = SyntheticImages.jpeg(600, 400, "Carro de exemplo");

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(time.now()).thenReturn(NOW);
        when(time.zone()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(ids.next()).thenAnswer(i -> UUID.randomUUID());
        when(records.load(any())).thenAnswer(invocation -> {
            FinancialRecords base = scenario.records();
            return new FinancialRecords(base.accounts(), base.cards(), base.transactions(), base.debts(),
                    goals.findAll(invocation.getArgument(0)).stream().map(Versioned::value).toList());
        });
        when(idempotency.claim(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(true);
        build(limits);
        goals.save(umaMetaDeEmergencia());
    }

    private void build(PhotoLimits newLimits) {
        limits = newLimits;
        GoalReadModel model = new GoalReadModel(mock(CommitmentSourcesGateway.class), mock(GoalSnapshotGateway.class), time);
        photos = new GoalPhotoUseCasesImpl(goals, storage, new PhotoImagePipeline(limits), limits, records, idempotency, ids, time, model);
        featured = new SetGoalFeaturedUseCaseImpl(goals, records, model);
    }

    private UploadedPhoto upload(UUID goalId, byte[] content) {
        return photos.handle(new UploadGoalPhotoCommand(USER_ID, goalId, content, null));
    }

    @Test
    @DisplayName("envia: processa, grava os metadados e depois os bytes (exibição e miniatura), travando a meta antes da cota")
    void deveEnviar() {
        UploadedPhoto uploaded = upload(GOAL_ID, jpeg);

        assertThat(uploaded.position()).isZero();
        assertThat(uploaded.photo().width()).isEqualTo(600);
        Goal stored = goals.findById(USER_ID, GOAL_ID).orElseThrow().value();
        assertThat(stored.photos()).extracting(GoalPhoto::id).containsExactly(uploaded.photo().id());
        assertThat(stored.coverPhotoId()).isEqualTo(uploaded.photo().id());
        InOrder order = inOrder(storage);
        order.verify(storage).put(eq(USER_ID), eq(GOAL_ID), eq(uploaded.photo().id()), any(byte[].class), any(byte[].class));
        assertThat(goals.calls()).containsSubsequence("lock", "lockMedia", "save");
    }

    @Test
    @DisplayName("a 9ª foto: GOAL_PHOTO_LIMIT_REACHED, nada gravado e nenhum byte enviado ao armazenamento")
    void limitePorMeta() {
        for (int i = 0; i < 8; i++) {
            upload(GOAL_ID, jpeg);
        }
        int saves = goals.saves();

        assertThatThrownBy(() -> upload(GOAL_ID, jpeg)).isInstanceOf(GoalPhotoLimitReachedException.class);

        assertThat(goals.saves()).isEqualTo(saves);
        verify(storage, org.mockito.Mockito.times(8)).put(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("cota do usuário: a 41ª foto (ou o byte que passa de 40 MB) é GOAL_PHOTO_QUOTA_EXCEEDED, contando todas as metas")
    void cotaPorUsuario() {
        build(new PhotoLimits(8, 3, PhotoLimits.MAX_BYTES_PER_USER, PhotoLimits.MAX_UPLOAD_BYTES, PhotoLimits.MAX_PIXELS, PhotoLimits.MAX_SIDE));
        goals.save(umaMeta(SECOND_GOAL_ID, "Outro sonho", "10000.00", null, GoalPriority.MEDIUM));
        upload(GOAL_ID, jpeg);
        upload(GOAL_ID, jpeg);
        upload(SECOND_GOAL_ID, jpeg);

        assertThatThrownBy(() -> upload(SECOND_GOAL_ID, jpeg)).isInstanceOf(GoalPhotoQuotaExceededException.class);

        build(new PhotoLimits(8, 40, 100, PhotoLimits.MAX_UPLOAD_BYTES, PhotoLimits.MAX_PIXELS, PhotoLimits.MAX_SIDE)); // 100 bytes no total
        assertThatThrownBy(() -> upload(GOAL_ID, jpeg)).isInstanceOf(GoalPhotoQuotaExceededException.class);
    }

    @Test
    @DisplayName("arquivo acima de 8 MB: GOAL_PHOTO_TOO_LARGE antes de qualquer trava ou leitura")
    void arquivoGrande() {
        byte[] big = new byte[(int) PhotoLimits.MAX_UPLOAD_BYTES + 1];

        assertThatThrownBy(() -> upload(GOAL_ID, big)).isInstanceOf(GoalPhotoTooLargeException.class);

        assertThat(goals.calls()).doesNotContain("lock");
    }

    @Test
    @DisplayName("conteúdo que não é imagem: GOAL_PHOTO_UNSUPPORTED_TYPE e nenhum byte guardado")
    void arquivoFalso() {
        assertThatThrownBy(() -> upload(GOAL_ID, SyntheticImages.text("não sou uma foto"))).isInstanceOf(GoalPhotoUnsupportedTypeException.class);

        verify(storage, never()).put(any(), any(), any(), any(), any());
        assertThat(goals.findById(USER_ID, GOAL_ID).orElseThrow().value().photos()).isEmpty();
    }

    @Test
    @DisplayName("meta de outro usuário é inexistente (404) e meta DEBT_PAYOFF não aceita foto")
    void isolamentoETipo() {
        assertThatThrownBy(() -> photos.handle(new UploadGoalPhotoCommand(OTHER_USER_ID, GOAL_ID, jpeg, null)))
                .isInstanceOf(GoalNotFoundException.class);
        UUID payoff = UUID.randomUUID();
        goals.save(Goal.createDebtPayoff(payoff, USER_ID, "Quitar", null, GoalPriority.HIGH, null));

        assertThatThrownBy(() -> upload(payoff, jpeg)).isInstanceOf(GoalPhotosKindNotSupportedException.class);
        verify(storage, never()).put(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Idempotency-Key: o mesmo arquivo devolve a mesma foto sem gravar outra; outro arquivo com a mesma chave é 409")
    void idempotencia() {
        when(idempotency.claim(eq(USER_ID), anyString(), eq(KEY), anyString(), any(), any(), any())).thenReturn(true, false, false);
        UploadedPhoto first = photos.handle(new UploadGoalPhotoCommand(USER_ID, GOAL_ID, jpeg, KEY));
        ArgumentCaptorHash captured = new ArgumentCaptorHash();
        verify(idempotency).claim(eq(USER_ID), anyString(), eq(KEY), org.mockito.ArgumentMatchers.argThat(captured), any(), any(), any());
        when(idempotency.find(eq(USER_ID), anyString(), eq(KEY))).thenReturn(Optional.of(new Claim(captured.hash, first.photo().id(), NOW)));

        UploadedPhoto replay = photos.handle(new UploadGoalPhotoCommand(USER_ID, GOAL_ID, jpeg, KEY));

        assertThat(replay.photo().id()).isEqualTo(first.photo().id());
        assertThat(goals.findById(USER_ID, GOAL_ID).orElseThrow().value().photos()).hasSize(1);
        verify(storage, org.mockito.Mockito.times(1)).put(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> photos.handle(new UploadGoalPhotoCommand(USER_ID, GOAL_ID, SyntheticImages.png(300, 300, "outra"), KEY)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    @DisplayName("remover a capa promove a próxima e apaga os bytes da foto; capa e ordem devolvem a meta atualizada")
    void ordemCapaERemocao() {
        UploadedPhoto a = upload(GOAL_ID, jpeg);
        UploadedPhoto b = upload(GOAL_ID, jpeg);
        UploadedPhoto c = upload(GOAL_ID, jpeg);

        GoalDetails reordered = photos.handle(new ReorderGoalPhotosCommand(USER_ID, GOAL_ID, List.of(c.photo().id(), a.photo().id(), b.photo().id())));
        assertThat(reordered.goal().photos()).extracting(GoalPhoto::id).containsExactly(c.photo().id(), a.photo().id(), b.photo().id());

        GoalDetails covered = photos.handle(new SetGoalPhotoCoverCommand(USER_ID, GOAL_ID, b.photo().id()));
        assertThat(covered.goal().coverPhotoId()).isEqualTo(b.photo().id());

        GoalDetails removed = photos.handle(new RemoveGoalPhotoCommand(USER_ID, GOAL_ID, b.photo().id()));
        assertThat(removed.goal().coverPhotoId()).isEqualTo(c.photo().id());
        assertThat(removed.goal().photos()).hasSize(2);
        verify(storage).delete(USER_ID, b.photo().id());
    }

    @Test
    @DisplayName("destaque: marcar B desmarca A na mesma operação; a resposta traz B; arquivada é GOAL_ARCHIVED")
    void destaqueUnico() {
        goals.save(umaMeta(SECOND_GOAL_ID, "Outro sonho", "10000.00", null, GoalPriority.MEDIUM));
        featured.handle(new SetGoalFeaturedCommand(USER_ID, GOAL_ID, true));
        assertThat(goals.findFeatured(USER_ID).orElseThrow().value().id()).isEqualTo(GOAL_ID);

        GoalDetails moved = featured.handle(new SetGoalFeaturedCommand(USER_ID, SECOND_GOAL_ID, true));

        assertThat(moved.goal().isFeatured()).isTrue();
        assertThat(goals.findById(USER_ID, GOAL_ID).orElseThrow().value().isFeatured()).isFalse();
        assertThat(goals.findFeatured(USER_ID).orElseThrow().value().id()).isEqualTo(SECOND_GOAL_ID);
        assertThat(goals.calls()).contains("lockMedia");

        Goal toArchive = goals.findById(USER_ID, GOAL_ID).orElseThrow().value();
        toArchive.archive();
        goals.save(toArchive);
        assertThatThrownBy(() -> featured.handle(new SetGoalFeaturedCommand(USER_ID, GOAL_ID, true))).isInstanceOf(GoalArchivedException.class);
        assertThat(goals.findFeatured(USER_ID).orElseThrow().value().id()).isEqualTo(SECOND_GOAL_ID);

        GoalDetails off = featured.handle(new SetGoalFeaturedCommand(USER_ID, SECOND_GOAL_ID, false));
        assertThat(off.goal().isFeatured()).isFalse();
        assertThat(goals.findFeatured(USER_ID)).isEmpty();
    }

    /** Captura o hash do comando que o caso de uso reivindicou. */
    private static final class ArgumentCaptorHash implements org.mockito.ArgumentMatcher<String> {
        String hash;

        @Override
        public boolean matches(String argument) {
            this.hash = argument;
            return true;
        }
    }
}
