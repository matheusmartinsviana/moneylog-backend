package com.moneylog.core.transaction.domain;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.finance.util.ListScenario;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundCategoryLockedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;

/** T27: regras da edição (S-68, S-81) e do estorno com contas arquivadas e reembolsos (S-73, S-88, S-89). */
@DisplayName("Edição e estorno de movimentação em conta (T27)")
class TransactionEditingTest {

    private static final Money ZERO = Money.of("0.00");
    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private final ListScenario s = new ListScenario();

    private static TransactionHeader headerOf(Transaction t) {
        return new TransactionHeader(t.id(), t.userId(), t.date(), t.description(), t.notes(), t.origin());
    }

    private Transaction expenseLike(Transaction current, String amount, com.moneylog.core.category.domain.Category category) {
        return AccountTransactions.expense(headerOf(current), Money.of(amount), s.nubank, category, PaymentMethod.PIX);
    }

    private static Transaction withVersion(Transaction t, long version) {
        TransactionState st = t.state();
        return Transaction.restore(new TransactionState(st.id(), st.userId(), st.type(), st.amount(), st.date(), st.description(), st.notes(),
                st.origin(), st.paymentMethod(), st.accountId(), st.counterpartAccountId(), st.cardId(), st.categoryId(), st.debtId(),
                st.refundOfId(), st.investmentDirection(), st.adjustmentDirection(), st.adjustmentKind(), st.reason(),
                st.invoiceClosingDate(), st.lateEntry(), st.installments(), st.status(), st.voidReason(), st.voidedAt(), version), t.entries());
    }

    @Test
    @DisplayName("a substituta herda a versão da atual (a persistência incrementa) e mantém id, dono, tipo e origem")
    void deveHerdarAVersao() {
        Transaction current = withVersion(s.pix, 3);
        Transaction replacement = expenseLike(current, "120.00", s.transporte);

        Transaction edited = TransactionEditing.replace(current, replacement, ZERO);

        assertThat(edited).isSameAs(replacement);
        assertThat(edited.version()).isEqualTo(3);
        assertThat(edited.id()).isEqualTo(current.id());
        assertThat(edited.amount()).isEqualTo(Money.of("120.00"));
        assertThat(edited.categoryId()).isEqualTo(s.transporte.id());
        assertThat(edited.postings()).hasSize(2);
        assertThat(edited.origin()).isEqualTo(current.origin());
    }

    @Test
    @DisplayName("movimentação estornada não é editável: TRANSACTION_NOT_EDITABLE")
    void naoDeveEditarEstornada() {
        Transaction replacement = expenseLike(s.estornada, "10.00", s.alimentacao);

        assertThatThrownBy(() -> TransactionEditing.replace(s.estornada, replacement, ZERO)).isInstanceOf(TransactionNotEditableException.class)
                .hasFieldOrPropertyWithValue("code", "TRANSACTION_NOT_EDITABLE");
    }

    @Test
    @DisplayName("tipo, id ou dono diferentes: TRANSACTION_NOT_EDITABLE")
    void naoDeveMudarTipoIdNemDono() {
        Transaction otherType = AccountTransactions.income(headerOf(s.pix), Money.of("100.00"), s.nubank, s.salario, PaymentMethod.PIX);
        Transaction otherId = AccountTransactions.expense(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000b9999"), USER_ID,
                TODAY, "x"), Money.of("100.00"), s.nubank, s.alimentacao, PaymentMethod.PIX);

