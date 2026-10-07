package com.moneylog.infra.transaction.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.NewDebtSpec;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Share;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.AdjustmentKind;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.infra.exception.RequestValidationException;

import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.groups.Default;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corpo do {@code POST /v1/transactions}, discriminado por {@code type} (contrato: {@code TransactionInput}). Os tipos
 * que existem no domínio mas não nesta etapa caem em {@link Unavailable} e respondem {@code 422}, não {@code 400}
 * (S-58); um {@code type} que nem existe no domínio é {@code 400}. Campo desconhecido é {@code 400} (o contrato tem
 * {@code additionalProperties: false}); o {@code type}, lido como discriminador (visible), é ignorado nos tipos concretos.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type", visible = true,
        defaultImpl = TransactionInput.Unavailable.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TransactionInput.Expense.class, name = "EXPENSE"),
        @JsonSubTypes.Type(value = TransactionInput.Income.class, name = "INCOME"),
        @JsonSubTypes.Type(value = TransactionInput.Transfer.class, name = "TRANSFER"),
        @JsonSubTypes.Type(value = TransactionInput.Investment.class, name = "INVESTMENT"),
        @JsonSubTypes.Type(value = TransactionInput.Adjustment.class, name = "ADJUSTMENT"),
        @JsonSubTypes.Type(value = TransactionInput.Refund.class, name = "REFUND"),
        @JsonSubTypes.Type(value = TransactionInput.CardPurchase.class, name = "CARD_PURCHASE"),
        @JsonSubTypes.Type(value = TransactionInput.LoanGiven.class, name = "LOAN_GIVEN"),
        @JsonSubTypes.Type(value = TransactionInput.MoneyReceived.class, name = "MONEY_RECEIVED"),
        @JsonSubTypes.Type(value = TransactionInput.DebtPayment.class, name = "DEBT_PAYMENT")})
public sealed interface TransactionInput {

    String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";

    CreateTransactionCommand toCommand(UUID userId);

    /** {@code version} do lock otimista: só o {@code PUT} usa (obrigatória lá, o controller confere); no {@code POST} é ignorada. */
    Long version();

    static Common common(UUID userId, String amount, LocalDate date, String description, String notes) {
        return new Common(userId, Money.of(amount, CurrencyCode.BRL), date, description, notes);
    }

