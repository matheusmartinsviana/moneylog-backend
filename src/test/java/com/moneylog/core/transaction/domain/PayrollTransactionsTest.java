package com.moneylog.core.transaction.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeDespesa;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeReceita;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.umaDividaComOPedro;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.umHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.debt.exception.DebtExceptions.SettlementExceedsOutstandingException;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Postings;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.exception.TransactionExceptions.PayrollLoanInvalidException;

@DisplayName("Renda com consignado em folha (ADR-026)")
class PayrollTransactionsTest {

    private static final Competence SEPTEMBER = Competence.parse("2026-09");

    @Test
    @DisplayName("sem juros: conta recebe N, passivo baixa p, renda registra N + p e nenhuma despesa nasce")
    void withoutInterest() {
        Debt debt = umaDividaComOPedro();
        PayrollLoanShare loan = new PayrollLoanShare(debt, Money.of("150.00"), null, null);

        Transaction tx = PayrollTransactions.incomeWithLoan(umHeader(UUID.randomUUID()), Money.of("1000.00"), umaContaCorrente(),
                umaCategoriaDeReceita(), PaymentMethod.TRANSFER, SEPTEMBER, loan);

        assertThat(Postings.sum(tx.entries().stream().map(LedgerEntry::posting).toList()).isZero()).isTrue();
        assertThat(tx.entries()).noneMatch(e -> e.posting().bookType() == BookType.EXPENSE);
        assertThat(tx.entries()).filteredOn(e -> e.posting().bookType() == BookType.INCOME)
                .singleElement().satisfies(e -> {
                    assertThat(e.posting().amount()).isEqualTo(Money.of("1150.00").negate());
                    assertThat(e.posting().competence()).isEqualTo(SEPTEMBER);
                });
        assertThat(tx.entries()).filteredOn(e -> e.posting().bookType() == BookType.PAYABLE)
                .singleElement().satisfies(e -> assertThat(e.posting().amount()).isEqualTo(Money.of("150.00")));
        assertThat(tx.debtId()).isEqualTo(debt.id());
        assertThat(debt.settled()).isEqualTo(Money.of("150.00"));
        assertThat(debt.status()).isEqualTo(DebtStatus.PARTIALLY_SETTLED);
    }

    @Test
    @DisplayName("com juros: o passivo baixa só p - juros e os juros viram despesa, soma continua zero")
    void withInterest() {
        Debt debt = umaDividaComOPedro();
        PayrollLoanShare loan = new PayrollLoanShare(debt, Money.of("150.00"), Money.of("20.00"), umaCategoriaDeDespesa());

        Transaction tx = PayrollTransactions.incomeWithLoan(umHeader(UUID.randomUUID()), Money.of("1000.00"), umaContaCorrente(),
                umaCategoriaDeReceita(), PaymentMethod.TRANSFER, SEPTEMBER, loan);

        assertThat(Postings.sum(tx.entries().stream().map(LedgerEntry::posting).toList()).isZero()).isTrue();
        assertThat(tx.entries()).filteredOn(e -> e.posting().bookType() == BookType.EXPENSE)
                .singleElement().satisfies(e -> assertThat(e.posting().amount()).isEqualTo(Money.of("20.00")));
        assertThat(tx.entries()).filteredOn(e -> e.posting().bookType() == BookType.PAYABLE)
                .singleElement().satisfies(e -> assertThat(e.posting().amount()).isEqualTo(Money.of("130.00")));
        assertThat(debt.settled()).isEqualTo(Money.of("130.00"));
    }

    @Test
    @DisplayName("parcela maior que o saldo da dívida é recusada e nada é baixado")
    void exceedsOutstanding() {
        Debt debt = umaDividaComOPedro();
        PayrollLoanShare loan = new PayrollLoanShare(debt, Money.of("250.00"), null, null);

        assertThatThrownBy(() -> PayrollTransactions.incomeWithLoan(umHeader(UUID.randomUUID()), Money.of("1000.00"), umaContaCorrente(),
                umaCategoriaDeReceita(), PaymentMethod.TRANSFER, SEPTEMBER, loan)).isInstanceOf(SettlementExceedsOutstandingException.class);
        assertThat(debt.settled().isZero()).isTrue();
    }

    @Test
    @DisplayName("juros iguais ou maiores que a parcela são inválidos")
    void interestAtLeastApplied() {
        Debt debt = umaDividaComOPedro();
        assertThatThrownBy(() -> new PayrollLoanShare(debt, Money.of("100.00"), Money.of("100.00"), umaCategoriaDeDespesa()))
                .isInstanceOf(PayrollLoanInvalidException.class);
    }
}
