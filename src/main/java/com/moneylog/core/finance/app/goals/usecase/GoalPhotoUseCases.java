package com.moneylog.core.finance.app.goals.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.app.gateways.PhotoStorage.Variant;
import com.moneylog.core.goal.domain.GoalPhoto;

/**
 * Fotos e destaque da meta (ADR-018). Só a meta {@code SAVINGS} tem fotos. Escrita devolve a foto criada ou a meta atualizada; a leitura do
 * conteúdo devolve os bytes e é a única que toca a tabela de bytes.
 */
public interface GoalPhotoUseCases {

    interface UploadGoalPhotoUseCase {
        /**
         * Higieniza, reduz e guarda a foto no fim da lista. Com {@code idempotencyKey} (escopo próprio, 24 h), repetir o mesmo arquivo na
         * mesma meta devolve a mesma foto sem gravar outra; outro arquivo com a mesma chave é {@code IDEMPOTENCY_KEY_REUSED}.
         */
        UploadedPhoto handle(UploadGoalPhotoCommand command);

        /** O conteúdo nunca é registrado em log. */
        record UploadGoalPhotoCommand(UUID userId, UUID goalId, byte[] content, UUID idempotencyKey) {
            @Override
            public String toString() {
                return "UploadGoalPhotoCommand[goalId=" + goalId + ", bytes=" + (content == null ? 0 : content.length) + "]";
            }
        }

        record UploadedPhoto(GoalPhoto photo, int position) {
        }
    }

    interface ReorderGoalPhotosUseCase {
        GoalDetails handle(ReorderGoalPhotosCommand command);

        record ReorderGoalPhotosCommand(UUID userId, UUID goalId, List<UUID> photoIds) {
        }
    }

    interface SetGoalPhotoCoverUseCase {
        GoalDetails handle(SetGoalPhotoCoverCommand command);

        record SetGoalPhotoCoverCommand(UUID userId, UUID goalId, UUID photoId) {
        }
    }

    interface RemoveGoalPhotoUseCase {
        GoalDetails handle(RemoveGoalPhotoCommand command);

        record RemoveGoalPhotoCommand(UUID userId, UUID goalId, UUID photoId) {
        }
    }

    interface GetGoalPhotoContentUseCase {
        /** Os bytes JPEG da variante; foto de outra meta ou de outro usuário é {@code GOAL_PHOTO_NOT_FOUND}. */
        byte[] handle(GoalPhotoContentQuery query);

        record GoalPhotoContentQuery(UUID userId, UUID goalId, UUID photoId, Variant variant) {
        }
    }

    interface SetGoalFeaturedUseCase {
        /**
         * Marca ou desmarca o destaque. Marcar move: a meta anterior é desmarcada na mesma transação (no máximo uma por usuário). Meta
         * arquivada: {@code GOAL_ARCHIVED}; que não é de guardar dinheiro: {@code GOAL_FEATURED_KIND_NOT_SUPPORTED}.
         */
        GoalDetails handle(SetGoalFeaturedCommand command);

        record SetGoalFeaturedCommand(UUID userId, UUID goalId, boolean featured) {
        }
    }
}
