package com.moneylog.core.goal.domain;

/**
 * Estágio da meta, sempre derivado na leitura (ADR-016): sem prazo é fantasia; com prazo é objetivo; com prazo e plano é estratégia;
 * com plano e consistência comprovada é consistente; atingida prevalece sobre todos ("sucesso").
 */
public enum GoalStage {
    FANTASY,
    OBJECTIVE,
    STRATEGY,
    CONSISTENT,
    ACHIEVED;

    /** Meses seguidos cumprindo o ritmo para a meta ser {@link #CONSISTENT}. */
    public static final int CONSISTENT_STREAK = 2;

    /**
     * @param hasDeadline  a meta tem prazo
     * @param hasAutoPlan  existe plano automático válido (prazo + alvo; quitar dívidas exige a baseline)
     * @param declaredPlan o dono declarou {@code planNote} ou ao menos uma ação
     * @param streak       meses fechados seguidos cumprindo o ritmo
     */
    public static GoalStage derive(boolean achieved, boolean hasDeadline, boolean hasAutoPlan, boolean declaredPlan, int streak) {
        if (achieved) {
            return ACHIEVED;
        }
        if (!hasDeadline) {
            return FANTASY;
        }
        if (!hasAutoPlan || !declaredPlan) {
            return OBJECTIVE;
        }
        return streak >= CONSISTENT_STREAK ? CONSISTENT : STRATEGY;
    }
}
