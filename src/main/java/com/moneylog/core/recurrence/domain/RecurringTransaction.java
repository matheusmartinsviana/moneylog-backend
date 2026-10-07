package com.moneylog.core.recurrence.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.NotAnOccurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceAlreadyRealizedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceSkippedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceEndedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceInactiveException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceReferenceMismatchException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceStateInconsistentException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionType;

/**
 * Recorrência (D1 §2.7): modelo de uma {@code EXPENSE}, {@code INCOME} ou {@code CARD_PURCHASE} à vista, com agenda.
 *
 * <ul>
 *   <li>As ocorrências previstas ({@link #occurrences}) não são movimentações: não geram posting (S-20).</li>
 *   <li>Nada é realizado automaticamente: {@link #realize} é chamado com a confirmação do usuário e cria a
 *       movimentação com {@code recurrenceId + occurrenceDate}; a mesma ocorrência não é realizada duas vezes.</li>
 *   <li>Uma ocorrência pode ser pulada ({@link #skip}) e deixa de aparecer como prevista.</li>
 *   <li>No cartão ({@link #realizeOnCard}), a compra passa pela alocação com lançamento tardio (S-14, S-50): uma
 *       ocorrência confirmada depois de a fatura do ciclo ter sido paga vai para a fatura corrente (A2).</li>
 *   <li>Valor até o teto de S-78; descrição com no máximo {@value TransactionHeader#DESCRIPTION_MAX_LENGTH}
 *       caracteres, o mesmo da movimentação que ela gera.</li>
 * </ul>
 */
public final class RecurringTransaction {

    private static final Set<TransactionType> TYPES = Set.of(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.CARD_PURCHASE);

    private final UUID id;
    private final UUID userId;
    private final TransactionType type;
    private Money amount;
    private String description;
    private UUID categoryId;
    private UUID accountId;
    private UUID cardId;
    private PaymentMethod paymentMethod;
    private Schedule schedule;
    private final Set<LocalDate> realized = new HashSet<>();
    private final Set<LocalDate> skipped = new HashSet<>();
    private boolean active = true;

    private RecurringTransaction(UUID id, UUID userId, TransactionType type, Money amount, String description, UUID categoryId,
                                 UUID accountId, UUID cardId, PaymentMethod paymentMethod, Schedule schedule) {
        if (id == null || userId == null || schedule == null) {
            throw new InvalidRecurrenceException("id, dono, categoria e agenda são obrigatórios.");
        }
        validateModel(type, amount, description, categoryId, accountId, cardId, paymentMethod);
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.description = description;
        this.categoryId = categoryId;
        this.accountId = accountId;
        this.cardId = cardId;
        this.paymentMethod = paymentMethod;
        this.schedule = schedule;
    }

    /** As regras do modelo, as mesmas na criação e na edição ({@link #updateDetails}). */
    private static void validateModel(TransactionType type, Money amount, String description, UUID categoryId, UUID accountId,
                                      UUID cardId, PaymentMethod paymentMethod) {
        if (categoryId == null) {
            throw new InvalidRecurrenceException("id, dono, categoria e agenda são obrigatórios.");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new InvalidRecurrenceException("o modelo só pode ser EXPENSE, INCOME ou CARD_PURCHASE à vista.");
        }
        if (amount == null || !amount.isPositive()) {
            throw new InvalidRecurrenceException("o valor precisa ser maior que zero.");
        }
        AmountLimit.ensureWithinLimit(amount, "amount");
        if (description != null && description.strip().length() > TransactionHeader.DESCRIPTION_MAX_LENGTH) {
            throw new InvalidRecurrenceException("a descrição pode ter no máximo " + TransactionHeader.DESCRIPTION_MAX_LENGTH + " caracteres.");
        }
        boolean card = type == TransactionType.CARD_PURCHASE;
        if (card ? cardId == null || accountId != null || paymentMethod != null : accountId == null || paymentMethod == null || cardId != null) {
            throw new InvalidRecurrenceException(card ? "compra no cartão exige só o cartão." : "despesa e receita exigem conta e meio de pagamento.");
        }
    }

    public static RecurringTransaction ofAccount(UUID id, UUID userId, TransactionType type, Money amount, String description,
                                                 UUID categoryId, UUID accountId, PaymentMethod method, Schedule schedule) {
        return new RecurringTransaction(id, userId, type, amount, description, categoryId, accountId, null, method, schedule);
    }

    public static RecurringTransaction ofCard(UUID id, UUID userId, Money amount, String description, UUID categoryId,
                                              UUID cardId, Schedule schedule) {
        return new RecurringTransaction(id, userId, TransactionType.CARD_PURCHASE, amount, description, categoryId, null, cardId, null, schedule);
    }

