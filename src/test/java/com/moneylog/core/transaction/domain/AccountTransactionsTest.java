package com.moneylog.core.transaction.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.XP_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.XP_INVEST_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaArquivada;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaDeInvestimento;
import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaSemSaldo;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.ALIMENTACAO_ID;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaArquivada;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeDespesa;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeReceita;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.DATE;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.EXPENSE_ID;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.NOW;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.REFUND_ID;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.TX_ID;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.umHeader;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.umaDespesaPix;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryArchivedException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryKindMismatchException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.importing.domain.DedupKey;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.money.MoneyExceptions.CurrencyMismatchException;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;
import com.moneylog.core.transaction.exception.TransactionExceptions.AdjustmentReasonRequiredException;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvalidRefundOriginException;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvalidTransactionOriginException;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvestmentAccountsMismatchException;
import com.moneylog.core.transaction.exception.TransactionExceptions.NonPositiveAmountException;
import com.moneylog.core.transaction.exception.TransactionExceptions.PaymentMethodRequiredException;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException;
import com.moneylog.core.transaction.exception.TransactionExceptions.SameAccountTransferException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionFieldRequiredException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionTextTooLongException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransferAcrossLiquidityException;
import com.moneylog.core.transaction.exception.TransactionExceptions.YieldRequiresInvestmentAccountException;

@DisplayName("AccountTransactions (T4)")
class AccountTransactionsTest {

    private static final Account NUBANK = umaContaCorrente();
    private static final Account XP = umaContaSemSaldo();
    private static final Account XP_INVEST = umaContaDeInvestimento();
    private static final Category ALIMENTACAO = umaCategoriaDeDespesa();
    private static final Category SALARIO = umaCategoriaDeReceita();

    @Nested
    @DisplayName("EXPENSE e INCOME")
    class DespesaEReceita {

        @Test
        @DisplayName("CA1: despesa 100,00 PIX gera EXPENSE(Alimentação, 2026-10) +100 / ACCOUNT(Nubank) −100, origem TRANSACTION ativa")
        void deveGerarPostingsDaDespesa() {
            Transaction tx = umaDespesaPix();

            assertThat(tx.postings()).containsExactly(
                    new Posting(BookRef.expense(ALIMENTACAO_ID), Money.of("100.00"), DATE),
                    new Posting(BookRef.account(NUBANK_ID), Money.of("-100.00"), DATE));
            assertThat(tx.postings().getFirst().competence().toString()).isEqualTo("2026-10");
            assertThat(tx.entries()).allSatisfy(e -> {
                assertThat(e.source()).isEqualTo(PostingSource.transaction(EXPENSE_ID));
                assertThat(e.active()).isTrue();
            });
            assertThat(tx.type()).isEqualTo(TransactionType.EXPENSE);
            assertThat(tx.paymentMethod()).isEqualTo(PaymentMethod.PIX);
            assertThat(tx.status()).isEqualTo(TransactionStatus.ACTIVE);
        }

        @Test
        @DisplayName("CA2: CREDIT não existe em PaymentMethod; despesa e receita sem método são rejeitadas")
        void deveExigirMetodoSemCredito() {
            assertThat(Arrays.stream(PaymentMethod.values()).map(Enum::name)).doesNotContain("CREDIT");
            assertThatThrownBy(() -> AccountTransactions.expense(umHeader(), Money.of("10.00"), NUBANK, ALIMENTACAO, null))
                    .isInstanceOf(PaymentMethodRequiredException.class);
            assertThatThrownBy(() -> AccountTransactions.income(umHeader(), Money.of("10.00"), NUBANK, SALARIO, null))
                    .isInstanceOf(PaymentMethodRequiredException.class);
        }

        @Test
        @DisplayName("receita gera ACCOUNT +X / INCOME(cat) −X")
        void deveGerarPostingsDaReceita() {
            Transaction tx = AccountTransactions.income(umHeader(), Money.of("5000.00"), NUBANK, SALARIO, PaymentMethod.TRANSFER);

            assertThat(tx.postings()).containsExactly(
                    new Posting(BookRef.account(NUBANK_ID), Money.of("5000.00"), DATE),
                    new Posting(SALARIO.book(), Money.of("-5000.00"), DATE));
        }

