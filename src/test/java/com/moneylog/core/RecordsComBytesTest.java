package com.moneylog.core;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.UploadGoalPhotoUseCase.UploadGoalPhotoCommand;
import com.moneylog.core.goal.photo.ProcessedPhoto;
import com.moneylog.core.importing.app.usecase.CreateImportBatchUseCase.CreateImportBatchCommand;
import com.moneylog.core.importing.parser.ColumnMapping;
import com.moneylog.core.note.app.vault.VaultZipReader.NoteEntry;
import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.ReplaceProfilePhotoUseCase.ReplaceProfilePhotoCommand;

@DisplayName("Records com byte[]: igualdade por conteúdo e toString sem vazar os bytes")
class RecordsComBytesTest {

    private static final String SECRET = "SEGREDO";
    private static final UUID GOAL_ID = UUID.fromString("00000000-0000-0000-0000-00000000b101");
    private static final UUID KEY = UUID.fromString("00000000-0000-0000-0000-00000000b102");

    private static byte[] secret() {
        return SECRET.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] other() {
        return "OUTRO".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("ProcessedPhoto")
    void processedPhoto() {
        ProcessedPhoto a = new ProcessedPhoto(secret(), secret(), 10, 20);
        ProcessedPhoto b = new ProcessedPhoto(secret(), secret(), 10, 20);
        ProcessedPhoto c = new ProcessedPhoto(secret(), other(), 10, 20);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
        assertThat(a.toString()).doesNotContain(SECRET).contains("7 bytes");
    }

    @Test
    @DisplayName("NoteEntry")
    void noteEntry() {
        NotePath path = NotePath.of("pasta/nota.md");
        NoteEntry a = new NoteEntry(1, "pasta/nota.md", path, secret(), false);
        NoteEntry b = new NoteEntry(1, "pasta/nota.md", path, secret(), false);
        NoteEntry c = new NoteEntry(1, "pasta/nota.md", path, other(), false);
        NoteEntry semDados = new NoteEntry(1, "pasta/nota.md", path, null, false);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c).isNotEqualTo(semDados);
        assertThat(a.toString()).doesNotContain(SECRET).contains("bytes=7");
        assertThat(semDados.toString()).contains("bytes=0");
    }

    @Test
    @DisplayName("UploadGoalPhotoCommand")
    void uploadGoalPhotoCommand() {
        UploadGoalPhotoCommand a = new UploadGoalPhotoCommand(USER_ID, GOAL_ID, secret(), KEY);
        UploadGoalPhotoCommand b = new UploadGoalPhotoCommand(USER_ID, GOAL_ID, secret(), KEY);
        UploadGoalPhotoCommand c = new UploadGoalPhotoCommand(USER_ID, GOAL_ID, other(), KEY);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
        assertThat(a.toString()).doesNotContain(SECRET).contains("bytes=7");
    }

    @Test
    @DisplayName("CreateImportBatchCommand")
    void createImportBatchCommand() {
        ColumnMapping mapping = new ColumnMapping(0, 1, 2, null, null, false, true, ';');
        CreateImportBatchCommand a = new CreateImportBatchCommand(USER_ID, GOAL_ID, null, secret(), "csv", mapping, KEY);
        CreateImportBatchCommand b = new CreateImportBatchCommand(USER_ID, GOAL_ID, null, secret(), "csv", mapping, KEY);
        CreateImportBatchCommand c = new CreateImportBatchCommand(USER_ID, GOAL_ID, null, other(), "csv", mapping, KEY);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
        assertThat(a.toString()).doesNotContain(SECRET).contains("bytes=7");
    }

    @Test
    @DisplayName("ReplaceProfilePhotoCommand")
    void replaceProfilePhotoCommand() {
        ReplaceProfilePhotoCommand a = new ReplaceProfilePhotoCommand(USER_ID, secret());
        ReplaceProfilePhotoCommand b = new ReplaceProfilePhotoCommand(USER_ID, secret());
        ReplaceProfilePhotoCommand c = new ReplaceProfilePhotoCommand(USER_ID, other());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(c);
        assertThat(a.toString()).doesNotContain(SECRET).contains("bytes=7");
    }
}