    /**
     * Reidrata uma recorrência já persistida (usado pelo mapper da infra), com as ocorrências já realizadas e puladas.
     * Não consulta relógio nem cria movimentação. Estado corrompido (campos que não batem com o tipo, data que não é
     * ocorrência da agenda, data ao mesmo tempo realizada e pulada) lança {@link RecurrenceStateInconsistentException}.
     */
    public static RecurringTransaction restore(UUID id, UUID userId, TransactionType type, Money amount, String description,
                                               UUID categoryId, UUID accountId, UUID cardId, PaymentMethod paymentMethod,
                                               Schedule schedule, Collection<LocalDate> realized, Collection<LocalDate> skipped,
                                               boolean active) {
        RecurringTransaction recurrence;
        try {
            recurrence = new RecurringTransaction(id, userId, type, amount, description, categoryId, accountId, cardId,
                    paymentMethod, schedule);
        } catch (InvalidRecurrenceException e) {
            throw new RecurrenceStateInconsistentException(e.getMessage(), e);
        }
        for (LocalDate date : realized) {
            recurrence.restoreDate(date, "realizada", recurrence.realized);
        }
        for (LocalDate date : skipped) {
            recurrence.restoreDate(date, "pulada", recurrence.skipped);
        }
        recurrence.active = active;
        return recurrence;
    }

    private void restoreDate(LocalDate date, String kind, Set<LocalDate> target) {
        if (date == null || !schedule.isOccurrence(date)) {
            throw new RecurrenceStateInconsistentException("a data " + date + " (" + kind + ") não é ocorrência da agenda.");
        }
        if (realized.contains(date) || skipped.contains(date)) {
            throw new RecurrenceStateInconsistentException("a ocorrência de " + date + " aparece como realizada e/ou pulada mais de uma vez.");
        }
        target.add(date);
    }

    /** Ocorrências já realizadas, em ordem de data (o que o gateway grava junto da recorrência). */
    public List<LocalDate> realizedDates() {
        return realized.stream().sorted().toList();
    }

    /** Ocorrências puladas, em ordem de data. */
    public List<LocalDate> skippedDates() {
        return skipped.stream().sorted().toList();
    }

    /** Ocorrências previstas em {@code [from, to]}: agendadas, nem realizadas nem puladas. */
    public List<Occurrence> occurrences(LocalDate from, LocalDate to) {
        if (!active) {
            return List.of();
        }
        return schedule.datesBetween(from, to).stream()
                .filter(d -> !realized.contains(d) && !skipped.contains(d))
                .map(d -> new Occurrence(id, d, amount, direction(), description))
                .toList();
    }

    /**
     * Realiza uma ocorrência de despesa ou receita com a confirmação do usuário (S-20), criando a movimentação com a
     * origem {@code RECURRENCE}. O valor pode ser o real (ex.: conta de luz); {@code null} usa o do modelo.
     */
    public Transaction realize(LocalDate occurrenceDate, UUID transactionId, Money actualAmount, Account account, Category category) {
        ensureActive();
        ensurePending(occurrenceDate);
        if (type == TransactionType.CARD_PURCHASE) {
            throw new RecurrenceReferenceMismatchException("conta");
        }
        TransactionHeader header = header(occurrenceDate, transactionId, category);
        Account validAccount = requireAccount(account);
        Money value = actualAmount == null ? amount : actualAmount;
        Transaction transaction = type == TransactionType.EXPENSE
                ? AccountTransactions.expense(header, value, validAccount, category, paymentMethod)
                : AccountTransactions.income(header, value, validAccount, category, paymentMethod);
        realized.add(occurrenceDate);
        return transaction;
    }

    /**
     * Realiza uma ocorrência de compra no cartão (à vista). A fatura é escolhida pelo status das faturas em
     * {@code today} (S-14, S-50): ciclo fechado e não pago recebe a compra como lançamento tardio; ciclo já pago manda a
     * compra para a fatura corrente. Nunca entra em fatura {@code PAID} (A2).
     *
     * @param chain cadeia de faturas do cartão
     * @param today "hoje" no fuso do usuário
     */
    public Transaction realizeOnCard(LocalDate occurrenceDate, UUID transactionId, Money actualAmount, CreditCard card,
                                     InvoiceChain chain, LocalDate today, Category category) {
        ensureActive();
        ensurePending(occurrenceDate);
        if (type != TransactionType.CARD_PURCHASE) {
            throw new RecurrenceReferenceMismatchException("cartão");
        }
        TransactionHeader header = header(occurrenceDate, transactionId, category);
        CreditCard validCard = requireCard(card);
        Money value = actualAmount == null ? amount : actualAmount;
        Transaction transaction = CardTransactions.purchase(header, value, validCard, category, 1, chain, today);
        realized.add(occurrenceDate);
        return transaction;
    }

    private TransactionHeader header(LocalDate occurrenceDate, UUID transactionId, Category category) {
        if (category == null || !category.id().equals(categoryId)) {
            throw new RecurrenceReferenceMismatchException("categoria");
        }
        return new TransactionHeader(transactionId, userId, occurrenceDate, description, null,
                TransactionOrigin.recurrence(id, occurrenceDate));
    }

