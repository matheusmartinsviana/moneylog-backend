package com.moneylog.core.finance.app.debts.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.sk.money.Money;

public interface CreateDebtUseCase {

    /**
     * Registra uma dívida <b>sem movimentação</b> (D1 §2.6, PR 01 caso 8): não mexe no caixa. {@code PAYABLE −X / EQUITY +X} (ou
     * {@code RECEIVABLE +X / EQUITY −X}) na data da dívida. Com {@code categoryId} (só em {@code PAYABLE}, S-137: "um amigo pagou
     * meu jantar"): {@code EXPENSE(cat) +X / PAYABLE −X}, consumo +X na competência da data e caixa 0.
     *
     * <p>Com {@code creditorName} (P1, "outras dívidas") e sem {@code personId}: dívida {@code PAYABLE} com credor livre, também contra
     * {@code EQUITY}; sem categoria. Pessoa e credor juntos, ou nenhum dos dois, são {@code 422 DEBT_CREDITOR_INVALID}.
     *
     * @throws com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException pessoa de outro usuário
     * @throws com.moneylog.core.debt.exception.DebtExceptions.PersonArchivedException pessoa arquivada
     * @throws com.moneylog.core.debt.exception.DebtExceptions.DebtCategoryNotAllowedException categoria numa {@code RECEIVABLE}
     */
    DebtView handle(CreateDebtCommand command);

    /** Escopo da chave de idempotência (S-237), próprio desta rota. */
    String IDEMPOTENCY_SCOPE = "POST /v1/debts";

    /**
     * @param idempotencyKey opcional (S-237): com a chave, repetir o mesmo comando devolve a mesma dívida (sem novo lançamento) e
     *                       outro comando com a mesma chave é {@code 409 IDEMPOTENCY_KEY_REUSED}
     */
    record CreateDebtCommand(UUID userId, UUID personId, DebtDirection direction, Money principal, LocalDate date,
                             LocalDate expectedDate, String description, UUID categoryId, UUID idempotencyKey, String creditorName) {

        /** Com chave de idempotência, sem credor livre. */
        public CreateDebtCommand(UUID userId, UUID personId, DebtDirection direction, Money principal, LocalDate date,
                                 LocalDate expectedDate, String description, UUID categoryId, UUID idempotencyKey) {
            this(userId, personId, direction, principal, date, expectedDate, description, categoryId, idempotencyKey, null);
        }

        /** Sem chave de idempotência. */
        public CreateDebtCommand(UUID userId, UUID personId, DebtDirection direction, Money principal, LocalDate date,
                                 LocalDate expectedDate, String description, UUID categoryId) {
            this(userId, personId, direction, principal, date, expectedDate, description, categoryId, null);
        }
    }
}
