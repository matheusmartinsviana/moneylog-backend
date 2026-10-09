package com.moneylog.core.goal.domain;

import static com.moneylog.core.goal.util.GoalTestFactory.NOW;
import static com.moneylog.core.goal.util.GoalTestFactory.umaMetaDeEmergencia;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.goal.exception.GoalExceptions.GoalArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalFeaturedKindNotSupportedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoLimitReachedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotosKindNotSupportedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotosOrderMismatchException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalStateInconsistentException;
import com.moneylog.core.sk.money.Money;

@DisplayName("Meta: fotos e destaque (T255, ADR-018): ordem, capa, limite, tipo e arquivamento")
class GoalPhotosTest {

    private static GoalPhoto photo() {
        return new GoalPhoto(UUID.randomUUID(), 1280, 853, 150_000, NOW);
    }

    private static Goal withPhotos(int count) {
        Goal goal = umaMetaDeEmergencia();
        for (int i = 0; i < count; i++) {
            goal.addPhoto(photo(), 8);
        }
        return goal;
    }

    @Test
    @DisplayName("a primeira foto vira a capa; as demais entram no fim")
    void primeiraFotoECapa() {
        Goal goal = umaMetaDeEmergencia();
        assertThat(goal.photos()).isEmpty();
        assertThat(goal.coverPhotoId()).isNull();

        GoalPhoto first = goal.addPhoto(photo(), 8);
        GoalPhoto second = goal.addPhoto(photo(), 8);

        assertThat(goal.coverPhotoId()).isEqualTo(first.id());
        assertThat(goal.photos()).extracting(GoalPhoto::id).containsExactly(first.id(), second.id());
    }

    @Test
    @DisplayName("a 9ª foto é recusada com GOAL_PHOTO_LIMIT_REACHED e nada é acrescentado")
    void limiteDeOitoPorMeta() {
        Goal goal = withPhotos(8);

        assertThatThrownBy(() -> goal.addPhoto(photo(), 8)).isInstanceOf(GoalPhotoLimitReachedException.class);
        assertThat(goal.photos()).hasSize(8);
    }

    @Test
    @DisplayName("definir a capa leva a foto para a posição 0 e as outras descem")
    void definirCapa() {
        Goal goal = withPhotos(3);
        List<GoalPhoto> before = goal.photos();

        goal.setCover(before.get(2).id());

        assertThat(goal.photos()).extracting(GoalPhoto::id).containsExactly(before.get(2).id(), before.get(0).id(), before.get(1).id());
        assertThat(goal.coverPhotoId()).isEqualTo(before.get(2).id());
    }

    @Test
    @DisplayName("remover a capa promove a próxima; foto que não existe é GOAL_PHOTO_NOT_FOUND")
    void removerCapa() {
        Goal goal = withPhotos(3);
        List<GoalPhoto> before = goal.photos();

        goal.removePhoto(before.get(0).id());

        assertThat(goal.coverPhotoId()).isEqualTo(before.get(1).id());
        assertThat(goal.photos()).hasSize(2);
        assertThatThrownBy(() -> goal.removePhoto(UUID.randomUUID())).isInstanceOf(GoalPhotoNotFoundException.class);
        assertThatThrownBy(() -> goal.setCover(UUID.randomUUID())).isInstanceOf(GoalPhotoNotFoundException.class);
    }