        @Test
        @DisplayName("CA5: categoria do tipo errado lança CategoryKindMismatchException")
        void deveRejeitarCategoriaDoTipoErrado() {
            assertThatThrownBy(() -> AccountTransactions.income(umHeader(), Money.of("10.00"), NUBANK, ALIMENTACAO, PaymentMethod.PIX))
                    .isInstanceOf(CategoryKindMismatchException.class);
            assertThatThrownBy(() -> AccountTransactions.expense(umHeader(), Money.of("10.00"), NUBANK, SALARIO, PaymentMethod.PIX))
                    .isInstanceOf(CategoryKindMismatchException.class);
        }

        @Test
        @DisplayName("despesa e receita exigem conta líquida (CASH)")
        void deveExigirContaLiquida() {
            assertThatThrownBy(() -> AccountTransactions.expense(umHeader(), Money.of("10.00"), XP_INVEST, ALIMENTACAO, PaymentMethod.PIX))
                    .isInstanceOf(AccountNotCashException.class);
        }

        @Test
        @DisplayName("conta e categoria arquivadas não recebem movimentação nova")
        void deveRejeitarArquivadas() {
            assertThatThrownBy(() -> AccountTransactions.expense(umHeader(), Money.of("10.00"), umaContaArquivada(), ALIMENTACAO, PaymentMethod.PIX))
                    .isInstanceOf(AccountArchivedException.class);
            assertThatThrownBy(() -> AccountTransactions.expense(umHeader(), Money.of("10.00"), NUBANK, umaCategoriaArquivada(), PaymentMethod.PIX))
                    .isInstanceOf(CategoryArchivedException.class);
        }
    }

    @Nested
    @DisplayName("TRANSFER e INVESTMENT")
    class TransferenciaEInvestimento {

        @Test
        @DisplayName("CA3: transferência 1.000 Nubank → XP gera ACCOUNT(XP) +1.000 / ACCOUNT(Nubank) −1.000, sem EXPENSE/INCOME")
        void deveGerarPostingsDaTransferencia() {
            Transaction tx = AccountTransactions.transfer(umHeader(), Money.of("1000.00"), NUBANK, XP);

            assertThat(tx.postings()).containsExactly(
                    new Posting(BookRef.account(XP_ID), Money.of("1000.00"), DATE),
                    new Posting(BookRef.account(NUBANK_ID), Money.of("-1000.00"), DATE));
            assertThat(tx.postings()).noneMatch(p -> p.bookType().isResult());
        }

        @Test
        @DisplayName("CA3: origem = destino lança SameAccountTransferException")
        void deveRejeitarMesmaConta() {
            assertThatThrownBy(() -> AccountTransactions.transfer(umHeader(), Money.of("1.00"), NUBANK, NUBANK))
                    .isInstanceOf(SameAccountTransferException.class);
        }

        @Test
        @DisplayName("CA3: CASH → INVESTMENT lança TransferAcrossLiquidityException (o caso é INVESTMENT)")
        void deveRejeitarLiquidezDiferente() {
            assertThatThrownBy(() -> AccountTransactions.transfer(umHeader(), Money.of("1.00"), NUBANK, XP_INVEST))
                    .isInstanceOf(TransferAcrossLiquidityException.class);
        }

