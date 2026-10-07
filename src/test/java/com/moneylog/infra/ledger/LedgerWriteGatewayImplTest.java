package com.moneylog.infra.ledger;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.LedgerExceptions.UnbalancedPostingsException;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.infra.ledger.gateways.LedgerWriteGatewayImpl;
import com.moneylog.infra.ledger.persistence.LedgerPostingRepositoryJpa;

@DisplayName("LedgerWriteGateway: validação antes do SQL (T24 CA6)")
class LedgerWriteGatewayImplTest {

    private static final UUID TX = UUID.fromString("00000000-0000-0000-0000-0000000e0001");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    private final LedgerPostingRepositoryJpa repository = mock(LedgerPostingRepositoryJpa.class);
    private final LedgerWriteGatewayImpl gateway = new LedgerWriteGatewayImpl(repository, UUID::randomUUID,
            new ClockTimeProvider(Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC)));

    @Test
    @DisplayName("grupo que não soma zero é recusado antes de qualquer SQL")
    void deveRecusarGrupoDesbalanceado() {
        LedgerEntry debit = new LedgerEntry(PostingSource.transaction(TX),
                new Posting(BookRef.account(UUID.randomUUID()), Money.of("10.00"), DAY), true);
        LedgerEntry credit = new LedgerEntry(PostingSource.transaction(TX),
                new Posting(BookRef.equity(), Money.of("-9.99"), DAY), true);

        assertThatThrownBy(() -> gateway.append(USER_ID, List.of(debit, credit))).isInstanceOf(UnbalancedPostingsException.class);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("lançamento inativo não é gravado")
    void deveRecusarLancamentoInativo() {
        LedgerEntry inactive = new LedgerEntry(PostingSource.transaction(TX),
                new Posting(BookRef.equity(), Money.of("1.00"), DAY), false);

        assertThatThrownBy(() -> gateway.append(USER_ID, List.of(inactive))).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("lista vazia não faz nada")
    void deveIgnorarListaVazia() {
        gateway.append(USER_ID, List.of());

        verifyNoInteractions(repository);
    }
}
