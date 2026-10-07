package com.moneylog.core.account.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.OPENING_DATE;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaArquivada;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountFieldRequiredException;
import com.moneylog.core.account.exception.AccountExceptions.AccountOpeningLockedException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.Postings;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

@DisplayName("Account: correção do saldo inicial e flag archivable (T25)")
class AccountOpeningCorrectionTest {

    @Nested
    @DisplayName("correctOpening")
    class CorrigirSaldoInicial {

        @Test
        @DisplayName("muda tipo, saldo e data e devolve o par novo balanceado")
        void deveCorrigirEDevolverOParNovo() {
            Account account = umaContaCorrente();
            LocalDate newDate = LocalDate.of(2026, 9, 15);

            List<Posting> postings = account.correctOpening(AccountType.SAVINGS, Money.of("15000.00"), newDate, false);

            assertThat(account.type()).isEqualTo(AccountType.SAVINGS);
            assertThat(account.openingBalance()).isEqualTo(Money.of("15000.00"));
            assertThat(account.openingDate()).isEqualTo(newDate);
            assertThat(postings).containsExactly(
                    new Posting(BookRef.account(account.id()), Money.of("15000.00"), newDate),
                    new Posting(BookRef.equity(), Money.of("-15000.00"), newDate));
            Postings.assertBalanced(postings);
        }

        @Test
        @DisplayName("saldo zero não gera lançamento; saldo negativo é aceito (S-5)")
        void deveAceitarZeroENegativo() {
            Account account = umaContaCorrente();

            assertThat(account.correctOpening(AccountType.CHECKING, Money.of("0.00"), OPENING_DATE, false)).isEmpty();
            assertThat(account.correctOpening(AccountType.CHECKING, Money.of("-300.00"), OPENING_DATE, false))
                    .extracting(Posting::amount).containsExactly(Money.of("-300.00"), Money.of("300.00"));
        }

        @Test
        @DisplayName("com movimentação ativa lança ACCOUNT_OPENING_LOCKED e não muda nada")
        void deveRecusarComMovimentacao() {
            Account account = umaContaCorrente();

            assertThatThrownBy(() -> account.correctOpening(AccountType.SAVINGS, Money.of("1.00"), OPENING_DATE, true))
                    .isInstanceOf(AccountOpeningLockedException.class).extracting("code").isEqualTo("ACCOUNT_OPENING_LOCKED");

            assertThat(account.type()).isEqualTo(AccountType.CHECKING);
            assertThat(account.openingBalance()).isEqualTo(Money.of("20000.00"));
        }

        @Test
        @DisplayName("recusa campo ausente, moeda diferente de BRL, valor acima do teto e conta arquivada")
        void deveValidar() {
            Account account = umaContaCorrente();

            assertThatThrownBy(() -> account.correctOpening(null, Money.of("1.00"), OPENING_DATE, false))
                    .isInstanceOf(AccountFieldRequiredException.class);
            assertThatThrownBy(() -> account.correctOpening(AccountType.CHECKING, null, OPENING_DATE, false))
                    .isInstanceOf(AccountFieldRequiredException.class);
            assertThatThrownBy(() -> account.correctOpening(AccountType.CHECKING, Money.of("1.00"), null, false))
                    .isInstanceOf(AccountFieldRequiredException.class);
            assertThatThrownBy(() -> account.correctOpening(AccountType.CHECKING, Money.of("1.00", CurrencyCode.of("USD")),
                    OPENING_DATE, false)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> account.correctOpening(AccountType.CHECKING,
                    AmountLimit.max(CurrencyCode.BRL).add(Money.of("0.01")), OPENING_DATE, false)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> umaContaArquivada().correctOpening(AccountType.CHECKING, Money.of("1.00"), OPENING_DATE, false))
                    .isInstanceOf(AccountArchivedException.class);
        }

        @Test
        @DisplayName("openingMatches compara tipo, saldo e data")
        void deveComparar() {
            Account account = umaContaCorrente();

            assertThat(account.openingMatches(AccountType.CHECKING, Money.of("20000.00"), OPENING_DATE)).isTrue();
            assertThat(account.openingMatches(AccountType.SAVINGS, Money.of("20000.00"), OPENING_DATE)).isFalse();
            assertThat(account.openingMatches(AccountType.CHECKING, Money.of("1.00"), OPENING_DATE)).isFalse();
            assertThat(account.openingMatches(AccountType.CHECKING, Money.of("20000.00"), OPENING_DATE.plusDays(1))).isFalse();
        }
    }

    @Nested
    @DisplayName("canBeArchived")
    class PodeSerArquivada {

        private final LocalDate today = LocalDate.of(2026, 10, 10);

        @Test
        @DisplayName("é a mesma checagem do archive, sem mudar a conta")
        void deveAlinharComArchive() {
            Account account = umaContaCorrente();

            assertThat(account.canBeArchived(account.openingPostings(), today)).isFalse();
            assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(account.canBeArchived(List.of(), today)).isTrue();
        }

        @Test
        @DisplayName("saldo zero hoje mas movimentação futura que muda o saldo: não pode")
        void deveContarSaldoFuturo() {
            Account account = umaContaCorrente();
            List<Posting> postings = List.of(new Posting(account.book(), Money.of("50.00"), today.plusDays(10)));

            assertThat(account.canBeArchived(postings, today)).isFalse();
        }

        @Test
        @DisplayName("conta arquivada não pode ser arquivada de novo")
        void naoDeveArquivarArquivada() {
            assertThat(umaContaArquivada().canBeArchived(List.of(), today)).isFalse();
        }
    }
}
