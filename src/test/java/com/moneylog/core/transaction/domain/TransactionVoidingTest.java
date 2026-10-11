package com.moneylog.core.transaction.domain;

import static com.moneylog.core.debt.util.factory.DebtTestFactory.umEmprestimoAoJoao;
import static com.moneylog.core.debt.util.factory.DebtTestFactory.umHeader;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.NOW;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.umaDespesaPix;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.util.factory.InvoiceScenario;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidContextMissingException;

@DisplayName("Ponto único de estorno (M1)")
class TransactionVoidingTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 10, 20);

    @Test
    @DisplayName("Transaction.voidTransaction não é público: fora do pacote só TransactionVoiding estorna")
    void naoDeveExporOEstornoSemGuarda() throws Exception {
        assertThat(Modifier.isPublic(Transaction.class.getDeclaredMethod("voidTransaction", String.class, Instant.class,
                TransactionVoidPolicy.class).getModifiers())).isFalse();
        assertThat(Transaction.class.getMethods()).extracting(java.lang.reflect.Method::getName)
                .doesNotContain("voidTransaction", "prepareVoid", "applyVoid");
    }

    @Test
    @DisplayName("movimentação de conta: guarda das contas, sem contexto extra")
    void deveEstornarMovimentacaoDeConta() {
        Transaction despesa = umaDespesaPix();
        TransactionVoiding.voidTransaction(despesa, VoidContext.of("duplicada", NOW, HOJE)
                .withAccounts(java.util.List.of(AccountTestFactory.umaContaCorrente())).withActiveRefunds(Money.of("0.00")));
        assertThat(despesa.status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(despesa.postings()).isEmpty();
    }

    @Test
    @DisplayName("B3: estorno em conta falha fechado: sem as contas ou, para despesa, sem a soma dos reembolsos, é VoidContextMissingException e nada muda")
    void deveFalharFechadoSemContextoDeConta() {
        Transaction despesa = umaDespesaPix();
        var contas = java.util.List.of(AccountTestFactory.umaContaCorrente());
        VoidContext semContas = VoidContext.of("x", NOW, HOJE).withActiveRefunds(Money.of("0.00"));
        VoidContext semReembolsos = VoidContext.of("x", NOW, HOJE).withAccounts(contas);
        VoidContext contaDeOutra = VoidContext.of("x", NOW, HOJE).withAccounts(java.util.List.of(AccountTestFactory.umaContaSemSaldo()))
                .withActiveRefunds(Money.of("0.00"));

        for (VoidContext incompleto : new VoidContext[] {VoidContext.of("x", NOW, HOJE), semContas, semReembolsos, contaDeOutra}) {
            assertThatThrownBy(() -> TransactionVoiding.voidTransaction(despesa, incompleto)).isInstanceOf(VoidContextMissingException.class);
            assertThat(despesa.isActive()).isTrue();
        }
    }

    @Test
    @DisplayName("B3: transferência exige as duas contas; receita não exige a soma de reembolsos")
    void deveExigirAsDuasContasDaTransferencia() {
        var nubank = AccountTestFactory.umaContaCorrente();
        var xp = AccountTestFactory.umaContaSemSaldo();
        Transaction transferencia = com.moneylog.core.transaction.domain.AccountTransactions.transfer(
                com.moneylog.core.transaction.util.factory.TransactionTestFactory.umHeader(UUID.fromString("00000000-0000-0000-0000-0000000e0b03"),
                        java.time.LocalDate.of(2026, 10, 10)), Money.of("10.00"), nubank, xp);

        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(transferencia,
                VoidContext.of("x", NOW, HOJE).withAccounts(java.util.List.of(nubank)))).isInstanceOf(VoidContextMissingException.class);
        assertThat(transferencia.isActive()).isTrue();

        TransactionVoiding.voidTransaction(transferencia, VoidContext.of("x", NOW, HOJE).withAccounts(java.util.List.of(nubank, xp)));
        assertThat(transferencia.status()).isEqualTo(TransactionStatus.VOIDED);
    }

    @Test
    @DisplayName("movimentação de cartão sem cartão/cadeia ou de dívida sem dívida: erro de ligação e nada muda")
    void deveExigirOContextoDoTipo() {
        Transaction compra = InvoiceScenario.comCompraParcelada().get(InvoiceScenario.PURCHASE_6X_ID);
        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(compra, VoidContext.of("x", NOW, HOJE)))
                .isInstanceOf(VoidContextMissingException.class);
        assertThat(compra.isActive()).isTrue();

        Transaction emprestimo = umEmprestimoAoJoao().transaction();
        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(emprestimo, VoidContext.of("x", NOW, HOJE)))
                .isInstanceOf(VoidContextMissingException.class);
        assertThat(emprestimo.isActive()).isTrue();
    }

    @Test
    @DisplayName("estorno da origem pelo ponto único deixa a dívida VOIDED (efeito aplicado junto)")
    void deveAplicarOEfeitoNaDivida() {
        DebtOperation op = umEmprestimoAoJoao();
        TransactionVoiding.voidTransaction(op.transaction(), VoidContext.of("erro", NOW, HOJE).withDebt(op.debt()));
        assertThat(op.transaction().status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(op.debt().status()).isEqualTo(DebtStatus.VOIDED);
    }

    @Test
    @DisplayName("quitação sem registro ativo na dívida: recusada antes de mudar a movimentação (sem estado parcial)")
    void deveValidarADividaAntesDeMudarAMovimentacao() {
        Debt debt = umEmprestimoAoJoao().debt();
        Transaction quitacao = DebtTransactions.moneyReceived(umHeader(UUID.fromString("00000000-0000-0000-0000-0000000e0b09")),
                Money.of("100.00"), AccountTestFactory.umaContaCorrente(), debt).transaction();
        debt.voidSettlement(quitacao.id());

        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(quitacao, VoidContext.of("erro", NOW, HOJE).withDebt(debt)))
                .isInstanceOf(DebtNotFoundException.class);
        assertThat(quitacao.status()).isEqualTo(TransactionStatus.ACTIVE);
        assertThat(quitacao.postings()).hasSize(2);
        assertThat(debt.status()).isEqualTo(DebtStatus.OPEN);
    }
}