        @Test
        @DisplayName("CA4: aplicação de 10.000 Nubank → XP Invest e resgate com sinais invertidos")
        void deveGerarPostingsDoInvestimento() {
            Transaction aplicacao = AccountTransactions.investment(umHeader(), Money.of("10000.00"), InvestmentDirection.CONTRIBUTION, NUBANK, XP_INVEST);
            Transaction resgate = AccountTransactions.investment(umHeader(), Money.of("10000.00"), InvestmentDirection.REDEMPTION, NUBANK, XP_INVEST);

            assertThat(aplicacao.postings()).containsExactly(
                    new Posting(BookRef.account(XP_INVEST_ID), Money.of("10000.00"), DATE),
                    new Posting(BookRef.account(NUBANK_ID), Money.of("-10000.00"), DATE));
            assertThat(resgate.postings()).containsExactly(
                    new Posting(BookRef.account(XP_INVEST_ID), Money.of("-10000.00"), DATE),
                    new Posting(BookRef.account(NUBANK_ID), Money.of("10000.00"), DATE));
            assertThat(aplicacao.postings()).noneMatch(p -> p.bookType().isResult());
        }

        @Test
        @DisplayName("CA4: investimento exige uma conta CASH e uma INVESTMENT")
        void deveExigirContasCorretasNoInvestimento() {
            assertThatThrownBy(() -> AccountTransactions.investment(umHeader(), Money.of("1.00"), InvestmentDirection.CONTRIBUTION, NUBANK, XP))
                    .isInstanceOf(InvestmentAccountsMismatchException.class);
            assertThatThrownBy(() -> AccountTransactions.investment(umHeader(), Money.of("1.00"), InvestmentDirection.CONTRIBUTION, XP_INVEST, XP_INVEST))
                    .isInstanceOf(InvestmentAccountsMismatchException.class);
        }
    }

    @Nested
    @DisplayName("REFUND em conta")
    class Reembolso {

        @Test
        @DisplayName("CA6: reembolso 50 com refundOf = despesa de 100 gera ACCOUNT +50 / EXPENSE −50 na competência do reembolso")
        void deveGerarPostingsDoReembolso() {
            LocalDate novembro = LocalDate.of(2026, 11, 3);
            Transaction tx = AccountTransactions.refundToAccount(umHeader(REFUND_ID, novembro), Money.of("50.00"), NUBANK, ALIMENTACAO,
                    RefundOrigin.firstRefundOf(umaDespesaPix()));

            assertThat(tx.postings()).containsExactly(
                    new Posting(BookRef.account(NUBANK_ID), Money.of("50.00"), novembro),
                    new Posting(BookRef.expense(ALIMENTACAO_ID), Money.of("-50.00"), novembro));
            assertThat(tx.refundOfId()).isEqualTo(EXPENSE_ID);
        }

        @Test
        @DisplayName("CA6: reembolso de 60 depois de 50 já reembolsados de 100 lança RefundExceedsOriginalException (S-9)")
        void deveRejeitarReembolsoAcimaDoOriginal() {
            RefundOrigin origem = new RefundOrigin(umaDespesaPix(), Money.of("50.00"));

            assertThatThrownBy(() -> AccountTransactions.refundToAccount(umHeader(), Money.of("60.00"), NUBANK, ALIMENTACAO, origem))
                    .isInstanceOf(RefundExceedsOriginalException.class);
            assertThat(AccountTransactions.refundToAccount(umHeader(), Money.of("50.00"), NUBANK, ALIMENTACAO, origem).amount())
                    .isEqualTo(Money.of("50.00"));
        }

        @Test
        @DisplayName("reembolso sem refundOf é aceito (S-9)")
        void deveAceitarReembolsoSemOriginal() {
            assertThat(AccountTransactions.refundToAccount(umHeader(), Money.of("500.00"), NUBANK, ALIMENTACAO, null).refundOfId()).isNull();
        }