    @JsonIgnoreProperties("type")
    record Expense(@NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount, @NotNull @DateInWindow LocalDate date,
                   @NotBlank @Size(max = 200) @NoControlChars String description, @Size(max = 2000) @NoControlChars(allowLineBreaks = true) String notes, @NotNull UUID accountId,
                   @NotNull UUID categoryId, @NotNull PaymentMethod method, @Valid @Size(min = 1, max = 20) List<ShareInput> shares,
                   Long version) implements TransactionInput {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            List<Share> parts = shares == null ? List.of() : shares.stream().map(ShareInput::toShare).toList();
            return new CreateTransactionCommand.Expense(common(userId, amount, date, description, notes), accountId, categoryId, method, parts);
        }
    }

    /** Parte de uma despesa dividida ({@code ExpenseShareInput}): o que a pessoa deve e a dívida que nasce. */
    record ShareInput(@NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount, @NotNull @Valid NewDebtInput newDebt) {
        Share toShare() {
            return new Share(Money.of(amount, CurrencyCode.BRL), newDebt.toSpec());
        }
    }

    /** Dívida que nasce com a movimentação ({@code NewDebtInput}). Validada nos dois grupos (padrão e {@link Strict}). */
    record NewDebtInput(@NotNull(groups = {Default.class, Strict.class}) UUID personId,
                        @DateInWindow(groups = {Default.class, Strict.class}) LocalDate expectedDate,
                        @Size(max = 200, groups = {Default.class, Strict.class}) @NoControlChars(groups = {Default.class, Strict.class}) String description) {
        NewDebtSpec toSpec() {
            return new NewDebtSpec(personId, expectedDate, description);
        }
    }

    @JsonIgnoreProperties("type")
    record Income(@NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount, @NotNull @DateInWindow LocalDate date,
                  @NotBlank @Size(max = 200) @NoControlChars String description, @Size(max = 2000) @NoControlChars(allowLineBreaks = true) String notes, @NotNull UUID accountId,
                  @NotNull UUID categoryId, @NotNull PaymentMethod method, Long version) implements TransactionInput {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            return new CreateTransactionCommand.Income(common(userId, amount, date, description, notes), accountId, categoryId, method);
        }
    }

    @JsonIgnoreProperties("type")
    record Transfer(@NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount, @NotNull @DateInWindow LocalDate date,
                    @NotBlank @Size(max = 200) @NoControlChars String description, @Size(max = 2000) @NoControlChars(allowLineBreaks = true) String notes, @NotNull UUID fromAccountId,
                    @NotNull UUID toAccountId, Long version) implements TransactionInput {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            return new CreateTransactionCommand.Transfer(common(userId, amount, date, description, notes), fromAccountId, toAccountId);
        }
    }

    @JsonIgnoreProperties("type")
    record Investment(@NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount, @NotNull @DateInWindow LocalDate date,
                      @NotBlank @Size(max = 200) @NoControlChars String description, @Size(max = 2000) @NoControlChars(allowLineBreaks = true) String notes,
                      @NotNull UUID cashAccountId, @NotNull UUID investmentAccountId, @NotNull InvestmentDirection direction, Long version)
            implements TransactionInput {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            return new CreateTransactionCommand.Investment(common(userId, amount, date, description, notes), cashAccountId,
                    investmentAccountId, direction);
        }
    }

    @JsonIgnoreProperties("type")
    record Adjustment(@NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount, @NotNull @DateInWindow LocalDate date,
                      @NotBlank @Size(max = 200) @NoControlChars String description, @Size(max = 2000) @NoControlChars(allowLineBreaks = true) String notes, @NotNull UUID accountId,
                      @NotNull AdjustmentDirection direction, AdjustmentKind kind, @NotBlank @Size(max = 500) @NoControlChars String reason, Long version)
            implements TransactionInput {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            return new CreateTransactionCommand.Adjustment(common(userId, amount, date, description, notes), accountId, direction,
                    kind, reason);
        }
    }

    /**
     * Reembolso em conta ({@code accountId}) ou no cartão ({@code cardId}): informe exatamente um dos dois (o contrato
     * descreve a exclusividade com {@code not}/{@code if}; aqui ela vira {@code 400}).
     */
    @JsonIgnoreProperties("type")
    record Refund(@NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount, @NotNull @DateInWindow LocalDate date,
                  @NotBlank @Size(max = 200) @NoControlChars String description, @Size(max = 2000) @NoControlChars(allowLineBreaks = true) String notes, UUID accountId,
                  @NotNull UUID categoryId, UUID refundOfTransactionId, UUID cardId, Long version) implements TransactionInput {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            if (accountId == null && cardId == null) {
                throw new RequestValidationException("accountId", "REQUIRED", "Informe 'accountId' (conta) ou 'cardId' (cartão).");
            }
            if (accountId != null && cardId != null) {
                throw new RequestValidationException("cardId", "INVALID", "Informe 'accountId' ou 'cardId', não os dois.");
            }
            return new CreateTransactionCommand.Refund(common(userId, amount, date, description, notes), accountId, categoryId,
                    refundOfTransactionId, cardId);
        }
    }

    /** Grupo de validação da compra no cartão: o {@code POST} e o {@code PUT} (edição limitada, S-121) validam. */
    interface Strict {
    }

    /**
     * Tipos cujas restrições ficam no grupo {@link Strict}: o controller as aplica no {@code POST} (e no {@code PUT} da compra no
     * cartão). Os de dívida não têm edição ({@code 422 TRANSACTION_NOT_EDITABLE}): o {@code PUT} com o corpo mínimo do contrato
     * ({@code UnavailableTransactionUpdate}) chega ao caso de uso, que recusa.
     */
    interface StrictValidated {
    }

    /** Tipos sem edição por {@code PUT} (emprestar, receber, pagar): o {@code PUT} chega ao caso de uso, que recusa, sem exigir {@code version}. */
    interface NotEditable {
    }

    /**
     * Compra no cartão ({@code CARD_PURCHASE}). Suas restrições ficam no grupo {@link Strict}: o controller as aplica no
     * {@code POST} e no {@code PUT} (corpo inteiro + {@code version}; só descrição, categoria e observação mudam, S-121).
     * O {@code type} não vem do Bean Validation porque o discriminador já o define.
     */
    @JsonIgnoreProperties("type")
    record CardPurchase(@NotNull(groups = Strict.class) @Pattern(regexp = AMOUNT_PATTERN, groups = Strict.class) String amount,
                        @NotNull(groups = Strict.class) @DateInWindow(groups = Strict.class) LocalDate date,
                        @Size(max = 200, groups = Strict.class) @NoControlChars(groups = Strict.class) String description,
                        @Size(max = 2000, groups = Strict.class) @NoControlChars(allowLineBreaks = true, groups = Strict.class) String notes,
                        @NotNull(groups = Strict.class) UUID cardId, @NotNull(groups = Strict.class) UUID categoryId,
                        @NotNull(groups = Strict.class) @Min(value = 1, groups = Strict.class) @Max(value = 36, groups = Strict.class) Integer installments,
                        Long version) implements TransactionInput, StrictValidated {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            return new CreateTransactionCommand.CardPurchase(common(userId, amount, date, description, notes), cardId, categoryId,
                    installments);
        }
    }

    /**
     * Dinheiro emprestado ({@code LoanGivenInput}): informe {@code newDebt} <b>ou</b> {@code debtId} (este só para recusar com o
     * motivo certo). Sem corpo completo (PUT mínimo), vira {@link Unavailable} e o caso de uso recusa a edição.
     */
    @JsonIgnoreProperties("type")
    record LoanGiven(@NotNull(groups = Strict.class) @Pattern(regexp = AMOUNT_PATTERN, groups = Strict.class) String amount,
                     @NotNull(groups = Strict.class) @DateInWindow(groups = Strict.class) LocalDate date,
                     @NotBlank(groups = Strict.class) @Size(max = 200, groups = Strict.class) @NoControlChars(groups = Strict.class) String description,
                     @Size(max = 2000, groups = Strict.class) @NoControlChars(allowLineBreaks = true, groups = Strict.class) String notes,
                     @NotNull(groups = Strict.class) UUID accountId, @Valid NewDebtInput newDebt, UUID debtId, Long version)
            implements TransactionInput, StrictValidated, NotEditable {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            if (amount == null || date == null || accountId == null) {
                return new CreateTransactionCommand.Unavailable(userId, TransactionType.LOAN_GIVEN);
            }
            if (newDebt == null && debtId == null) {
                throw new RequestValidationException("newDebt", "REQUIRED", "Informe 'newDebt' ou 'debtId'.");
            }
            if (newDebt != null && debtId != null) {
                throw new RequestValidationException("debtId", "INVALID", "Informe 'newDebt' ou 'debtId', não os dois.");
            }
            return new CreateTransactionCommand.LoanGiven(common(userId, amount, date, description, notes), accountId,
                    newDebt == null ? null : newDebt.toSpec(), debtId);
        }
    }

    /**
     * Dinheiro recebido ({@code MoneyReceivedInput}): devolução ({@code debtId}) ou captação ({@code newDebt}); exatamente um.
     * Nunca é receita (PR 01).
     */
    @JsonIgnoreProperties("type")
    record MoneyReceived(@NotNull(groups = Strict.class) @Pattern(regexp = AMOUNT_PATTERN, groups = Strict.class) String amount,
                         @NotNull(groups = Strict.class) @DateInWindow(groups = Strict.class) LocalDate date,
                         @NotBlank(groups = Strict.class) @Size(max = 200, groups = Strict.class) @NoControlChars(groups = Strict.class) String description,
                         @Size(max = 2000, groups = Strict.class) @NoControlChars(allowLineBreaks = true, groups = Strict.class) String notes,
                         @NotNull(groups = Strict.class) UUID accountId, UUID debtId, @Valid NewDebtInput newDebt, Long version)
            implements TransactionInput, StrictValidated, NotEditable {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            if (amount == null || date == null || accountId == null) {
                return new CreateTransactionCommand.Unavailable(userId, TransactionType.MONEY_RECEIVED);
            }
            if (debtId == null && newDebt == null) {
                throw new RequestValidationException("debtId", "REQUIRED", "Informe 'debtId' (devolução) ou 'newDebt' (captação).");
            }
            if (debtId != null && newDebt != null) {
                throw new RequestValidationException("newDebt", "INVALID", "Informe 'debtId' ou 'newDebt', não os dois.");
            }
            return new CreateTransactionCommand.MoneyReceived(common(userId, amount, date, description, notes), accountId, debtId,
                    newDebt == null ? null : newDebt.toSpec());
        }
    }

    /** Pagamento de dívida ({@code DebtPaymentInput}). */
    @JsonIgnoreProperties("type")
    record DebtPayment(@NotNull(groups = Strict.class) @Pattern(regexp = AMOUNT_PATTERN, groups = Strict.class) String amount,
                       @NotNull(groups = Strict.class) @DateInWindow(groups = Strict.class) LocalDate date,
                       @NotBlank(groups = Strict.class) @Size(max = 200, groups = Strict.class) @NoControlChars(groups = Strict.class) String description,
                       @Size(max = 2000, groups = Strict.class) @NoControlChars(allowLineBreaks = true, groups = Strict.class) String notes,
                       @NotNull(groups = Strict.class) UUID accountId, @NotNull(groups = Strict.class) UUID debtId, Long version)
            implements TransactionInput, StrictValidated, NotEditable {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            if (amount == null || date == null || accountId == null || debtId == null) {
                return new CreateTransactionCommand.Unavailable(userId, TransactionType.DEBT_PAYMENT);
            }
            return new CreateTransactionCommand.DebtPayment(common(userId, amount, date, description, notes), accountId, debtId);
        }
    }

    /** O contrato deixa esses tipos abertos (campos definidos na etapa de cada um): ignora o que não conhece. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Unavailable(@NotNull TransactionType type, Long version) implements TransactionInput {
        @Override
        public CreateTransactionCommand toCommand(UUID userId) {
            return new CreateTransactionCommand.Unavailable(userId, type);
        }
    }
}
