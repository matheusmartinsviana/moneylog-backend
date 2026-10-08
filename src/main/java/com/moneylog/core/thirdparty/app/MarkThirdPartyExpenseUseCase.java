package com.moneylog.core.thirdparty.app;

import java.util.UUID;

import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;

public interface MarkThirdPartyExpenseUseCase {

    /**
     * Marca a despesa (compra no cartão, inclusive as parcelas futuras, ou despesa em conta) como de uma pessoa. Não muda nenhum
     * lançamento: o consumo segue do usuário até o reembolso ({@code REFUND} ligado à compra). Outra marca na mesma despesa →
     * {@code 409/422 THIRD_PARTY_EXPENSE_ALREADY_MARKED}; despesa estornada ou de outro tipo → {@code 422 THIRD_PARTY_EXPENSE_INVALID}.
     * Mesma chave + mesmo corpo devolve a mesma marca.
     */
    ThirdPartyExpense handle(MarkThirdPartyExpenseCommand command);

    record MarkThirdPartyExpenseCommand(UUID userId, UUID transactionId, UUID personId, String note, com.moneylog.core.sk.money.Money amount,
                                        UUID idempotencyKey) {
    }

    String IDEMPOTENCY_SCOPE = "POST /v1/third-party-expenses";
}
