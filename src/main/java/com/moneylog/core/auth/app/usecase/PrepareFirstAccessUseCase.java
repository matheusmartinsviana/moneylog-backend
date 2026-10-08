package com.moneylog.core.auth.app.usecase;

import java.time.Duration;

public interface PrepareFirstAccessUseCase {

    /**
     * No boot: se o dono ainda precisa escolher a senha, grava o hash de um código de uso único (o informado pelo
     * operador ou um gerado agora) com validade. Se não precisa, nada muda.
     */
    FirstAccessPrepared handle(PrepareFirstAccessCommand command);

    /** @param providedCode código vindo de variável de ambiente/secret, ou {@code null}/vazio para gerar um */
    record PrepareFirstAccessCommand(String providedCode, Duration ttl) {
    }

    /**
     * @param setupRequired  o dono precisa escolher a senha
     * @param generatedCode  o código em claro, <b>só</b> quando foi gerado agora (único momento em que existe); senão {@code null}
     */
    record FirstAccessPrepared(boolean setupRequired, String generatedCode) {
    }
}
