package com.moneylog.core.auth.app.usecase;

import com.moneylog.core.auth.domain.User;

public interface EnsureOwnerUseCase {

    /**
     * Garante que o dono existe (S-82): cria com a tabela vazia; se já existe com outro e-mail, atualiza o e-mail
     * mantendo o id. Nunca cria uma segunda linha. Com {@code resetPassword}, apaga a senha do banco e marca o
     * primeiro acesso como pendente (ADR-015) revogando todas as sessões; os dados de negócio não são tocados.
     */
    OwnerEnsured handle(EnsureOwnerCommand command);

    record EnsureOwnerCommand(String email, boolean resetPassword) {

        public EnsureOwnerCommand(String email) {
            this(email, false);
        }
    }

    /**
     * @param created       o dono foi criado agora (só então {@code OwnerCreated} é publicado, depois do commit)
     * @param previousEmail e-mail antigo quando houve troca; {@code null} nos demais casos
     * @param passwordReset a senha foi redefinida agora (primeiro acesso pendente)
     */
    record OwnerEnsured(User owner, boolean created, String previousEmail, boolean passwordReset) {

        public OwnerEnsured(User owner, boolean created, String previousEmail) {
            this(owner, created, previousEmail, false);
        }
    }
}
