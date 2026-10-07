package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.domain.User;

public interface EnsureOwnerUseCase {

    /**
     * Garante que o dono existe (S-82): cria com a tabela vazia; se já existe com outro e-mail, atualiza o e-mail
     * mantendo o id. Nunca cria uma segunda linha.
     */
    OwnerEnsured handle(EnsureOwnerCommand command);

    record EnsureOwnerCommand(String email) {
    }

    /**
     * @param created       o dono foi criado agora (só então {@code OwnerCreated} é publicado, depois do commit)
     * @param previousEmail e-mail antigo quando houve troca; {@code null} nos demais casos
     */
    record OwnerEnsured(User owner, boolean created, String previousEmail) {
    }
}
