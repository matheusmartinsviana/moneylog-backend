package com.moneylog.infra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.moneylog.core.note.app.ExplicitLinkValidator;
import com.moneylog.core.note.app.NoteViewAssembler;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.app.gateways.NoteTargetGateway;
import com.moneylog.core.note.app.usecase.CreateNoteUseCase;
import com.moneylog.core.note.app.usecase.CreateNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.EmptyTrashUseCase;
import com.moneylog.core.note.app.usecase.EmptyTrashUseCaseImpl;
import com.moneylog.core.note.app.usecase.ExportVaultUseCase;
import com.moneylog.core.note.app.usecase.ExportVaultUseCaseImpl;
import com.moneylog.core.note.app.usecase.GetNoteUseCase;
import com.moneylog.core.note.app.usecase.GetNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.ImportVaultUseCase;
import com.moneylog.core.note.app.usecase.ImportVaultUseCaseImpl;
import com.moneylog.core.note.app.usecase.ListBacklinksUseCase;
import com.moneylog.core.note.app.usecase.ListBacklinksUseCaseImpl;
import com.moneylog.core.note.app.usecase.ListNotesUseCase;
import com.moneylog.core.note.app.usecase.ListNotesUseCaseImpl;
import com.moneylog.core.note.app.usecase.ListTagsUseCase;
import com.moneylog.core.note.app.usecase.ListTagsUseCaseImpl;
import com.moneylog.core.note.app.usecase.MoveNoteUseCase;
import com.moneylog.core.note.app.usecase.MoveNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.RestoreNoteUseCase;
import com.moneylog.core.note.app.usecase.RestoreNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.SetNoteLinksUseCase;
import com.moneylog.core.note.app.usecase.SetNoteLinksUseCaseImpl;
import com.moneylog.core.note.app.usecase.TrashNoteUseCase;
import com.moneylog.core.note.app.usecase.TrashNoteUseCaseImpl;
import com.moneylog.core.note.app.usecase.UpdateNoteUseCase;
import com.moneylog.core.note.app.usecase.UpdateNoteUseCaseImpl;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.infra.note.gateways.NoteGatewayImpl;
import com.moneylog.infra.note.gateways.NoteTargetGatewayImpl;
import com.moneylog.infra.note.persistence.NoteRepositoryJpa;

/** Ligação da feature notas/vault (Etapa 6, T108 a T112): gateways, casos de uso (cada um numa transação) e o montador de visões. */
@Configuration(proxyBeanMethods = false)
public class NoteConfig {

    @Bean
    NoteGateway noteGateway(NoteRepositoryJpa notes, NamedParameterJdbcTemplate named, IdGenerator ids, TimeProvider time) {
        return new NoteGatewayImpl(notes, named, ids, time);
    }

    @Bean
    NoteTargetGateway noteTargetGateway(NamedParameterJdbcTemplate named) {
        return new NoteTargetGatewayImpl(named);
    }

    @Bean
    NoteViewAssembler noteViewAssembler(NoteTargetGateway targets) {
        return new NoteViewAssembler(targets);
    }

    @Bean
    ExplicitLinkValidator explicitLinkValidator(NoteTargetGateway targets) {
        return new ExplicitLinkValidator(targets);
    }

    @Bean
    CreateNoteUseCase createNoteUseCase(NoteGateway notes, ExplicitLinkValidator validator, NoteViewAssembler views, IdempotencyGateway idempotency,
                                        IdGenerator ids, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(CreateNoteUseCase.class, new CreateNoteUseCaseImpl(notes, validator, views, idempotency, ids, time), tm);
    }

    @Bean
    GetNoteUseCase getNoteUseCase(NoteGateway notes, NoteViewAssembler views, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetNoteUseCase.class, new GetNoteUseCaseImpl(notes, views), tm);
    }

    @Bean
    UpdateNoteUseCase updateNoteUseCase(NoteGateway notes, NoteViewAssembler views, PlatformTransactionManager tm) {
        return TransactionalProxies.required(UpdateNoteUseCase.class, new UpdateNoteUseCaseImpl(notes, views), tm);
    }

    @Bean
    MoveNoteUseCase moveNoteUseCase(NoteGateway notes, NoteViewAssembler views, PlatformTransactionManager tm) {
        return TransactionalProxies.required(MoveNoteUseCase.class, new MoveNoteUseCaseImpl(notes, views), tm);
    }

    @Bean
    TrashNoteUseCase trashNoteUseCase(NoteGateway notes, NoteViewAssembler views, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.required(TrashNoteUseCase.class, new TrashNoteUseCaseImpl(notes, views, time), tm);
    }

    @Bean
    RestoreNoteUseCase restoreNoteUseCase(NoteGateway notes, NoteViewAssembler views, PlatformTransactionManager tm) {
        return TransactionalProxies.required(RestoreNoteUseCase.class, new RestoreNoteUseCaseImpl(notes, views), tm);
    }

    @Bean
    EmptyTrashUseCase emptyTrashUseCase(NoteGateway notes, PlatformTransactionManager tm) {
        return TransactionalProxies.required(EmptyTrashUseCase.class, new EmptyTrashUseCaseImpl(notes), tm);
    }

    @Bean
    SetNoteLinksUseCase setNoteLinksUseCase(NoteGateway notes, ExplicitLinkValidator validator, NoteViewAssembler views, PlatformTransactionManager tm) {
        return TransactionalProxies.required(SetNoteLinksUseCase.class, new SetNoteLinksUseCaseImpl(notes, validator, views), tm);
    }

    @Bean
    ListNotesUseCase listNotesUseCase(NoteGateway notes, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListNotesUseCase.class, new ListNotesUseCaseImpl(notes), tm);
    }

    @Bean
    ListBacklinksUseCase listBacklinksUseCase(NoteGateway notes, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(ListBacklinksUseCase.class, new ListBacklinksUseCaseImpl(notes), tm);
    }

    @Bean
    ListTagsUseCase listTagsUseCase(NoteGateway notes, PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(ListTagsUseCase.class, new ListTagsUseCaseImpl(notes), tm);
    }

    /** Só lê para montar o índice; a escrita do zip acontece depois, em lotes, fora da transação (cada lote é uma leitura própria). */
    @Bean
    ExportVaultUseCase exportVaultUseCase(NoteGateway notes, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.readOnly(ExportVaultUseCase.class, new ExportVaultUseCaseImpl(notes, time), tm);
    }

    @Bean
    ImportVaultUseCase importVaultUseCase(NoteGateway notes, NoteTargetGateway targets, IdGenerator ids, PlatformTransactionManager tm) {
        return TransactionalProxies.required(ImportVaultUseCase.class, new ImportVaultUseCaseImpl(notes, targets, ids), tm);
    }
}
