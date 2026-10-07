package com.moneylog.core.finance.app.transactions.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.AdjustmentKind;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;

/** Entrada de {@link CreateTransactionUseCase}, um comando por tipo (S-58). */
public sealed interface CreateTransactionCommand {

    UUID userId();

    /** O tipo da movimentação que o comando cria (ou, na edição, o tipo que o corpo declara). */
    TransactionType type();

    /** Campos comuns a todos os tipos. */
    record Common(UUID userId, Money amount, LocalDate date, String description, String notes) {
    }

    /**
     * Dados da dívida que nasce junto com a movimentação (empréstimo, captação ou parte de despesa dividida): a pessoa, a
     * previsão e a descrição (opcionais; sem descrição, a da movimentação). O principal e a data vêm da movimentação.
     */
    record NewDebtSpec(UUID personId, LocalDate expectedDate, String description) {
    }

    /** Parte de uma despesa dividida (D1 §2.6.1): o que a pessoa deve (valor &gt; 0). */
    record Share(Money amount, NewDebtSpec newDebt) {
    }

    /**
     * Despesa paga de conta. {@code shares} (opcional) divide a despesa: cada parte vira uma dívida {@code RECEIVABLE} cuja
     * origem é esta despesa (S-40, S-137); {@code null} ou vazio = despesa comum.
     */
    record Expense(Common common, UUID accountId, UUID categoryId, PaymentMethod method, List<Share> shares)
            implements CreateTransactionCommand {

        public Expense {
            shares = shares == null ? List.of() : List.copyOf(shares);
        }

        /** Despesa comum, sem divisão. */
        public Expense(Common common, UUID accountId, UUID categoryId, PaymentMethod method) {
            this(common, accountId, categoryId, method, List.of());
        }

        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.EXPENSE;
        }
    }

    record Income(Common common, UUID accountId, UUID categoryId, PaymentMethod method) implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.INCOME;
        }
    }

    record Transfer(Common common, UUID fromAccountId, UUID toAccountId) implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.TRANSFER;
        }
    }

    record Investment(Common common, UUID cashAccountId, UUID investmentAccountId, InvestmentDirection direction)
            implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.INVESTMENT;
        }
    }

    record Adjustment(Common common, UUID accountId, AdjustmentDirection direction, AdjustmentKind kind, String reason)
            implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.ADJUSTMENT;
        }
    }

    /** {@code cardId} preenchido é reembolso no cartão (T56); até lá responde {@code TRANSACTION_TYPE_NOT_AVAILABLE}. */
    record Refund(Common common, UUID accountId, UUID categoryId, UUID refundOfTransactionId, UUID cardId)
            implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.REFUND;
        }
    }

    /** Compra no cartão, à vista ({@code installments} = 1) ou parcelada (1..36); o valor é o total da compra. */
    record CardPurchase(Common common, UUID cardId, UUID categoryId, int installments) implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.CARD_PURCHASE;
        }
    }

    /**
     * Dinheiro emprestado (S-6, D1 §2.6): cria a dívida {@code RECEIVABLE} junto ({@code newDebt}). {@code debtId} só serve
     * para recusar com o motivo certo: uma dívida existente nunca recebe outra origem ({@code DEBT_ALREADY_HAS_ORIGIN}).
     */
    record LoanGiven(Common common, UUID accountId, NewDebtSpec newDebt, UUID debtId) implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.LOAN_GIVEN;
        }
    }

    /**
     * Dinheiro recebido, <b>nunca receita</b> (PR 01): devolução de uma {@code RECEIVABLE} ({@code debtId}) ou captação
     * ({@code newDebt}: cria uma {@code PAYABLE}, S-6). Exatamente um dos dois.
     */
    record MoneyReceived(Common common, UUID accountId, UUID debtId, NewDebtSpec newDebt) implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.MONEY_RECEIVED;
        }
    }

    /** Pagamento de uma dívida {@code PAYABLE}: caixa −, consumo 0, saldo devedor da dívida cai. */
    record DebtPayment(Common common, UUID accountId, UUID debtId) implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.DEBT_PAYMENT;
        }
    }

    /** Tipos que existem no domínio mas não nesta etapa: respondem {@code TRANSACTION_TYPE_NOT_AVAILABLE}. */
    record Unavailable(UUID userId, TransactionType type) implements CreateTransactionCommand {
    }
}