        assertThatThrownBy(() -> TransactionEditing.replace(s.pix, otherType, ZERO)).isInstanceOf(TransactionNotEditableException.class);
        assertThatThrownBy(() -> TransactionEditing.replace(s.pix, otherId, ZERO)).isInstanceOf(TransactionNotEditableException.class);
    }

    @Test
    @DisplayName("S-81 (a): despesa com reembolso ativo não troca de categoria; valor abaixo do reembolsado é recusado; igual ou maior passa")
    void deveAplicarOInvarianteDaDespesaComReembolso() {
        Money refunded = Money.of("50.00");

        assertThatThrownBy(() -> TransactionEditing.replace(s.pix, expenseLike(s.pix, "100.00", s.transporte), refunded))
                .isInstanceOf(RefundCategoryLockedException.class).hasFieldOrPropertyWithValue("code", "REFUND_CATEGORY_LOCKED");
        assertThatThrownBy(() -> TransactionEditing.replace(s.pix, expenseLike(s.pix, "40.00", s.alimentacao), refunded))
                .isInstanceOf(RefundExceedsOriginalException.class).hasFieldOrPropertyWithValue("code", "REFUND_EXCEEDS_ORIGINAL");
        assertThat(TransactionEditing.replace(s.pix, expenseLike(s.pix, "50.00", s.alimentacao), refunded).amount()).isEqualTo(Money.of("50.00"));
        assertThat(TransactionEditing.replace(s.pix, expenseLike(s.pix, "80.00", s.alimentacao), refunded).amount()).isEqualTo(Money.of("80.00"));
    }

    @Test
    @DisplayName("sem reembolso ativo (zero ou null) a despesa troca de categoria e de valor livremente; a regra não vale para outros tipos")
    void naoDeveTravarSemReembolso() {
        assertThat(TransactionEditing.replace(s.pix, expenseLike(s.pix, "10.00", s.transporte), ZERO).categoryId()).isEqualTo(s.transporte.id());
        assertThat(TransactionEditing.replace(s.debito, expenseLike(s.debito, "10.00", s.alimentacao), null).categoryId()).isEqualTo(s.alimentacao.id());
        Transaction newTransfer = AccountTransactions.transfer(headerOf(s.transferencia), Money.of("1.00"), s.nubank, s.xp);
        assertThat(TransactionEditing.replace(s.transferencia, newTransfer, Money.of("999.00")).amount()).isEqualTo(Money.of("1.00"));
    }

    @Test
    @DisplayName("S-81 (b): reembolso ligado é revalidado excluindo a si mesmo; categoria diferente da original é REFUND_INVALID_ORIGINAL")
    void deveRevalidarOReembolsoSemContarASiMesmo() {
        // despesa de 100,00 com dois reembolsos ativos: R1 de 30,00 e R2 de 20,00; ao editar R1 o teto é 100 − 20 = 80
        Money otherRefunds = Money.of("20.00");
        RefundOrigin origin = new RefundOrigin(s.pix, otherRefunds);

        Transaction r1At80 = AccountTransactions.refundToAccount(headerOf(s.reembolso), Money.of("80.00"), s.nubank, s.alimentacao, origin);
        assertThat(TransactionEditing.replace(s.reembolso, r1At80, ZERO).amount()).isEqualTo(Money.of("80.00"));
        assertThatThrownBy(() -> AccountTransactions.refundToAccount(headerOf(s.reembolso), Money.of("80.01"), s.nubank, s.alimentacao, origin))
                .isInstanceOf(RefundExceedsOriginalException.class);
        assertThatThrownBy(() -> AccountTransactions.refundToAccount(headerOf(s.reembolso), Money.of("10.00"), s.nubank, s.transporte, origin))
                .hasFieldOrPropertyWithValue("code", "REFUND_INVALID_ORIGINAL");
    }

    // ---------------------------------------------------------------- estorno

    private static Account archived(Account account) {
        return Account.restore(account.id(), account.userId(), account.name(), AccountType.CHECKING, null, account.openingBalance(),
                account.openingDate(), AccountStatus.ARCHIVED, 1);
    }

    @Test
    @DisplayName("S-73/S-88: conta arquivada recusa o estorno com ACCOUNT_ARCHIVED e nada muda")
    void deveRecusarEstornoComContaArquivada() {
        VoidContext context = VoidContext.of("engano", NOW, TODAY).withAccounts(java.util.List.of(s.nubank, archived(s.xp)));

        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(s.transferencia, context)).hasFieldOrPropertyWithValue("code", "ACCOUNT_ARCHIVED");
        assertThat(s.transferencia.isActive()).isTrue();
        assertThat(s.transferencia.postings()).isNotEmpty();
    }

    @Test
    @DisplayName("S-89: despesa com reembolso ativo recusa o estorno com TRANSACTION_HAS_ACTIVE_REFUNDS; sem reembolso estorna")
    void deveRecusarEstornoDaDespesaComReembolso() {
        VoidContext blocked = VoidContext.of("engano", NOW, TODAY).withAccounts(java.util.List.of(s.nubank)).withActiveRefunds(Money.of("50.00"));

        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(s.pix, blocked)).hasFieldOrPropertyWithValue("code", "TRANSACTION_HAS_ACTIVE_REFUNDS");
        assertThat(s.pix.isActive()).isTrue();

        TransactionVoiding.voidTransaction(s.pix, VoidContext.of("engano", NOW, TODAY).withAccounts(java.util.List.of(s.nubank)).withActiveRefunds(ZERO));
        assertThat(s.pix.status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(s.pix.postings()).isEmpty();
    }

    @Test
    @DisplayName("a ordem das recusas: já estornada primeiro, depois motivo, conta arquivada e reembolso")
    void deveManterAOrdemDasRecusas() {
        VoidContext both = VoidContext.of("engano", NOW, TODAY).withAccounts(java.util.List.of(archived(s.nubank))).withActiveRefunds(Money.of("1.00"));

        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(s.estornada, both)).hasFieldOrPropertyWithValue("code", "TRANSACTION_ALREADY_VOIDED");
        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(s.debito, VoidContext.of(" ", NOW, TODAY).withAccounts(java.util.List.of(archived(s.nubank))).withActiveRefunds(ZERO)))
                .hasFieldOrPropertyWithValue("code", "TRANSACTION_VOID_REASON_REQUIRED");
        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(s.debito, both)).hasFieldOrPropertyWithValue("code", "ACCOUNT_ARCHIVED");
    }

    @Test
    @DisplayName("os withers do contexto preservam os campos; o construtor de seis campos continua valendo")
    void devePreservarOContexto() {
        VoidContext base = new VoidContext("x", NOW, TODAY, null, null, null);
        VoidContext full = base.withAccounts(java.util.List.of(s.nubank)).withActiveRefunds(Money.of("5.00"));

        assertThat(base.accounts()).isEmpty();
        assertThat(base.activeRefunds()).isNull();
        assertThat(full.accounts()).containsExactly(s.nubank);
        assertThat(full.withDebt(null).activeRefunds()).isEqualTo(Money.of("5.00"));
        assertThat(full.withCard(null, null).accounts()).containsExactly(s.nubank);
        assertThat(full.withActiveRefunds(null).accounts()).containsExactly(s.nubank);
        assertThat(new VoidContext("x", NOW, TODAY, null, null, null, null, null).accounts()).isEmpty();
    }
}
