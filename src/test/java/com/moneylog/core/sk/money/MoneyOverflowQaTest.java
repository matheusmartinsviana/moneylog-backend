package com.moneylog.core.sk.money;

import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.money.MoneyExceptions.AmountAboveLimitException;
import com.moneylog.core.sk.money.MoneyExceptions.MoneyOverflowException;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;

/**
 * T12 (QA): overflow de {@link Money} nos limites de {@code long} e na agregação das lentes. Nunca pode haver
 * transbordo silencioso (D1 §2.1).
 */
@DisplayName("Money: overflow nos limites (T12, QA)")
class MoneyOverflowQaTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 10);

    @Test
    @DisplayName("string decimal no limite do long: 92233720368547758.07 cabe, .08 transborda; o mínimo negativo cabe")
    void deveRespeitarOLimiteNaCriacao() {
        assertThat(Money.of("92233720368547758.07").cents()).isEqualTo(Long.MAX_VALUE);
        assertThat(Money.of("-92233720368547758.08").cents()).isEqualTo(Long.MIN_VALUE);
        assertThatThrownBy(() -> Money.of("92233720368547758.08")).isInstanceOf(MoneyOverflowException.class);
        assertThatThrownBy(() -> Money.of("-92233720368547758.09")).isInstanceOf(MoneyOverflowException.class);
        assertThatThrownBy(() -> Money.of("99999999999999999999999")).isInstanceOf(MoneyOverflowException.class);
    }

    @Test
    @DisplayName("abs e negate do mínimo, soma de lista e times transbordam com exceção, nunca com wrap")
    void deveFalharSemWrap() {
        Money min = Money.ofCents(Long.MIN_VALUE, CurrencyCode.BRL);
        Money max = Money.ofCents(Long.MAX_VALUE, CurrencyCode.BRL);
        assertThatThrownBy(min::abs).isInstanceOf(MoneyOverflowException.class);
        assertThatThrownBy(min::negate).isInstanceOf(MoneyOverflowException.class);
        assertThatThrownBy(() -> Money.sum(List.of(max, Money.of("0.01")), CurrencyCode.BRL)).isInstanceOf(MoneyOverflowException.class);
        assertThatThrownBy(() -> max.times(-2)).isInstanceOf(MoneyOverflowException.class);
        assertThat(max.add(min)).isEqualTo(Money.of("-0.01"));
    }

    @Test
    @DisplayName("allocate de Long.MAX_VALUE em 36 parcelas soma exatamente o total")
    void deveAlocarNoLimite() {
        Money max = Money.ofCents(Long.MAX_VALUE, CurrencyCode.BRL);
        List<Money> parts = max.allocate(36);
        assertThat(Money.sum(parts, CurrencyCode.BRL)).isEqualTo(max);
        assertThat(parts.getFirst().cents() - parts.get(1).cents()).isEqualTo(Long.MAX_VALUE % 36);
    }

    @Test
    @DisplayName("compra no cartão no teto de S-78 (999.999.999,99) em 36x gera postings balanceados por parcela; 1 centavo acima é recusado")
    void deveAceitarCompraNoLimite() {
        Transaction t = compraNoCalendario(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000bb001"), USER_ID, DATE,
                "x"), AmountLimit.max(CurrencyCode.BRL), CreditCardTestFactory.umCartao(),
                CategoryTestFactory.umaCategoriaDeDespesa(), 36);
        assertThat(t.installments()).hasSize(36);
        assertThat(t.postings()).hasSize(72);
        assertThat(Money.sum(t.installments().stream().map(i -> i.amount()).toList(), CurrencyCode.BRL)).isEqualTo(Money.of("999999999.99"));
        assertThatThrownBy(() -> compraNoCalendario(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000bb004"), USER_ID,
                DATE, "x"), Money.of("1000000000.00"), CreditCardTestFactory.umCartao(), CategoryTestFactory.umaCategoriaDeDespesa(), 1))
                .isInstanceOf(AmountAboveLimitException.class)
                .extracting(e -> ((AmountAboveLimitException) e).code()).isEqualTo("AMOUNT_ABOVE_LIMIT");
    }

    /**
     * BUG-QA-03 (corrigido, S-78): antes não havia teto, e dois gastos individualmente válidos cuja soma passava de
     * {@code Long.MAX_VALUE} centavos travavam toda leitura do razão. Agora o valor gigante é barrado na entrada
     * ({@code AMOUNT_ABOVE_LIMIT}, 422), e mesmo milhares de lançamentos no teto somam sem transbordar.
     */
    @Test
    @DisplayName("BUG-QA-03: valor acima do teto é recusado na entrada; lançamentos no teto não travam a leitura do razão")
    void deveDocumentarOverflowNaAgregacao() {
        var conta = AccountTestFactory.umaContaCorrente();
        var categoria = CategoryTestFactory.umaCategoriaDeDespesa();
        Money metade = Money.ofCents(Long.MAX_VALUE / 2 + 1, CurrencyCode.BRL);
        assertThatThrownBy(() -> AccountTransactions.expense(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000bb002"),
                USER_ID, DATE, "a"), metade, conta, categoria, PaymentMethod.PIX)).isInstanceOf(AmountAboveLimitException.class);

        Money teto = AmountLimit.max(CurrencyCode.BRL);
        List<Transaction> noTeto = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            noTeto.add(AccountTransactions.expense(TransactionHeader.of(UUID.nameUUIDFromBytes(("teto" + i).getBytes()), USER_ID, DATE, "b"),
                    teto, conta, categoria, PaymentMethod.PIX));
        }
        FinancialRecords records = new FinancialRecords(List.of(conta), List.of(), noTeto, List.of());
        assertThat(records.ledger().balance(categoria.book(), DATE)).isEqualTo(teto.times(2_000));
    }

    @Test
    @DisplayName("S-78: o teto vale para saldo inicial (em módulo), limite do cartão, principal da dívida e recorrência")
    void deveAplicarOTetoNosCadastros() {
        Money acima = Money.of("1000000000.00");
        assertThatThrownBy(() -> Account.open(UUID.randomUUID(), USER_ID, "x",
                AccountType.CHECKING, null, acima, DATE)).isInstanceOf(AmountAboveLimitException.class);
        assertThatThrownBy(() -> Account.open(UUID.randomUUID(), USER_ID, "x",
                AccountType.CHECKING, null, acima.negate(), DATE)).isInstanceOf(AmountAboveLimitException.class);
        assertThat(Account.open(UUID.randomUUID(), USER_ID, "x",
                AccountType.CHECKING, null, AmountLimit.max(CurrencyCode.BRL).negate(), DATE).openingBalance())
                .isEqualTo(Money.of("-999999999.99"));
        assertThatThrownBy(() -> CreditCardTestFactory.umCartaoComLimite(acima, 25, 5)).isInstanceOf(AmountAboveLimitException.class);
        assertThatThrownBy(() -> Debt.withoutMovement(UUID.randomUUID(), USER_ID,
                PersonTestFactory.umPedro(), DebtDirection.PAYABLE, acima,
                DATE, null, null)).isInstanceOf(AmountAboveLimitException.class);
        assertThatThrownBy(() -> RecurringTransaction.ofCard(UUID.randomUUID(), USER_ID, acima, "x",
                UUID.randomUUID(), UUID.randomUUID(), Schedule.of(
                        Frequency.MONTHLY, DATE))).isInstanceOf(AmountAboveLimitException.class);
    }
}
