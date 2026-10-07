package com.moneylog.infra.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;

import com.moneylog.core.auth.app.OwnerCreated;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.EnsureOwnerCommand;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.OwnerEnsured;

/**
 * No boot garante o dono único (S-82). O caso de uso roda (e confirma) a própria transação; só depois, se o dono foi
 * criado, {@link OwnerCreated} é publicado (efeito colateral depois do commit: seed de categorias).
 */
public class OwnerBootstrap implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

    private final EnsureOwnerUseCase ensureOwner;
    private final String email;
    private final ApplicationEventPublisher publisher;

    public OwnerBootstrap(EnsureOwnerUseCase ensureOwner, String email, ApplicationEventPublisher publisher) {
        this.ensureOwner = ensureOwner;
        this.email = email;
        this.publisher = publisher;
    }

    @Override
    public void run(ApplicationArguments args) {
        OwnerEnsured result = ensureOwner.handle(new EnsureOwnerCommand(email));
        if (result.created()) {
            log.info("Dono criado ({}).", mask(result.owner().email()));
            publisher.publishEvent(new OwnerCreated(result.owner().id(), result.owner().email()));
        } else if (result.previousEmail() != null) {
            log.info("E-mail do dono atualizado de {} para {} (id mantido).", mask(result.previousEmail()),
                    mask(result.owner().email()));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** {@code dono@moneylog.test} → {@code d***@moneylog.test}. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
