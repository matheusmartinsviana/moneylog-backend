package com.moneylog.infra.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
import com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCase;
import com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCase.FirstAccessPrepared;
import com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCase.PrepareFirstAccessCommand;

/**
 * No boot garante o dono único (S-82) e prepara o primeiro acesso (ADR-015). Cada caso de uso roda (e confirma) a própria
 * transação; só depois, se o dono foi criado, {@link OwnerCreated} é publicado (efeito colateral depois do commit: seed
 * de categorias).
 *
 * <p>O código do primeiro acesso, quando gerado, é impresso <b>uma vez</b> e <b>só no perfil dev</b>. Em produção ele vem
 * de {@code MONEYLOG_OWNER_SETUP_CODE}/{@code _FILE} (secrets) e nunca é logado.
 */
public class OwnerBootstrap implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

    private final EnsureOwnerUseCase ensureOwner;
    private final PrepareFirstAccessUseCase prepareFirstAccess;
    private final MoneylogOwnerProperties owner;
    private final ApplicationEventPublisher publisher;
    private final boolean devProfile;

    public OwnerBootstrap(EnsureOwnerUseCase ensureOwner, PrepareFirstAccessUseCase prepareFirstAccess,
                          MoneylogOwnerProperties owner, ApplicationEventPublisher publisher, boolean devProfile) {
        this.ensureOwner = ensureOwner;
        this.prepareFirstAccess = prepareFirstAccess;
        this.owner = owner;
        this.publisher = publisher;
        this.devProfile = devProfile;
    }

    @Override
    public void run(ApplicationArguments args) {
        OwnerEnsured result = ensureOwner.handle(new EnsureOwnerCommand(owner.email(), owner.resetPassword()));
        if (result.created()) {
            log.info("Dono criado ({}).", mask(result.owner().email()));
            publisher.publishEvent(new OwnerCreated(result.owner().id(), result.owner().email()));
        } else if (result.previousEmail() != null) {
            log.info("E-mail do dono atualizado de {} para {} (id mantido).", mask(result.previousEmail()),
                    mask(result.owner().email()));
        }
        if (result.passwordReset()) {
            log.warn("Senha do dono redefinida (MONEYLOG_OWNER_RESET_PASSWORD): sessões revogadas e primeiro acesso pendente. "
                    + "Remova a variável para não repetir.");
        } else if (owner.resetPassword()) {
            log.warn("MONEYLOG_OWNER_RESET_PASSWORD ainda está ligada: remova-a depois do primeiro acesso.");
        }
        prepareFirstAccess();
    }

    private void prepareFirstAccess() {
        FirstAccessPrepared prepared = this.prepareFirstAccess.handle(
                new PrepareFirstAccessCommand(providedSetupCode(), owner.setupCodeTtl()));
        if (!prepared.setupRequired()) {
            return;
        }
        if (prepared.generatedCode() != null) {
            if (devProfile) {
                log.info("PRIMEIRO ACESSO: código de uso único (válido por {}): {}", owner.setupCodeTtl(), prepared.generatedCode());
            } else {
                // Fora do dev o código gerado não é impresso (log estruturado/centralizado): sem o secret, não há como usar.
                log.warn("Primeiro acesso pendente, mas nenhum MONEYLOG_OWNER_SETUP_CODE informado: defina o código por secret e reinicie.");
            }
        } else {
            log.info("Primeiro acesso pendente: use o código informado em MONEYLOG_OWNER_SETUP_CODE (válido por {}).",
                    owner.setupCodeTtl());
        }
    }

    /** O arquivo, se informado, vale mais que o valor direto (mesma regra do hash). */
    private String providedSetupCode() {
        String file = owner.setupCodeFile();
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalStateException("não foi possível ler MONEYLOG_OWNER_SETUP_CODE_FILE (" + file.strip() + ")", e);
            }
        }
        return owner.setupCode();
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
