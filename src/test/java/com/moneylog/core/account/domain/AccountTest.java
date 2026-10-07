package com.moneylog.core.account.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.OPENING_DATE;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaArquivada;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaDoTipo;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaNoStatus;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaSemSaldo;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.account.exception.AccountExceptions.AccountAlreadyArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountCurrencyNotSupportedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountFieldRequiredException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNameRequiredException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNameTooLongException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.account.exception.AccountExceptions.AccountOwnerRequiredException;
import com.moneylog.core.account.exception.AccountExceptions.AccountWithBalanceCannotBeArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountWithFutureBalanceCannotBeArchivedException;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.NotFoundException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.Postings;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

@DisplayName("Account")
class AccountTest {

    @Nested
    @DisplayName("saldo inicial")
    class SaldoInicial {

        @Test
        @DisplayName("corrente com 20.000,00 em 01/10/2026 gera ACCOUNT +20.000,00 e EQUITY −20.000,00 (Σ = 0)")
        void deveGerarPostingsDeAbertura() {
            Account nubank = umaContaCorrente();

            List<Posting> postings = nubank.openingPostings();

            assertThat(postings).containsExactly(
                    new Posting(BookRef.account(NUBANK_ID), Money.of("20000.00"), LocalDate.of(2026, 10, 1)),
                    new Posting(BookRef.equity(), Money.of("-20000.00"), LocalDate.of(2026, 10, 1)));
            assertThatCode(() -> Postings.assertBalanced(postings)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("saldo inicial negativo (cheque especial, S-5) inverte os sinais")
        void deveAceitarSaldoInicialNegativo() {
            Account conta = Account.open(NUBANK_ID, USER_ID, "Nubank", AccountType.CHECKING, null, Money.of("-150.00"), OPENING_DATE);

            assertThat(conta.openingPostings()).containsExactly(
                    new Posting(BookRef.account(NUBANK_ID), Money.of("-150.00"), OPENING_DATE),
                    new Posting(BookRef.equity(), Money.of("150.00"), OPENING_DATE));
        }

        @Test
        @DisplayName("saldo inicial zero não gera lançamento")
        void naoDeveGerarPostingComSaldoZero() {
            assertThat(umaContaSemSaldo().openingPostings()).isEmpty();
        }
    }

    @Nested
    @DisplayName("liquidez")
    class Liquidez {

        @ParameterizedTest(name = "{0} é CASH")
        @EnumSource(value = AccountType.class, names = {"CHECKING", "SAVINGS", "WALLET"})
        @DisplayName("corrente, poupança e carteira são CASH")
        void deveSerCash(AccountType tipo) {
            assertThat(umaContaDoTipo(tipo).liquidity()).isEqualTo(LiquidityClass.CASH);
        }

        @ParameterizedTest(name = "{0} é INVESTMENT")
        @EnumSource(value = AccountType.class, names = "INVESTMENT")
        @DisplayName("investimento é INVESTMENT")
        void deveSerInvestimento(AccountType tipo) {
            assertThat(umaContaDoTipo(tipo).liquidity()).isEqualTo(LiquidityClass.INVESTMENT);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(AccountType.class)
        @DisplayName("todo tipo tem uma classe de liquidez")
        void todoTipoTemLiquidez(AccountType tipo) {
            assertThat(tipo.liquidity()).isNotNull();
        }
    }

    @Nested
    @DisplayName("invariantes de criação")
    class Invariantes {

        @ParameterizedTest(name = "nome \"{0}\"")
        @NullSource
        @ValueSource(strings = {"", "   ", "\t"})
        @DisplayName("nome em branco lança AccountNameRequiredException")
        void deveRejeitarNomeEmBranco(String nome) {
            assertThatThrownBy(() -> Account.open(NUBANK_ID, USER_ID, nome, AccountType.CHECKING, null, Money.of("0.00"), OPENING_DATE))
                    .isInstanceOf(AccountNameRequiredException.class)
                    .isInstanceOf(BusinessRuleException.class);
        }

        @Test
        @DisplayName("nome com mais de 100 caracteres é rejeitado; espaços nas pontas são removidos")
        void deveLimitarONome() {
            assertThatThrownBy(() -> Account.open(NUBANK_ID, USER_ID, "x".repeat(101), AccountType.CHECKING, null, Money.of("0.00"), OPENING_DATE))
                    .isInstanceOf(AccountNameTooLongException.class);
            assertThat(Account.open(NUBANK_ID, USER_ID, "  Nubank  ", AccountType.CHECKING, "  ", Money.of("0.00"), OPENING_DATE))
                    .satisfies(c -> {
                        assertThat(c.name()).isEqualTo("Nubank");
                        assertThat(c.institution()).isNull();
                    });
        }

        @Test
        @DisplayName("moeda diferente de BRL lança AccountCurrencyNotSupportedException (S-2)")
        void deveRejeitarMoedaDiferenteDeBrl() {
            Money dolares = Money.of("10.00", CurrencyCode.of("USD"));

            assertThatThrownBy(() -> Account.open(NUBANK_ID, USER_ID, "Conta USD", AccountType.CHECKING, null, dolares, OPENING_DATE))
                    .isInstanceOf(AccountCurrencyNotSupportedException.class)
                    .extracting("code").isEqualTo("ACCOUNT_CURRENCY_NOT_SUPPORTED");
        }

        @Test
        @DisplayName("userId nulo lança AccountOwnerRequiredException, não NullPointerException")
        void deveRejeitarUsuarioNulo() {
            assertThatThrownBy(() -> Account.open(NUBANK_ID, null, "Nubank", AccountType.CHECKING, null, Money.of("0.00"), OPENING_DATE))
                    .isInstanceOf(AccountOwnerRequiredException.class);
        }

        static Stream<Arguments> camposObrigatorios() {
            Money zero = Money.of("0.00");
            return Stream.of(
                    Arguments.of("id", (Runnable) () -> Account.open(null, USER_ID, "N", AccountType.CHECKING, null, zero, OPENING_DATE)),
                    Arguments.of("type", (Runnable) () -> Account.open(NUBANK_ID, USER_ID, "N", null, null, zero, OPENING_DATE)),
                    Arguments.of("openingBalance", (Runnable) () -> Account.open(NUBANK_ID, USER_ID, "N", AccountType.CHECKING, null, null, OPENING_DATE)),
                    Arguments.of("openingDate", (Runnable) () -> Account.open(NUBANK_ID, USER_ID, "N", AccountType.CHECKING, null, zero, null)),
                    Arguments.of("status", (Runnable) () -> Account.restore(NUBANK_ID, USER_ID, "N", AccountType.CHECKING, null, zero, OPENING_DATE, null, 0L)));
        }

        @ParameterizedTest(name = "{0} ausente")
        @MethodSource("camposObrigatorios")
        @DisplayName("campos obrigatórios ausentes lançam AccountFieldRequiredException")
        void deveExigirCamposObrigatorios(String campo, Runnable criacao) {
            assertThatThrownBy(criacao::run)
                    .isInstanceOf(AccountFieldRequiredException.class)
                    .hasMessageContaining(campo);
        }

        @Test
        @DisplayName("a conta nova nasce ACTIVE, versão 0, BRL")
        void deveNascerAtiva() {
            Account nubank = umaContaCorrente();

            assertThat(nubank.status()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(nubank.isActive()).isTrue();
            assertThat(nubank.version()).isZero();
            assertThat(nubank.currency()).isEqualTo(CurrencyCode.BRL);
            assertThat(nubank.userId()).isEqualTo(USER_ID);
            assertThat(nubank.type()).isEqualTo(AccountType.CHECKING);
            assertThat(nubank.institution()).isEqualTo("Nubank");
            assertThat(nubank.openingBalance()).isEqualTo(Money.of("20000.00"));
            assertThat(nubank.openingDate()).isEqualTo(OPENING_DATE);
            assertThat(nubank.book()).isEqualTo(BookRef.account(NUBANK_ID));
            assertThat(nubank).isEqualTo(umaContaCorrente()).hasSameHashCodeAs(umaContaCorrente());
            assertThat(nubank.toString()).contains("Nubank", "ACTIVE");
        }
    }

    @Nested
    @DisplayName("arquivamento")
    class Arquivamento {

        private static final LocalDate HOJE = LocalDate.of(2026, 10, 10);

        private static Posting em(Account conta, String valor, LocalDate data) {
            return new Posting(conta.book(), Money.of(valor), data);
        }

        @Test
        @DisplayName("com saldo diferente de zero hoje lança AccountWithBalanceCannotBeArchivedException (S-4)")
        void naoDeveArquivarComSaldo() {
            Account nubank = umaContaCorrente();

            assertThatThrownBy(() -> nubank.archive(List.of(em(nubank, "0.01", HOJE)), HOJE))
                    .isInstanceOf(AccountWithBalanceCannotBeArchivedException.class);
            assertThatThrownBy(() -> nubank.archive(List.of(em(nubank, "-5.00", HOJE.minusDays(3))), HOJE))
                    .isInstanceOf(AccountWithBalanceCannotBeArchivedException.class);
            assertThatThrownBy(() -> nubank.archive(nubank.openingPostings(), HOJE))
                    .isInstanceOf(AccountWithBalanceCannotBeArchivedException.class);
            assertThat(nubank.status()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("saldo zero hoje, mas movimentação futura deixaria saldo: recusa (S-4 em todas as datas)")
        void naoDeveArquivarComMovimentacaoFutura() {
            Account nubank = umaContaCorrente();
            List<Posting> zeroHojeComFutura = List.of(em(nubank, "100.00", HOJE.minusDays(1)), em(nubank, "-100.00", HOJE),
                    em(nubank, "50.00", HOJE.plusDays(5)));
            assertThatThrownBy(() -> nubank.archive(zeroHojeComFutura, HOJE))
                    .isInstanceOf(AccountWithFutureBalanceCannotBeArchivedException.class)
                    .hasMessageContaining(HOJE.plusDays(5).toString());

            List<Posting> futuraQueVoltaAZeroDepois = List.of(em(nubank, "100.00", HOJE.plusDays(5)), em(nubank, "-100.00", HOJE.plusDays(9)));
            assertThatThrownBy(() -> nubank.archive(futuraQueVoltaAZeroDepois, HOJE))
                    .isInstanceOf(AccountWithFutureBalanceCannotBeArchivedException.class);
            assertThat(nubank.status()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("movimentações futuras que se anulam no mesmo dia e postings de outros livros não impedem o arquivamento")
        void deveArquivarQuandoTodasAsDatasFicamEmZero() {
            Account nubank = umaContaCorrente();
            List<Posting> postings = List.of(em(nubank, "30.00", HOJE.plusDays(2)), em(nubank, "-30.00", HOJE.plusDays(2)),
                    new Posting(BookRef.account(umaContaSemSaldo().id()), Money.of("999.00"), HOJE.plusDays(1)));
            nubank.archive(postings, HOJE);
            assertThat(nubank.status()).isEqualTo(AccountStatus.ARCHIVED);
        }

        @Test
        @DisplayName("com saldo zero vira ARCHIVED, e unarchive volta para ACTIVE")
        void deveArquivarEDesarquivar() {
            Account nubank = umaContaCorrente();

            nubank.archive(List.of(), HOJE);
            assertThat(nubank.status()).isEqualTo(AccountStatus.ARCHIVED);

            nubank.unarchive();
            assertThat(nubank.status()).isEqualTo(AccountStatus.ACTIVE);
        }

        static Stream<Arguments> matrizDeTransicoes() {
            Consumer<Account> archive = c -> c.archive(List.of(), HOJE);
            Consumer<Account> unarchive = Account::unarchive;
            return Stream.of(
                    Arguments.of(AccountStatus.ACTIVE, "archive", archive, AccountStatus.ARCHIVED, null),
                    Arguments.of(AccountStatus.ACTIVE, "unarchive", unarchive, null, AccountNotArchivedException.class),
                    Arguments.of(AccountStatus.ARCHIVED, "archive", archive, null, AccountAlreadyArchivedException.class),
                    Arguments.of(AccountStatus.ARCHIVED, "unarchive", unarchive, AccountStatus.ACTIVE, null));
        }

        @ParameterizedTest(name = "{0} --{1}--> {3}{4}")
        @MethodSource("matrizDeTransicoes")
        @DisplayName("matriz completa de transições (status × ação)")
        void deveRespeitarAMatriz(AccountStatus origem, String acao, Consumer<Account> transicao,
                                  AccountStatus destino, Class<? extends Throwable> erro) {
            Account conta = umaContaNoStatus(origem);

            if (erro != null) {
                assertThatThrownBy(() -> transicao.accept(conta)).isInstanceOf(erro).isInstanceOf(BusinessRuleException.class);
                assertThat(conta.status()).isEqualTo(origem);
            } else {
                transicao.accept(conta);
                assertThat(conta.status()).isEqualTo(destino);
            }
        }

        @Test
        @DisplayName("a matriz cobre todos os status")
        void aMatrizCobreTodosOsStatus() {
            assertThat(matrizDeTransicoes().map(a -> a.get()[0]).distinct()).containsExactlyInAnyOrder((Object[]) AccountStatus.values());
        }
    }

    @Nested
    @DisplayName("uso em movimentações")
    class Uso {

        @Test
        @DisplayName("conta ARCHIVED não recebe movimentação (AccountArchivedException)")
        void contaArquivadaNaoRecebeMovimentacao() {
            assertThatThrownBy(() -> umaContaArquivada().ensureCanReceiveTransactions())
                    .isInstanceOf(AccountArchivedException.class)
                    .extracting("code").isEqualTo("ACCOUNT_ARCHIVED");
        }

        @Test
        @DisplayName("conta ACTIVE recebe movimentação")
        void contaAtivaRecebeMovimentacao() {
            assertThatCode(() -> umaContaCorrente().ensureCanReceiveTransactions()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("conta de outro usuário é tratada como não encontrada")
        void contaDeOutroUsuarioNaoExiste() {
            Account nubank = umaContaCorrente();

            assertThatCode(() -> nubank.ensureBelongsTo(USER_ID)).doesNotThrowAnyException();
            assertThatThrownBy(() -> nubank.ensureBelongsTo(OTHER_USER_ID))
                    .isInstanceOf(AccountNotFoundException.class)
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("edição")
    class Edicao {

        @Test
        @DisplayName("rename e updateDetails reaplicam as invariantes do construtor")
        void deveReaplicarInvariantes() {
            Account nubank = umaContaCorrente();

            nubank.rename("  Nubank PF ");
            assertThat(nubank.name()).isEqualTo("Nubank PF");

            assertThatThrownBy(() -> nubank.rename(" ")).isInstanceOf(AccountNameRequiredException.class);
            assertThatThrownBy(() -> nubank.updateDetails(null, "Nu")).isInstanceOf(AccountNameRequiredException.class);
            assertThat(nubank.name()).isEqualTo("Nubank PF");
            assertThat(nubank.institution()).isEqualTo("Nubank");

            nubank.updateDetails("Nubank", "Nu Pagamentos");
            assertThat(nubank.institution()).isEqualTo("Nu Pagamentos");
        }

        @Test
        @DisplayName("não há setters públicos")
        void naoTemSetters() {
            assertThat(Arrays.stream(Account.class.getMethods()))
                    .filteredOn(m -> Modifier.isPublic(m.getModifiers()))
                    .extracting(Method::getName)
                    .noneMatch(nome -> nome.startsWith("set"));
            assertThat(Account.class.getConstructors()).isEmpty();
        }
    }
}
