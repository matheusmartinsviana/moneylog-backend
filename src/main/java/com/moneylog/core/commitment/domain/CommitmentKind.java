package com.moneylog.core.commitment.domain;

/**
 * Natureza do compromisso acompanhado (ADR-014). Nenhum dos dois é dívida própria no sentido de {@code Debt}: não toca caixa, consumo,
 * patrimônio nem o total de dívidas; é uma visão à parte, calculada só pelo backend.
 */
public enum CommitmentKind {
    /** Empréstimo no nome de terceiro que o usuário acompanha/paga: parcelas pagas e restantes, saldo possivelmente desconhecido. */
    TRACKED_THIRD_PARTY,
    /** Projeto de longo prazo com cronograma próprio (imóvel na planta): vencimentos e valores diferentes, progresso pago. */
    LONG_TERM_PROJECT
}
