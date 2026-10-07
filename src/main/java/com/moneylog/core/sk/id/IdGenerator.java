package com.moneylog.core.sk.id;

import java.util.UUID;

/**
 * Porta de geração de ids. O id nasce na aplicação (nunca no banco), para o caso de uso conhecer o id antes de gravar
 * (ex.: os lançamentos do saldo inicial apontam para a conta).
 */
public interface IdGenerator {

    UUID next();
}