        @Test
        @DisplayName("original estornada, de outro tipo ou de outra categoria é rejeitada")
        void deveValidarOriginal() {
            Transaction estornada = umaDespesaPix();
            estornada.voidTransaction("erro", NOW, TransactionVoidPolicy.accountTransactions());
            assertThatThrownBy(() -> AccountTransactions.refundToAccount(umHeader(), Money.of("1.00"), NUBANK, ALIMENTACAO,
                    RefundOrigin.firstRefundOf(estornada))).isInstanceOf(InvalidRefundOriginException.class);

            Transaction transferencia = AccountTransactions.transfer(umHeader(), Money.of("10.00"), NUBANK, XP);
            assertThatThrownBy(() -> AccountTransactions.refundToAccount(umHeader(), Money.of("1.00"), NUBANK, ALIMENTACAO,
                    RefundOrigin.firstRefundOf(transferencia))).isInstanceOf(InvalidRefundOriginException.class);

            Category outra = Category.create(UUID.fromString("00000000-0000-0000-0000-00000000c009"), USER_ID, "Lazer", CategoryKind.EXPENSE);
            assertThatThrownBy(() -> AccountTransactions.refundToAccount(umHeader(), Money.of("1.00"), NUBANK, outra,
                    RefundOrigin.firstRefundOf(umaDespesaPix()))).isInstanceOf(InvalidRefundOriginException.class);
        }

