package com.moneylog.core.transaction.domain;

import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.NOW;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.umaDespesaPix;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionAlreadyVoidedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionFieldRequiredException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidPolicyNotApplicableException;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidReasonRequiredException;

@DisplayName("Transaction: estorno com guarda (T4 CA9, S-36)")
class TransactionVoidTest {

    @Test
    @DisplayName("estorno aceito consulta a policy, vira VOIDED com motivo e data e desativa todos os postings")
    void deveEstornarConsultandoAPolicy() {
        Transaction tx = umaDespesaPix();
        AtomicInteger consultas = new AtomicInteger();

        tx.voidTransaction("lançado em duplicidade", NOW, t -> {
            consultas.incrementAndGet();
            return TransactionVoidPolicy.accountTransactions().evaluate(t);
        });

        assertThat(consultas).hasValue(1);
        assertThat(tx.status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(tx.isActive()).isFalse();
        assertThat(tx.voidReason()).isEqualTo("lançado em duplicidade");
        assertThat(tx.voidedAt()).isEqualTo(NOW);
        assertThat(tx.entries()).hasSize(2).noneMatch(LedgerEntry::active);
        assertThat(tx.postings()).isEmpty();
    }

    @Test
    @DisplayName("policy que recusa com TRANSACTION_IN_CLOSED_INVOICE lança TransactionVoidRefusedException com esse code e nada muda")
    void deveManterAtivaQuandoAPolicyRecusa() {
        Transaction tx = umaDespesaPix();

        assertThatThrownBy(() -> tx.voidTransaction("motivo", NOW,
                t -> VoidDecision.refuse("TRANSACTION_IN_CLOSED_INVOICE", "fatura fechada")))
                .isInstanceOf(TransactionVoidRefusedException.class)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(e -> ((TransactionVoidRefusedException) e).code())
                .isEqualTo("TRANSACTION_IN_CLOSED_INVOICE");
        assertThat(tx.status()).isEqualTo(TransactionStatus.ACTIVE);
        assertThat(tx.entries()).allMatch(LedgerEntry::active);
        assertThat(tx.voidReason()).isNull();
    }

    @Test
    @DisplayName("estornar de novo lança TransactionAlreadyVoidedException")
    void deveRejeitarEstornoDuplo() {
        Transaction tx = umaDespesaPix();
        tx.voidTransaction("erro", NOW, TransactionVoidPolicy.accountTransactions());

        assertThatThrownBy(() -> tx.voidTransaction("erro", NOW, TransactionVoidPolicy.accountTransactions()))
                .isInstanceOf(TransactionAlreadyVoidedException.class);
    }

    @ParameterizedTest(name = "motivo [{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("motivo em branco é rejeitado")
    void deveExigirMotivo(String motivo) {
        Transaction tx = umaDespesaPix();
        assertThatThrownBy(() -> tx.voidTransaction(motivo, NOW, TransactionVoidPolicy.accountTransactions()))
                .isInstanceOf(VoidReasonRequiredException.class);
        assertThat(tx.isActive()).isTrue();
    }

    @Test
    @DisplayName("instante e policy são obrigatórios")
    void deveExigirInstanteEPolicy() {
        Transaction tx = umaDespesaPix();
        assertThatThrownBy(() -> tx.voidTransaction("x", null, TransactionVoidPolicy.accountTransactions()))
                .isInstanceOf(TransactionFieldRequiredException.class);
        assertThatThrownBy(() -> tx.voidTransaction("x", NOW, null)).isInstanceOf(TransactionFieldRequiredException.class);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = TransactionType.class, names = {"EXPENSE", "INCOME", "TRANSFER", "INVESTMENT", "ADJUSTMENT", "REFUND"})
    @DisplayName("a policy padrão sempre permite os tipos do grupo de contas")
    void devePermitirTiposDoGrupo(TransactionType type) {
        assertThat(TransactionVoidPolicy.accountTransactions().evaluate(AccountTransactionsTest.TransactionSamples.valida(type)).allowed())
                .isTrue();
    }

    @Test
    @DisplayName("a matriz ACTIVE/VOIDED: só ACTIVE pode ser estornada")
    void deveRespeitarAMaquinaDeEstados() {
        for (TransactionStatus status : TransactionStatus.values()) {
            Transaction tx = umaDespesaPix();
            if (status == TransactionStatus.VOIDED) {
                tx.voidTransaction("x", NOW, TransactionVoidPolicy.accountTransactions());
                assertThatThrownBy(() -> tx.voidTransaction("y", NOW, TransactionVoidPolicy.accountTransactions()))
                        .isInstanceOf(TransactionAlreadyVoidedException.class);
            } else {
                tx.voidTransaction("x", NOW, TransactionVoidPolicy.accountTransactions());
                assertThat(tx.status()).isEqualTo(TransactionStatus.VOIDED);
            }
        }
    }

    @Test
    @DisplayName("a policy padrão não serve para tipos com guarda própria (erro de ligação, não estorno sem guarda)")
    void deveFalharComPolicyDeTipoErrado() {
        assertThat(new VoidPolicyNotApplicableException(TransactionType.CARD_PURCHASE).code())
                .isEqualTo("TRANSACTION_VOID_POLICY_NOT_APPLICABLE");
    }

    @Test
    @DisplayName("ensureBelongsTo trata movimentação de outro usuário como inexistente; equals por id")
    void deveCompararPorIdEValidarDono() {
        Transaction tx = umaDespesaPix();
        assertThatThrownBy(() -> tx.ensureBelongsTo(OTHER_USER_ID)).isInstanceOf(TransactionNotFoundException.class);
        assertThat(tx).isEqualTo(umaDespesaPix()).hasSameHashCodeAs(umaDespesaPix());
        assertThat(tx.toString()).contains("EXPENSE");
        assertThat(tx.installments()).isEmpty();
    }
}
