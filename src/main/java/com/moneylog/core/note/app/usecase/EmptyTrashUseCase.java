package com.moneylog.core.note.app.usecase;

import java.util.UUID;

public interface EmptyTrashUseCase {

    /**
     * Esvazia a lixeira: apaga de vez as notas já na lixeira do usuário e os vínculos delas. É a <b>única</b> exclusão definitiva do vault e
     * nunca toca entidade financeira. Devolve quantas notas foram apagadas.
     */
    int handle(EmptyTrashCommand command);

    record EmptyTrashCommand(UUID userId) {
    }
}
