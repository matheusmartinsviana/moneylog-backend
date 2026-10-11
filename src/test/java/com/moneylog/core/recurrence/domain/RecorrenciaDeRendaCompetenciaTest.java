package com.moneylog.core.recurrence.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.ALIMENTACAO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.SALARIO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeReceita;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.TX_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Recorrência de receita: competência da renda das ocorrências (ADR-017)")
class RecorrenciaDeRendaCompetenciaTest {

    private static final UUID REC_ID = UUID.fromString("00000000-0000-0000-0000-0000000c0c12");

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static RecurringTransaction adiantamento(int offset) {
        return RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.INCOME, Money.of("1500.00"), "Adiantamento", SALARIO_ID, NUBANK_ID,
                PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, d("2026-09-16")), offset);
    }

    @Test
    @DisplayName("deslocamento 1: a ocorrência de 16/09 realizada é renda de outubro; o caixa fica em 16/09")
    void deveHerdarOMesSeguinte() {
        Transaction t = adiantamento(1).realize(d("2026-09-16"), TX_ID, null, umaContaCorrente(), umaCategoriaDeReceita());

        assertThat(t.date()).isEqualTo(d("2026-09-16"));
        assertThat(t.incomeCompetence()).isEqualTo(Competence.of(2026, 10));
        assertThat(t.postings().stream().filter(p -> p.bookType() == BookType.INCOME).findFirst().orElseThrow().competence())
                .isEqualTo(Competence.of(2026, 10));
        assertThat(t.postings().stream().filter(p -> p.bookType() == BookType.ACCOUNT).findFirst().orElseThrow().effectiveDate())
                .isEqualTo(d("2026-09-16"));
    }

    @Test
    @DisplayName("virada de ano: 16/12 + 1 mês = janeiro do ano seguinte")
    void deveVirarOAno() {
        RecurringTransaction r = adiantamento(1);

        assertThat(r.realize(d("2026-12-16"), TX_ID, null, umaContaCorrente(), umaCategoriaDeReceita()).incomeCompetence())
                .isEqualTo(Competence.of(2027, 1));
    }

    @Test
    @DisplayName("deslocamento 0 (padrão): a ocorrência fica sem competência declarada, no mês da data")
    void deveManterOMesDaData() {
        for (RecurringTransaction r : List.of(adiantamento(0), RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.INCOME,
                Money.of("1.00"), "x", SALARIO_ID, NUBANK_ID, PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, d("2026-09-16"))))) {
            Transaction t = r.realize(d("2026-09-16"), TX_ID, null, umaContaCorrente(), umaCategoriaDeReceita());
            assertThat(t.incomeCompetence()).isNull();
            assertThat(r.incomeCompetenceOffsetMonths()).isZero();
        }
    }

    @Test
    @DisplayName("deslocamento só 0 ou 1 e só em receita; despesa e cartão recusam (RECURRENCE_INVALID)")
    void deveValidarODeslocamento() {
        assertThatThrownBy(() -> adiantamento(2)).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> adiantamento(-1)).isInstanceOf(InvalidRecurrenceException.class);
        assertThatThrownBy(() -> RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.EXPENSE, Money.of("1.00"), "x", ALIMENTACAO_ID,
                NUBANK_ID, PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, d("2026-09-16")), 1)).isInstanceOf(InvalidRecurrenceException.class);
        RecurringTransaction despesa = RecurringTransaction.ofAccount(REC_ID, USER_ID, TransactionType.EXPENSE, Money.of("1.00"), "x", ALIMENTACAO_ID,
                NUBANK_ID, PaymentMethod.PIX, Schedule.of(Frequency.MONTHLY, d("2026-09-16")));
        assertThatThrownBy(() -> despesa.updateDetails(Money.of("1.00"), "x", ALIMENTACAO_ID, NUBANK_ID, PaymentMethod.PIX, null, 1))
                .isInstanceOf(InvalidRecurrenceException.class);
    }

    @Test
    @DisplayName("editar o deslocamento vale só para as ocorrências ainda não realizadas; as realizadas não mudam")
    void deveEditarSemMexerNasRealizadas() {
        RecurringTransaction r = adiantamento(1);
        Transaction antes = r.realize(d("2026-09-16"), TX_ID, null, umaContaCorrente(), umaCategoriaDeReceita());

        r.updateDetails(Money.of("1500.00"), "Adiantamento", SALARIO_ID, NUBANK_ID, PaymentMethod.PIX, null, 0);
        Transaction depois = r.realize(d("2026-10-16"), UUID.fromString("00000000-0000-0000-0000-00000000aa77"), null, umaContaCorrente(),
                umaCategoriaDeReceita());

        assertThat(antes.incomeCompetence()).isEqualTo(Competence.of(2026, 10));
        assertThat(depois.incomeCompetence()).isNull();
        assertThat(r.incomeCompetenceOffsetMonths()).isZero();
        r.updateDetails(Money.of("1500.00"), "Adiantamento", SALARIO_ID, NUBANK_ID, PaymentMethod.PIX, null, 1);
        r.updateDetails(Money.of("1600.00"), "Adiantamento", SALARIO_ID, NUBANK_ID, PaymentMethod.PIX, null);
        assertThat(r.incomeCompetenceOffsetMonths()).isEqualTo(1);
    }

    @Test
    @DisplayName("reidratar devolve o deslocamento")
    void deveReidratar() {
        RecurringTransaction r = adiantamento(1);

        RecurringTransaction restored = RecurringTransaction.restore(r.id(), r.userId(), r.type(), r.amount(), r.description(), r.categoryId(),
                r.accountId(), r.cardId(), r.paymentMethod(), r.schedule(), List.of(), List.of(), true, 1);

        assertThat(restored.incomeCompetenceOffsetMonths()).isEqualTo(1);
    }
}