    /** Pula uma ocorrência prevista: ela deixa de ser compromisso. */
    public void skip(LocalDate occurrenceDate) {
        ensureActive();
        ensurePending(occurrenceDate);
        skipped.add(occurrenceDate);
    }

    /** Marca uma ocorrência como já realizada (reidratação a partir das movimentações gravadas). */
    public void markRealized(LocalDate occurrenceDate) {
        ensurePending(occurrenceDate);
        realized.add(occurrenceDate);
    }

    public void deactivate() {
        active = false;
    }

    /**
     * Edita os dados do modelo, que valem para as ocorrências <b>ainda não realizadas</b> (S-139): valor, descrição, categoria e
     * conta/meio de pagamento (ou cartão). O tipo, a frequência e o início não mudam (a agenda é calculada da âncora, D1 §2.7);
     * para mudar, encerra e cria outra. As ocorrências já realizadas continuam como estão (são movimentações). O modelo editado
     * segue as mesmas regras da criação ({@code RECURRENCE_INVALID}).
     */
    public void updateDetails(Money newAmount, String newDescription, UUID newCategoryId, UUID newAccountId, PaymentMethod newMethod,
                              UUID newCardId) {
        validateModel(type, newAmount, newDescription, newCategoryId, newAccountId, newCardId, newMethod);
        this.amount = newAmount;
        this.description = newDescription;
        this.categoryId = newCategoryId;
        this.accountId = newAccountId;
        this.cardId = newCardId;
        this.paymentMethod = newMethod;
    }

    /**
     * Encerra a recorrência na data (nada é apagado): as ocorrências depois dela deixam de existir. A data não pode ser anterior
     * ao início nem a uma ocorrência já realizada ou pulada ({@code RECURRENCE_INVALID}).
     */
    public void endOn(LocalDate end) {
        if (end == null) {
            throw new InvalidRecurrenceException("a data de encerramento é obrigatória.");
        }
        changeEnd(end);
    }

    /** Define (ou, com {@code null}, remove) o fim da agenda, com as mesmas guardas de {@link #endOn}. */
    public void changeEnd(LocalDate end) {
        if (end != null) {
            for (LocalDate date : realized) {
                requireNotBefore(end, date, "realizada");
            }
            for (LocalDate date : skipped) {
                requireNotBefore(end, date, "pulada");
            }
        }
        this.schedule = new Schedule(schedule.frequency(), schedule.interval(), schedule.unit(), schedule.startDate(), end,
                schedule.maxOccurrences());
    }

    private static void requireNotBefore(LocalDate end, LocalDate date, String kind) {
        if (end.isBefore(date)) {
            throw new InvalidRecurrenceException("o fim (" + end + ") não pode ser anterior à ocorrência " + kind + " de " + date + ".");
        }
    }

    public FlowDirection direction() {
        return type == TransactionType.INCOME ? FlowDirection.IN : FlowDirection.OUT;
    }

    /** Recorrência desativada não aceita realizar nem pular (S-234): a desativação é definitiva, não há reativação. */
    private void ensureActive() {
        if (!active) {
            throw new RecurrenceInactiveException();
        }
    }

    /**
     * Encerrada (S-235): a agenda não tem ocorrência em {@code today} nem depois (fim anterior a hoje ou quantidade máxima esgotada).
     * É derivado, não gravado; não depende de {@code active}. As ocorrências vencidas e não realizadas continuam pendentes (S-20).
     */
    public boolean isEnded(LocalDate today) {
        return !schedule.hasOccurrenceOnOrAfter(today);
    }

    /** Recusa remover o fim (S-236) quando isso reabriria uma recorrência encerrada: está encerrada por {@code endDate} e a agenda sem o fim teria futuro. */
    public void ensureEndRemovalDoesNotReopen(LocalDate today) {
        if (isEnded(today) && schedule.endDate() != null && schedule.until(null).hasOccurrenceOnOrAfter(today)) {
            throw new RecurrenceEndedException();
        }
    }

    private void ensurePending(LocalDate date) {
        if (date == null || !schedule.isOccurrence(date)) {
            throw new NotAnOccurrenceException(date);
        }
        if (realized.contains(date)) {
            throw new OccurrenceAlreadyRealizedException(date);
        }
        if (skipped.contains(date)) {
            throw new OccurrenceSkippedException(date);
        }
    }

    private Account requireAccount(Account account) {
        if (account == null || !account.id().equals(accountId)) {
            throw new RecurrenceReferenceMismatchException("conta");
        }
        return account;
    }

    private CreditCard requireCard(CreditCard card) {
        if (card == null || !card.id().equals(cardId)) {
            throw new RecurrenceReferenceMismatchException("cartão");
        }
        return card;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public TransactionType type() {
        return type;
    }

    public Money amount() {
        return amount;
    }

    public String description() {
        return description;
    }

    public UUID categoryId() {
        return categoryId;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID cardId() {
        return cardId;
    }

    public PaymentMethod paymentMethod() {
        return paymentMethod;
    }

    public Schedule schedule() {
        return schedule;
    }

    public boolean isActive() {
        return active;
    }
}
