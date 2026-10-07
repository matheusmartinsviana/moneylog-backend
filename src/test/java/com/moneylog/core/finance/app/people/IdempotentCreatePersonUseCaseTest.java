package com.moneylog.core.finance.app.people;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.app.usecase.CreatePersonUseCase;
import com.moneylog.core.debt.app.usecase.CreatePersonUseCase.CreatePersonCommand;
import com.moneylog.core.debt.app.usecase.CreatePersonUseCaseImpl;
import com.moneylog.core.debt.util.InMemoryDebtGateway;
import com.moneylog.core.debt.util.InMemoryPersonGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;

@DisplayName("Criação de pessoa com Idempotency-Key opcional (S-244)")
class IdempotentCreatePersonUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    private final InMemoryPersonGateway people = new InMemoryPersonGateway();
    private final InMemoryDebtGateway debts = new InMemoryDebtGateway();
    private final IdempotencyGateway idempotency = mock(IdempotencyGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private int sequence = 1;
    private final IdGenerator ids = () -> UUID.fromString(String.format("00000000-0000-0000-0000-0000000a%04d", sequence++));
    private final CreatePersonUseCase useCase;

    IdempotentCreatePersonUseCaseTest() {
        when(time.today()).thenReturn(LocalDate.of(2026, 10, 10));
        when(time.now()).thenReturn(NOW);
        useCase = new IdempotentCreatePersonUseCase(new CreatePersonUseCaseImpl(people, debts, ids, time), people, debts, idempotency, ids, time);
    }

    @Test
    @DisplayName("sem chave delega direto; com chave reivindica (escopo próprio), grava a pessoa com o id reservado e o replay devolve a mesma; outro comando é IDEMPOTENCY_KEY_REUSED")
    void deveSerIdempotente() {
        useCase.handle(new CreatePersonCommand(USER_ID, "Sem chave", null));
        verify(idempotency, never()).claim(any(), any(), any(), any(), any(), any(), any());

        UUID key = UUID.fromString("00000000-0000-0000-0000-0000000a0099");
        when(idempotency.claim(eq(USER_ID), eq("POST /v1/people"), eq(key), any(), any(), any(), any())).thenReturn(true);
        var first = useCase.handle(new CreatePersonCommand(USER_ID, "Carlos", "x", key));
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(idempotency).claim(eq(USER_ID), eq("POST /v1/people"), eq(key), hash.capture(), eq(first.person().id()), any(), any());

        when(idempotency.claim(eq(USER_ID), any(), eq(key), any(), any(), any(), any())).thenReturn(false);
        when(idempotency.find(USER_ID, "POST /v1/people", key)).thenReturn(Optional.of(new Claim(hash.getValue(), first.person().id(), NOW)));
        assertThat(useCase.handle(new CreatePersonCommand(USER_ID, "Carlos", "x", key)).person().id()).isEqualTo(first.person().id());
        assertThatThrownBy(() -> useCase.handle(new CreatePersonCommand(USER_ID, "Carla", "x", key))).isInstanceOf(IdempotencyKeyReusedException.class);
        verify(idempotency, times(3)).claim(any(), any(), any(), any(), any(), any(), any());
    }
}
