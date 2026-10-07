package com.moneylog.core.note.app.usecase;

import com.moneylog.core.note.app.gateways.NoteGateway;

public class EmptyTrashUseCaseImpl implements EmptyTrashUseCase {

    private final NoteGateway notes;

    public EmptyTrashUseCaseImpl(NoteGateway notes) {
        this.notes = notes;
    }

    @Override
    public int handle(EmptyTrashCommand c) {
        notes.lockVault(c.userId());
        return notes.deleteTrashed(c.userId());
    }
}
