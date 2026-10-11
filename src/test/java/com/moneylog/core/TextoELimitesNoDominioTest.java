package com.moneylog.core;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.account.exception.AccountExceptions.AccountInstitutionTooLongException;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.CardInstitutionTooLongException;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.exception.DebtExceptions.DebtDescriptionTooLongException;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.money.Money;

@DisplayName("Limites de texto do domínio = colunas (BUG-QA-05)")
class TextoELimitesNoDominioTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    @Test
    @DisplayName("Debt.description: 200 passa, 201 lança DebtDescriptionTooLongException (BusinessRule, não erro de banco)")
    void deveLimitarADescricaoDaDivida() {
        assertThat(divida("x".repeat(200)).description()).hasSize(200);
        assertThat(divida("  " + "x".repeat(200) + "  ").description()).hasSize(200);
        assertThatThrownBy(() -> divida("x".repeat(201))).isInstanceOf(DebtDescriptionTooLongException.class)
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    @DisplayName("institution da conta: 100 passa, 101 lança, no cadastro e na edição")
    void deveLimitarAInstituicaoDaConta() {
        Account conta = Account.open(UUID.randomUUID(), USER_ID, "Conta", AccountType.CHECKING, "i".repeat(100), Money.of("0.00"), DAY);
        assertThat(conta.institution()).hasSize(100);
        assertThatThrownBy(() -> conta.updateDetails("Conta", "i".repeat(101))).isInstanceOf(AccountInstitutionTooLongException.class);
        assertThat(conta.institution()).as("edição recusada não muda nada").hasSize(100);
        assertThatThrownBy(() -> Account.open(UUID.randomUUID(), USER_ID, "Conta", AccountType.CHECKING, "i".repeat(101), Money.of("0.00"), DAY))
                .isInstanceOf(AccountInstitutionTooLongException.class);
    }

    @Test
    @DisplayName("institution do cartão: 100 passa, 101 lança, no cadastro e na edição")
    void deveLimitarAInstituicaoDoCartao() {
        CreditCard card = CreditCard.create(UUID.randomUUID(), USER_ID, "Cartão", "i".repeat(100), Money.of("100.00"), 25, 5, null, DAY);
        assertThatThrownBy(() -> card.updateDetails("Cartão", "i".repeat(101), Money.of("100.00"), null))
                .isInstanceOf(CardInstitutionTooLongException.class);
        assertThat(card.institution()).hasSize(100);
        assertThatThrownBy(() -> CreditCard.create(UUID.randomUUID(), USER_ID, "Cartão", "i".repeat(101), Money.of("100.00"), 25, 5, null, DAY))
                .isInstanceOf(CardInstitutionTooLongException.class);
    }

    @Test
    @DisplayName("descrição da recorrência acompanha a da movimentação (200)")
    void deveLimitarADescricaoDaRecorrencia() {
        UUID id = UUID.randomUUID();
        Schedule schedule = Schedule.of(Frequency.MONTHLY, DAY);
        assertThat(RecurringTransaction.ofCard(id, USER_ID, Money.of("1.00"), "x".repeat(200), id, id, schedule).description()).hasSize(200);
        assertThatThrownBy(() -> RecurringTransaction.ofCard(id, USER_ID, Money.of("1.00"), "x".repeat(201), id, id, schedule))
                .isInstanceOf(InvalidRecurrenceException.class);
    }

    private static Debt divida(String descricao) {
        return Debt.withoutMovement(UUID.randomUUID(), USER_ID, PersonTestFactory.umPedro(), DebtDirection.PAYABLE, Money.of("200.00"), DAY,
                null, descricao);
    }
}
