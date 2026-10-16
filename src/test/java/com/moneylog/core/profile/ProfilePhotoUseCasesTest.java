package com.moneylog.core.profile;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.goal.app.gateways.PhotoStorage.Variant;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoInvalidImageException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoTooLargeException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoUnsupportedTypeException;
import com.moneylog.core.goal.photo.PhotoImagePipeline;
import com.moneylog.core.goal.photo.PhotoLimits;
import com.moneylog.core.goal.photo.ProcessedPhoto;
import com.moneylog.core.profile.app.gateways.ProfilePhotoGateway;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.GetProfilePhotoContentUseCase.ProfilePhotoContentQuery;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.ProfilePhotoInfo;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.ReplaceProfilePhotoUseCase.ReplaceProfilePhotoCommand;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCasesImpl;
import com.moneylog.core.profile.exception.ProfileExceptions.ProfilePhotoNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.util.SyntheticImages;

@DisplayName("Foto de perfil: substituir, ler, remover e informar versão")
class ProfilePhotoUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00.123Z");

    private ProfilePhotoGateway gateway;
    private ProfilePhotoUseCasesImpl useCases;
    private final byte[] jpeg = SyntheticImages.jpeg(900, 600, "Perfil");

    @BeforeEach
    void setUp() {
        gateway = mock(ProfilePhotoGateway.class);
        TimeProvider time = mock(TimeProvider.class);
        when(time.now()).thenReturn(NOW);
        PhotoLimits limits = new PhotoLimits(8, 40, PhotoLimits.MAX_BYTES_PER_USER, PhotoLimits.MAX_UPLOAD_BYTES, PhotoLimits.MAX_PIXELS, PhotoLimits.MAX_SIDE);
        useCases = new ProfilePhotoUseCasesImpl(gateway, new PhotoImagePipeline(limits), limits, time);
    }

    @Test
    @DisplayName("envio válido grava as variantes processadas e devolve hasPhoto com a versão")
    void substituiAFoto() {
        when(gateway.updatedAt(USER_ID)).thenReturn(Optional.of(NOW));

        ProfilePhotoInfo info = useCases.handle(new ReplaceProfilePhotoCommand(USER_ID, jpeg));

        assertThat(info).isEqualTo(new ProfilePhotoInfo(true, NOW.toEpochMilli()));
        verify(gateway).replace(any(), any(ProcessedPhoto.class), org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    @DisplayName("tipo inválido, vazio e grande demais são recusados sem gravar")
    void recusaEntradasInvalidas() {
        assertThatThrownBy(() -> useCases.handle(new ReplaceProfilePhotoCommand(USER_ID, SyntheticImages.text("não é imagem"))))
                .isInstanceOf(GoalPhotoUnsupportedTypeException.class);
        assertThatThrownBy(() -> useCases.handle(new ReplaceProfilePhotoCommand(USER_ID, new byte[0]))).isInstanceOf(GoalPhotoInvalidImageException.class);
        assertThatThrownBy(() -> useCases.handle(new ReplaceProfilePhotoCommand(USER_ID, new byte[(int) PhotoLimits.MAX_UPLOAD_BYTES + 1])))
                .isInstanceOf(GoalPhotoTooLargeException.class);
        verify(gateway, never()).replace(any(), any(), any());
    }

    @Test
    @DisplayName("sem foto: conteúdo é 404 de perfil e a informação é hasPhoto=false, versão 0")
    void semFoto() {
        when(gateway.content(USER_ID, Variant.DISPLAY)).thenReturn(Optional.empty());
        when(gateway.updatedAt(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCases.handle(new ProfilePhotoContentQuery(USER_ID, Variant.DISPLAY))).isInstanceOf(ProfilePhotoNotFoundException.class);
        assertThat(useCases.info(USER_ID)).isEqualTo(ProfilePhotoInfo.NONE);
    }

    @Test
    @DisplayName("conteúdo e remoção sempre usam o usuário informado")
    void leERemove() {
        byte[] bytes = {1, 2, 3};
        when(gateway.content(USER_ID, Variant.THUMBNAIL)).thenReturn(Optional.of(bytes));

        assertThat(useCases.handle(new ProfilePhotoContentQuery(USER_ID, Variant.THUMBNAIL))).isEqualTo(bytes);
        useCases.handle(USER_ID);

        verify(gateway).delete(USER_ID);
    }

    @Test
    @DisplayName("foto processada acima do limite próprio do perfil é recusada sem gravar e sem tocar a cota das metas")
    void recusaFotoProcessadaGrande() {
        PhotoImagePipeline big = mock(PhotoImagePipeline.class);
        PhotoLimits limits = PhotoLimits.defaults();
        int half = (int) (ProfilePhotoUseCasesImpl.MAX_STORED_BYTES / 2) + 1;
        when(big.process(any())).thenReturn(new ProcessedPhoto(new byte[half], new byte[half], 1280, 720));
        ProfilePhotoUseCasesImpl strict = new ProfilePhotoUseCasesImpl(gateway, big, limits, mock(TimeProvider.class));

        assertThatThrownBy(() -> strict.handle(new ReplaceProfilePhotoCommand(USER_ID, jpeg)))
                .isInstanceOf(com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoQuotaExceededException.class);
        verify(gateway, never()).replace(any(), any(), any());
    }
}
