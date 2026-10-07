package com.moneylog.core.sk.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;
import com.moneylog.core.sk.ledger.LedgerExceptions.UnbalancedPostingsException;
import com.moneylog.core.sk.money.Money;

@DisplayName("PostingSource, LedgerEntry e VoidDecision (S-45, S-36)")
class LedgerEntryTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000e0001");
    private static final UUID INSTALLMENT = UUID.fromString("00000000-0000-0000-0000-0000000e0002");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    @Test
    @DisplayName("as quatro origens; parcela só em TRANSACTION; transactionId só em TRANSACTION")
    void deveCriarOrigens() {
        assertThat(PostingSource.transaction(ID).transactionId()).isEqualTo(ID);
        assertThat(PostingSource.installment(ID, INSTALLMENT).installmentId()).isEqualTo(INSTALLMENT);
        assertThat(PostingSource.accountOpening(ID).type()).isEqualTo(PostingSourceType.ACCOUNT_OPENING);
        assertThat(PostingSource.debtRecognition(ID).transactionId()).isNull();
        assertThat(PostingSource.debtCancellation(ID).type()).isEqualTo(PostingSourceType.DEBT_CANCELLATION);
        assertThatThrownBy(() -> new PostingSource(PostingSourceType.DEBT_RECOGNITION, ID, INSTALLMENT)).isInstanceOf(InvalidPostingException.class);
        assertThatThrownBy(() -> new PostingSource(null, ID, null)).isInstanceOf(InvalidPostingException.class);
        assertThatThrownBy(() -> new PostingSource(PostingSourceType.TRANSACTION, null, null)).isInstanceOf(InvalidPostingException.class);
        assertThatThrownBy(() -> PostingSource.installment(ID, null)).isInstanceOf(InvalidPostingException.class);
    }

    @Test
    @DisplayName("grupo ativo exige Σ = 0; desativar devolve cópia inativa e é idempotente")
    void deveDesativarSemReativar() {
        Posting debit = new Posting(BookRef.account(ID), Money.of("10.00"), DAY);
        Posting credit = new Posting(BookRef.equity(), Money.of("-10.00"), DAY);
        List<LedgerEntry> group = LedgerEntry.activeGroup(PostingSource.accountOpening(ID), List.of(debit, credit));

        assertThat(group).allMatch(LedgerEntry::active);
        LedgerEntry inactive = group.getFirst().deactivate();
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.deactivate()).isSameAs(inactive);
        assertThatThrownBy(() -> LedgerEntry.activeGroup(PostingSource.accountOpening(ID), List.of(debit)))
                .isInstanceOf(UnbalancedPostingsException.class);
        assertThatThrownBy(() -> new LedgerEntry(null, debit, true)).isInstanceOf(InvalidPostingException.class);
    }

    @Test
    @DisplayName("recusa exige code UPPER_SNAKE_CASE")
    void deveValidarDecisao() {
        assertThat(VoidDecision.allow().allowed()).isTrue();
        assertThat(VoidDecision.refuse("DEBT_CLOSED", "x").code()).isEqualTo("DEBT_CLOSED");
        assertThatThrownBy(() -> VoidDecision.refuse("nope", "x")).isInstanceOf(InvalidPostingException.class);
        assertThatThrownBy(() -> VoidDecision.refuse(null, "x")).isInstanceOf(InvalidPostingException.class);
    }
}