    @Test
    @DisplayName("reordenar exige o conjunto exato: faltando, repetido ou de fora é GOAL_PHOTOS_ORDER_MISMATCH e a ordem não muda")
    void reordenar() {
        Goal goal = withPhotos(3);
        List<UUID> ids = goal.photos().stream().map(GoalPhoto::id).toList();

        goal.reorderPhotos(List.of(ids.get(2), ids.get(0), ids.get(1)));
        assertThat(goal.photos()).extracting(GoalPhoto::id).containsExactly(ids.get(2), ids.get(0), ids.get(1));

        List<UUID> current = goal.photos().stream().map(GoalPhoto::id).toList();
        assertThatThrownBy(() -> goal.reorderPhotos(List.of(ids.get(0), ids.get(1)))).isInstanceOf(GoalPhotosOrderMismatchException.class);
        assertThatThrownBy(() -> goal.reorderPhotos(List.of(ids.get(0), ids.get(0), ids.get(1)))).isInstanceOf(GoalPhotosOrderMismatchException.class);
        assertThatThrownBy(() -> goal.reorderPhotos(List.of(ids.get(0), ids.get(1), UUID.randomUUID()))).isInstanceOf(GoalPhotosOrderMismatchException.class);
        assertThatThrownBy(() -> goal.reorderPhotos(null)).isInstanceOf(GoalPhotosOrderMismatchException.class);
        assertThat(goal.photos()).extracting(GoalPhoto::id).containsExactlyElementsOf(current);
    }

    @Test
    @DisplayName("meta arquivada não aceita foto, capa, remoção nem ordem (GOAL_ARCHIVED)")
    void arquivadaNaoAceita() {
        Goal goal = withPhotos(2);
        UUID id = goal.photos().getFirst().id();
        goal.archive();

        assertThatThrownBy(() -> goal.addPhoto(photo(), 8)).isInstanceOf(GoalArchivedException.class);
        assertThatThrownBy(() -> goal.setCover(id)).isInstanceOf(GoalArchivedException.class);
        assertThatThrownBy(() -> goal.removePhoto(id)).isInstanceOf(GoalArchivedException.class);
        assertThatThrownBy(() -> goal.reorderPhotos(List.of(id))).isInstanceOf(GoalArchivedException.class);
    }

    @Test
    @DisplayName("só a meta de guardar dinheiro tem fotos e destaque: DEBT_PAYOFF recusa as duas coisas")
    void soSavings() {
        Goal payoff = Goal.createDebtPayoff(UUID.randomUUID(), USER_ID, "Quitar", null, GoalPriority.HIGH, null);

        assertThatThrownBy(() -> payoff.addPhoto(photo(), 8)).isInstanceOf(GoalPhotosKindNotSupportedException.class);
        assertThatThrownBy(payoff::feature).isInstanceOf(GoalFeaturedKindNotSupportedException.class);
        assertThatThrownBy(() -> payoff.restoreMedia(false, List.of(photo()))).isInstanceOf(GoalStateInconsistentException.class);
        assertThatThrownBy(() -> payoff.restoreMedia(true, List.of())).isInstanceOf(GoalStateInconsistentException.class);
    }

    @Test
    @DisplayName("destaque: marca e desmarca; meta arquivada não entra em destaque; arquivar limpa o destaque")
    void destaque() {
        Goal goal = umaMetaDeEmergencia();
        assertThat(goal.isFeatured()).isFalse();

        goal.feature();
        assertThat(goal.isFeatured()).isTrue();
        goal.unfeature();
        assertThat(goal.isFeatured()).isFalse();

        goal.feature();
        goal.archive();
        assertThat(goal.isFeatured()).isFalse();
        assertThatThrownBy(goal::feature).isInstanceOf(GoalArchivedException.class);
    }

    @Test
    @DisplayName("restoreMedia recusa meta arquivada em destaque (estado impossível)")
    void restaurarInconsistente() {
        Goal goal = umaMetaDeEmergencia();
        goal.archive();
        assertThatThrownBy(() -> goal.restoreMedia(true, List.of())).isInstanceOf(GoalStateInconsistentException.class);
    }

    @Test
    @DisplayName("a foto exige dimensões e tamanho positivos")
    void fotoInvalida() {
        assertThatThrownBy(() -> new GoalPhoto(UUID.randomUUID(), 0, 10, 10, NOW)).isInstanceOf(GoalStateInconsistentException.class);
        assertThatThrownBy(() -> new GoalPhoto(UUID.randomUUID(), 10, 10, 0, NOW)).isInstanceOf(GoalStateInconsistentException.class);
        assertThat(Money.of("1.00")).isNotNull();
    }
}