        @Test
        @DisplayName("original de outro usuário é tratada como inexistente")
        void deveRejeitarOriginalDeOutroUsuario() {
            Transaction alheia = AccountTransactions.expense(
                    TransactionHeader.of(EXPENSE_ID, OTHER_USER_ID, DATE, null), Money.of("100.00"),
                    Account.open(NUBANK_ID, OTHER_USER_ID, "Nubank", AccountType.CHECKING, null, Money.of("0.00"), DATE),
                    Category.create(ALIMENTACAO_ID, OTHER_USER_ID, "Alimentação", CategoryKind.EXPENSE), PaymentMethod.PIX);

            assertThatThrownBy(() -> AccountTransactions.refundToAccount(umHeader(), Money.of("1.00"), NUBANK, ALIMENTACAO,
                    RefundOrigin.firstRefundOf(alheia))).isInstanceOf(TransactionNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("ADJUSTMENT")
    class Ajuste {

        @Test
        @DisplayName("CA7: DECREASE de 30 sem motivo é rejeitado; com motivo gera ACCOUNT −30 / EQUITY +30 e kind CORRECTION")
        void deveExigirMotivoEGerarPostings() {
            assertThatThrownBy(() -> AccountTransactions.adjustment(umHeader(), Money.of("30.00"), NUBANK, AdjustmentDirection.DECREASE, "  "))
                    .isInstanceOf(AdjustmentReasonRequiredException.class);

            Transaction tx = AccountTransactions.adjustment(umHeader(), Money.of("30.00"), NUBANK, AdjustmentDirection.DECREASE, "tarifa não lançada");

            assertThat(tx.postings()).containsExactly(
                    new Posting(BookRef.account(NUBANK_ID), Money.of("-30.00"), DATE),
                    new Posting(BookRef.equity(), Money.of("30.00"), DATE));
            assertThat(tx.adjustmentKind()).isEqualTo(AdjustmentKind.CORRECTION);
            assertThat(tx.reason()).isEqualTo("tarifa não lançada");
        }

        @Test
        @DisplayName("CA7: YIELD (S-26) só em conta INVESTMENT e não vira receita")
        void deveExigirContaDeInvestimentoNoRendimento() {
            assertThatThrownBy(() -> AccountTransactions.adjustment(umHeader(), Money.of("12.34"), NUBANK, AdjustmentDirection.INCREASE,
                    AdjustmentKind.YIELD, "rendimento")).isInstanceOf(YieldRequiresInvestmentAccountException.class);

            Transaction tx = AccountTransactions.adjustment(umHeader(), Money.of("12.34"), XP_INVEST, AdjustmentDirection.INCREASE,
                    AdjustmentKind.YIELD, "rendimento de outubro");
            assertThat(tx.adjustmentKind()).isEqualTo(AdjustmentKind.YIELD);
            assertThat(tx.postings()).noneMatch(p -> p.bookType() == BookType.INCOME);
        }
    }

    @Nested
    @DisplayName("validações comuns")
    class Comuns {

        @ParameterizedTest(name = "valor {0}")
        @ValueSource(strings = {"0.00", "-1.00"})
        @DisplayName("CA8: valor zero ou negativo lança NonPositiveAmountException em todas as fábricas")
        void deveRejeitarValorNaoPositivo(String valor) {
            Money amount = Money.of(valor);
            assertThatThrownBy(() -> AccountTransactions.expense(umHeader(), amount, NUBANK, ALIMENTACAO, PaymentMethod.PIX)).isInstanceOf(NonPositiveAmountException.class);
            assertThatThrownBy(() -> AccountTransactions.income(umHeader(), amount, NUBANK, SALARIO, PaymentMethod.PIX)).isInstanceOf(NonPositiveAmountException.class);
            assertThatThrownBy(() -> AccountTransactions.transfer(umHeader(), amount, NUBANK, XP)).isInstanceOf(NonPositiveAmountException.class);
            assertThatThrownBy(() -> AccountTransactions.investment(umHeader(), amount, InvestmentDirection.CONTRIBUTION, NUBANK, XP_INVEST)).isInstanceOf(NonPositiveAmountException.class);
            assertThatThrownBy(() -> AccountTransactions.adjustment(umHeader(), amount, NUBANK, AdjustmentDirection.INCREASE, "x")).isInstanceOf(NonPositiveAmountException.class);
            assertThatThrownBy(() -> AccountTransactions.refundToAccount(umHeader(), amount, NUBANK, ALIMENTACAO, null)).isInstanceOf(NonPositiveAmountException.class);
        }

        @Test
        @DisplayName("CA8: conta ou categoria de outro usuário lança o NotFound da feature")
        void deveRejeitarReferenciaDeOutroUsuario() {
            Account alheia = Account.open(UUID.fromString("00000000-0000-0000-0000-00000000a099"), OTHER_USER_ID, "Alheia",
                    AccountType.CHECKING, null, Money.of("0.00"), DATE);
            Category alheiaCat = Category.create(UUID.fromString("00000000-0000-0000-0000-00000000c099"), OTHER_USER_ID, "X", CategoryKind.EXPENSE);

            assertThatThrownBy(() -> AccountTransactions.expense(umHeader(), Money.of("1.00"), alheia, ALIMENTACAO, PaymentMethod.PIX))
                    .isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> AccountTransactions.expense(umHeader(), Money.of("1.00"), NUBANK, alheiaCat, PaymentMethod.PIX))
                    .isInstanceOf(CategoryNotFoundException.class);
            assertThatThrownBy(() -> AccountTransactions.transfer(umHeader(), Money.of("1.00"), NUBANK, alheia))
                    .isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        @DisplayName("moeda diferente da conta lança CurrencyMismatchException")
        void deveRejeitarMoedaDiferente() {
            assertThatThrownBy(() -> AccountTransactions.expense(umHeader(), Money.of("1.00", CurrencyCode.of("USD")), NUBANK, ALIMENTACAO, PaymentMethod.PIX))
                    .isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        @DisplayName("header: campos obrigatórios, textos aparados e limites de tamanho")
        void deveValidarHeader() {
            assertThatThrownBy(() -> TransactionHeader.of(null, USER_ID, DATE, "x")).isInstanceOf(TransactionFieldRequiredException.class);
            assertThatThrownBy(() -> TransactionHeader.of(TX_ID, null, DATE, "x")).isInstanceOf(TransactionFieldRequiredException.class);
            assertThatThrownBy(() -> TransactionHeader.of(TX_ID, USER_ID, null, "x")).isInstanceOf(TransactionFieldRequiredException.class);
            assertThatThrownBy(() -> TransactionHeader.of(TX_ID, USER_ID, DATE, "x".repeat(201))).isInstanceOf(TransactionTextTooLongException.class);
            assertThatThrownBy(() -> AccountTransactions.expense(null, Money.of("1.00"), NUBANK, ALIMENTACAO, PaymentMethod.PIX))
                    .isInstanceOf(TransactionFieldRequiredException.class);
            TransactionHeader header = TransactionHeader.of(TX_ID, USER_ID, DATE, "  Café  ").withNotes("  ");
            assertThat(header.description()).isEqualTo("Café");
            assertThat(header.notes()).isNull();
            assertThat(header.withNotes(" pago ").notes()).isEqualTo("pago");
        }

        @Test
        @DisplayName("origem: importação exige lote e chave original; a chave efetiva pode ser nula (S-43); recorrência exige ocorrência")
        void deveValidarOrigem() {
            DedupKey chave = new DedupKey("a".repeat(64));
            UUID lote = UUID.fromString("00000000-0000-0000-0000-0000000b0001");
            TransactionOrigin forcada = TransactionOrigin.imported(lote, null, chave);
            assertThat(forcada.source()).isEqualTo(TransactionSource.IMPORT);
            assertThat(forcada.dedupKey()).isNull();
            assertThat(TransactionOrigin.recurrence(lote, DATE).source()).isEqualTo(TransactionSource.RECURRENCE);

            assertThatThrownBy(() -> TransactionOrigin.imported(null, chave, chave)).isInstanceOf(InvalidTransactionOriginException.class);
            assertThatThrownBy(() -> TransactionOrigin.imported(lote, chave, null)).isInstanceOf(InvalidTransactionOriginException.class);
            assertThatThrownBy(() -> TransactionOrigin.recurrence(lote, null)).isInstanceOf(InvalidTransactionOriginException.class);
            assertThatThrownBy(() -> new TransactionOrigin(TransactionSource.MANUAL, null, chave, null, null, null))
                    .isInstanceOf(InvalidTransactionOriginException.class);
            assertThatThrownBy(() -> new TransactionOrigin(null, null, null, null, null, null))
                    .isInstanceOf(InvalidTransactionOriginException.class);

            Transaction importada = AccountTransactions.expense(umHeader().withOrigin(TransactionOrigin.imported(lote, chave, chave)),
                    Money.of("8.50"), NUBANK, ALIMENTACAO, PaymentMethod.DEBIT);
            assertThat(importada.origin().originalDedupKey()).isEqualTo(chave);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = TransactionType.class, names = {"EXPENSE", "INCOME", "TRANSFER", "INVESTMENT", "ADJUSTMENT", "REFUND"})
    @DisplayName("CA10: Σ postings ativos = 0 antes e depois do estorno, para cada tipo do grupo")
    void deveFecharEmZeroAntesEDepoisDoEstorno(TransactionType type) {
        Transaction tx = TransactionSamples.valida(type);
        assertThat(tx.type()).isEqualTo(type);

        assertThat(soma(tx)).isEqualTo(Money.of("0.00"));
        tx.voidTransaction("teste", NOW, TransactionVoidPolicy.accountTransactions());
        assertThat(tx.postings()).isEmpty();
        assertThat(tx.entries()).isNotEmpty().noneMatch(LedgerEntry::active);
        assertThat(soma(tx)).isEqualTo(Money.of("0.00"));
    }

    private static Money soma(Transaction tx) {
        return Money.sum(tx.postings().stream().map(Posting::amount).toList(), CurrencyCode.BRL);
    }

    /** Uma movimentação válida de cada tipo do grupo de contas. */
    static final class TransactionSamples {
        static Transaction valida(TransactionType type) {
            return switch (type) {
                case EXPENSE -> umaDespesaPix();
                case INCOME -> AccountTransactions.income(umHeader(), Money.of("10.00"), NUBANK, SALARIO, PaymentMethod.PIX);
                case TRANSFER -> AccountTransactions.transfer(umHeader(), Money.of("10.00"), NUBANK, XP);
                case INVESTMENT -> AccountTransactions.investment(umHeader(), Money.of("10.00"), InvestmentDirection.CONTRIBUTION, NUBANK, XP_INVEST);
                case ADJUSTMENT -> AccountTransactions.adjustment(umHeader(), Money.of("10.00"), NUBANK, AdjustmentDirection.INCREASE, "x");
                case REFUND -> AccountTransactions.refundToAccount(umHeader(), Money.of("10.00"), NUBANK, ALIMENTACAO, null);
                default -> throw new IllegalArgumentException(type.name());
            };
        }
    }
}
