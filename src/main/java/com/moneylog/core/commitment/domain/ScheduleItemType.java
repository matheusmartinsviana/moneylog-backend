package com.moneylog.core.commitment.domain;

public enum ScheduleItemType {
    /** Sinal/arras. */
    DOWN_PAYMENT,
    /** Parcela mensal. */
    INSTALLMENT,
    /** Parcela extraordinária (reforço). */
    EXTRAORDINARY,
    /**
     * Financiamento bancário/FGTS: faz parte do saldo nominal, mas <b>não é despesa nem dinheiro recebido</b> e não é pagamento direto do
     * usuário; entra à parte ({@code financingPending}) e nunca no consumo.
     */
    FINANCING
}
