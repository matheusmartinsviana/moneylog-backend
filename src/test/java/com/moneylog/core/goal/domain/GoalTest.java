package com.moneylog.core.goal.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.NUBANK_ID;
import static com.moneylog.core.account.util.factory.AccountTestFactory.XP_INVEST_ID;
import static com.moneylog.core.goal.util.GoalTestFactory.GOAL_ID;
import static com.moneylog.core.goal.util.GoalTestFactory.NOW;
import static com.moneylog.core.goal.util.GoalTestFactory.TODAY;
import static com.moneylog.core.goal.util.GoalTestFactory.at;
import static com.moneylog.core.goal.util.GoalTestFactory.umaMetaDeEmergencia;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.goal.exception.GoalExceptions.GoalAlreadyArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalFieldRequiredException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNameRequiredException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNameTooLongException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReleaseExceedsReservedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReservationAccountNotEligibleException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReservationAmountZeroException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalStateInconsistentException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalTargetNotPositiveException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalTextTooLongException;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.money.MoneyExceptions.AmountAboveLimitException;
import com.moneylog.core.sk.money.Money;

@DisplayName("Meta: reserva lógica, derivados e invariantes (T92, S-147 a S-150, S-156)")
class GoalTest {

    private static final Account NUBANK = AccountTestFactory.umaContaCorrente();
    private static final Account INVEST = AccountTestFactory.umaContaDeInvestimento();

    private static ReserveAllocation on(Account account, String amount) {
        return ReserveAllocation.of(account, Money.of(amount));
    }

    private static Goal comReserva(String amount) {
        Goal goal = umaMetaDeEmergencia();
        goal.reserve(UUID.fromString("00000000-0000-0000-0000-00000000d001"), on(NUBANK, amount), TODAY, null, NOW);
        return goal;
    }

    @Test
    @DisplayName("CA1: meta de 20.000,00 com +8.500,00 na Nubank: current 8.500,00 e progress 0,4250 (42,5%)")
    void deveCalcularOProgresso() {
        Goal goal = comReserva("8500.00");

        assertThat(goal.current(TODAY)).isEqualTo(Money.of("8500.00"));
        assertThat(goal.progress(TODAY).ratioText()).isEqualTo("0.4250");
        assertThat(goal.progress(TODAY).remaining()).isEqualTo(Money.of("11500.00"));
        assertThat(goal.status(TODAY)).isEqualTo(GoalStatus.ACTIVE);
        assertThat(goal.isAchieved(TODAY)).isFalse();
    }

    @Test
    @DisplayName("sem reservas: current 0,00, progress 0.0000 e o que falta é o alvo inteiro")
    void deveComecarZerada() {
        Goal goal = umaMetaDeEmergencia();

        assertThat(goal.current(TODAY)).isEqualTo(Money.of("0.00"));
        assertThat(goal.progress(TODAY).ratioText()).isEqualTo("0.0000");
        assertThat(goal.progress(TODAY).remaining()).isEqualTo(Money.of("20000.00"));
        assertThat(goal.reservations()).isEmpty();
    }

    @Test
    @DisplayName("CA2: liberar 2.000,00 deixa 6.500,00; liberar mais do que o reservado naquela conta é GOAL_RELEASE_EXCEEDS_RESERVED")
    void deveLiberarAteOReservado() {
        Goal goal = comReserva("8500.00");

        goal.reserve(UUID.randomUUID(), on(NUBANK, "-2000.00"), TODAY, "usei", at(1));

        assertThat(goal.current(TODAY)).isEqualTo(Money.of("6500.00"));
        assertThat(goal.reservations()).hasSize(2);
        assertThat(goal.reservations().get(1).isRelease()).isTrue();
        assertThatThrownBy(() -> goal.reserve(UUID.randomUUID(), on(NUBANK, "-6500.01"), TODAY, null, at(2)))
                .isInstanceOf(GoalReleaseExceedsReservedException.class).extracting(e -> ((DomainException) e).code())
                .isEqualTo("GOAL_RELEASE_EXCEEDS_RESERVED");
        assertThat(goal.current(TODAY)).as("a liberação recusada não muda nada").isEqualTo(Money.of("6500.00"));
        goal.reserve(UUID.randomUUID(), on(NUBANK, "-6500.00"), TODAY, null, at(3));
        assertThat(goal.current(TODAY)).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("a liberação vale por conta: o reservado na Nubank não libera a conta de investimento")
    void deveLiberarPorConta() {
        Goal goal = comReserva("1000.00");

        assertThatThrownBy(() -> goal.reserve(UUID.randomUUID(), on(INVEST, "-1.00"), TODAY, null, at(1)))
                .isInstanceOf(GoalReleaseExceedsReservedException.class);
    }

    @Test
    @DisplayName("no mesmo dia a reserva conta antes da liberação, mesmo com o mesmo instante de criação (ordem de gravação não confiável)")
    void deveContarReservaAntesDaLiberacaoNoMesmoDia() {
        Goal goal = umaMetaDeEmergencia();
        goal.reserve(UUID.randomUUID(), on(NUBANK, "100.00"), TODAY, null, NOW);

        goal.reserve(UUID.randomUUID(), on(NUBANK, "-100.00"), TODAY, null, NOW);

        assertThat(goal.current(TODAY)).isEqualTo(Money.of("0.00"));
        Goal restored = Goal.restore(goal.id(), goal.userId(), goal.name(), goal.target(), null, GoalPriority.HIGH, null, false,
                List.of(goal.reservations().get(1), goal.reservations().get(0)));
        restored.reserve(UUID.randomUUID(), on(NUBANK, "50.00"), TODAY, null, NOW);
        restored.reserve(UUID.randomUUID(), on(NUBANK, "-50.00"), TODAY, null, NOW);
        assertThat(restored.current(TODAY)).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("a liberação datada antes da reserva deixaria o saldo negativo naquela data: recusada")
    void deveRecusarLiberacaoAnteriorAReserva() {
        Goal goal = comReserva("1000.00");

        assertThatThrownBy(() -> goal.reserve(UUID.randomUUID(), on(NUBANK, "-100.00"), TODAY.minusDays(1), null, at(1)))
                .isInstanceOf(GoalReleaseExceedsReservedException.class);
        goal.reserve(UUID.randomUUID(), on(NUBANK, "-100.00"), TODAY, null, at(2));
        assertThat(goal.current(TODAY)).isEqualTo(Money.of("900.00"));
    }

    @Test
    @DisplayName("CA3: current ≥ target é ACHIEVED (derivado, S-150), inclusive com mais do que o alvo; continua reservando até liberar")
    void deveDerivarAtingida() {
        Goal goal = comReserva("20000.00");

        assertThat(goal.isAchieved(TODAY)).isTrue();
        assertThat(goal.status(TODAY)).isEqualTo(GoalStatus.ACHIEVED);
        assertThat(goal.reservedOn(NUBANK_ID, TODAY)).isEqualTo(Money.of("20000.00"));
        goal.reserve(UUID.randomUUID(), on(NUBANK, "500.00"), TODAY, null, at(1));
        assertThat(goal.progress(TODAY).ratioText()).isEqualTo("1.0250");
        assertThat(goal.progress(TODAY).remaining()).isEqualTo(Money.of("0.00"));
        goal.reserve(UUID.randomUUID(), on(NUBANK, "-1000.00"), TODAY, null, at(2));
        assertThat(goal.status(TODAY)).isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    @DisplayName("CA3: meta arquivada não aceita reserva nem edição (GOAL_ARCHIVED); arquivar duas vezes e desarquivar a ativa são recusados")
    void deveRecusarMetaArquivada() {
        Goal goal = comReserva("1000.00");
        goal.archive();

        assertThat(goal.isArchived()).isTrue();
        assertThat(goal.status(TODAY)).isEqualTo(GoalStatus.ARCHIVED);
        assertThatThrownBy(() -> goal.reserve(UUID.randomUUID(), on(NUBANK, "1.00"), TODAY, null, at(1))).isInstanceOf(GoalArchivedException.class);
        assertThatThrownBy(() -> goal.update("Outro", Money.of("1.00"), null, GoalPriority.LOW, null)).isInstanceOf(GoalArchivedException.class);
        assertThatThrownBy(goal::archive).isInstanceOf(GoalAlreadyArchivedException.class);
        assertThat(goal.current(TODAY)).as("as reservas ficam guardadas").isEqualTo(Money.of("1000.00"));
        goal.unarchive();
        assertThat(goal.isArchived()).isFalse();
        assertThatThrownBy(goal::unarchive).isInstanceOf(GoalNotArchivedException.class);
    }

    @Test
    @DisplayName("meta arquivada não reserva nada (o dinheiro volta a ficar disponível) e desarquivar devolve o reservado")
    void deveTirarArquivadaDoReservado() {
        Goal goal = comReserva("1000.00");

        assertThat(goal.reservedOn(NUBANK_ID, TODAY)).isEqualTo(Money.of("1000.00"));
        assertThat(goal.reservedAccounts(TODAY)).containsExactly(NUBANK_ID);
        goal.archive();
        assertThat(goal.reservedOn(NUBANK_ID, TODAY)).isEqualTo(Money.of("0.00"));
        assertThat(goal.reservedAccounts(TODAY)).isEmpty();
        assertThat(goal.isUncovered(id -> true, TODAY)).isFalse();
        goal.unarchive();
        assertThat(goal.reservedOn(NUBANK_ID, TODAY)).isEqualTo(Money.of("1000.00"));
    }

    @Test
    @DisplayName("CA4: monthlyNeeded = (20.000,00 − 8.500,00) / 15 meses (out/26 a dez/27) = 766,67, arredondado para cima")
    void deveEstimarOValorMensal() {
        assertThat(comReserva("8500.00").monthlyNeeded(TODAY)).isEqualTo(Money.of("766.67"));
    }

    @Test
    @DisplayName("CA4: o arredondamento é sempre para cima ao centavo (100,00 em 3 meses = 33,34)")
    void deveArredondarParaCima() {
        Goal goal = Goal.create(GOAL_ID, USER_ID, "Viagem", Money.of("100.00"), LocalDate.of(2026, 12, 31), GoalPriority.LOW, null);

        assertThat(goal.monthlyNeeded(TODAY)).isEqualTo(Money.of("33.34"));
    }

    @Test
    @DisplayName("CA4: prazo no mês corrente conta 1 mês; sem prazo ou com prazo vencido é null; atingida é 0,00")
    void deveTratarPrazos() {
        Goal thisMonth = Goal.create(GOAL_ID, USER_ID, "Curto", Money.of("300.00"), LocalDate.of(2026, 10, 31), GoalPriority.LOW, null);
        Goal noDeadline = Goal.create(GOAL_ID, USER_ID, "Sem prazo", Money.of("300.00"), null, GoalPriority.LOW, null);
        Goal expired = Goal.create(GOAL_ID, USER_ID, "Vencida", Money.of("300.00"), LocalDate.of(2026, 10, 9), GoalPriority.LOW, null);
        Goal sameDay = Goal.create(GOAL_ID, USER_ID, "Hoje", Money.of("300.00"), TODAY, GoalPriority.LOW, null);

        assertThat(thisMonth.monthlyNeeded(TODAY)).isEqualTo(Money.of("300.00"));
        assertThat(sameDay.monthlyNeeded(TODAY)).isEqualTo(Money.of("300.00"));
        assertThat(noDeadline.monthlyNeeded(TODAY)).isNull();
        assertThat(expired.monthlyNeeded(TODAY)).isNull();
        assertThat(comReserva("20000.00").monthlyNeeded(TODAY)).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("CA5: o guarda da conta elegível: só CASH ou INVESTMENT em BRL (liquidez ausente ou outra moeda não é)")
    void deveGuardarContasElegiveis() {
        assertThat(ReserveAllocation.isEligible(com.moneylog.core.account.domain.LiquidityClass.CASH, com.moneylog.core.sk.money.CurrencyCode.BRL)).isTrue();
        assertThat(ReserveAllocation.isEligible(com.moneylog.core.account.domain.LiquidityClass.INVESTMENT, com.moneylog.core.sk.money.CurrencyCode.BRL)).isTrue();
        assertThat(ReserveAllocation.isEligible(null, com.moneylog.core.sk.money.CurrencyCode.BRL)).isFalse();
        assertThat(ReserveAllocation.isEligible(com.moneylog.core.account.domain.LiquidityClass.CASH, com.moneylog.core.sk.money.CurrencyCode.of("USD"))).isFalse();
        assertThat(on(INVEST, "10.00").accountId()).isEqualTo(XP_INVEST_ID);
    }

    @Test
    @DisplayName("CA5: reserva em moeda diferente de BRL é GOAL_RESERVATION_ACCOUNT_NOT_ELIGIBLE")
    void deveRecusarMoedaDiferente() {
        Money dollars = Money.of("10.00", com.moneylog.core.sk.money.CurrencyCode.of("USD"));

        assertThatThrownBy(() -> ReserveAllocation.of(NUBANK, dollars)).isInstanceOf(GoalReservationAccountNotEligibleException.class);
    }

    @Test
    @DisplayName("conta arquivada não recebe reserva nova (ACCOUNT_ARCHIVED), mas aceita liberação")
    void deveRecusarReservaEmContaArquivada() {
        Account archived = AccountTestFactory.umaContaArquivada();

        assertThatThrownBy(() -> ReserveAllocation.of(archived, Money.of("1.00"))).isInstanceOf(AccountArchivedException.class);
        assertThat(ReserveAllocation.of(archived, Money.of("-1.00")).amount()).isEqualTo(Money.of("-1.00"));
    }

    @Test
    @DisplayName("CA6: valor 0 e acima do teto (S-78) são erros; o teto vale também para o alvo")
    void deveValidarValores() {
        assertThatThrownBy(() -> ReserveAllocation.of(NUBANK, Money.of("0.00"))).isInstanceOf(GoalReservationAmountZeroException.class);
        assertThatThrownBy(() -> ReserveAllocation.of(NUBANK, null)).isInstanceOf(GoalReservationAmountZeroException.class);
        assertThatThrownBy(() -> ReserveAllocation.of(NUBANK, Money.of("1000000000.00"))).isInstanceOf(AmountAboveLimitException.class);
        assertThatThrownBy(() -> ReserveAllocation.of(NUBANK, Money.of("-1000000000.00"))).isInstanceOf(AmountAboveLimitException.class);
        assertThatThrownBy(() -> umaMetaDeEmergencia().update("X", Money.of("1000000000.00"), null, GoalPriority.LOW, null))
                .isInstanceOf(AmountAboveLimitException.class);
        assertThatThrownBy(() -> umaMetaDeEmergencia().update("X", Money.of("0.00"), null, GoalPriority.LOW, null))
                .isInstanceOf(GoalTargetNotPositiveException.class);
        assertThatThrownBy(() -> umaMetaDeEmergencia().update("X", Money.of("-5.00"), null, GoalPriority.LOW, null))
                .isInstanceOf(GoalTargetNotPositiveException.class);
        assertThatThrownBy(() -> umaMetaDeEmergencia().update("X", null, null, GoalPriority.LOW, null)).isInstanceOf(GoalFieldRequiredException.class);
    }

    @Test
    @DisplayName("CA6: nome vazio ou com mais de 100 caracteres, descrição e nota longas, caracteres de controle e prioridade ausente são erros de campo")
    void deveValidarTextos() {
        Money target = Money.of("10.00");
        assertThatThrownBy(() -> Goal.create(GOAL_ID, USER_ID, " ", target, null, GoalPriority.LOW, null)).isInstanceOf(GoalNameRequiredException.class);
        assertThatThrownBy(() -> Goal.create(GOAL_ID, USER_ID, null, target, null, GoalPriority.LOW, null)).isInstanceOf(GoalNameRequiredException.class);
        assertThatThrownBy(() -> Goal.create(GOAL_ID, USER_ID, "x".repeat(101), target, null, GoalPriority.LOW, null))
                .isInstanceOf(GoalNameTooLongException.class);
        assertThatThrownBy(() -> Goal.create(GOAL_ID, USER_ID, "a\u0000" + "x".repeat(101), target, null, GoalPriority.LOW, null))
                .isInstanceOf(BusinessRuleException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("TEXT_INVALID_CHARACTERS");
        assertThatThrownBy(() -> GoalAction.create(UUID.randomUUID(), "a\u0000" + "x".repeat(101), GoalActionFrequency.MONTHLY, NOW))
                .isInstanceOf(BusinessRuleException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("TEXT_INVALID_CHARACTERS");
        assertThatThrownBy(() -> GoalAction.create(UUID.randomUUID(), "x".repeat(101), GoalActionFrequency.MONTHLY, NOW))
                .isInstanceOf(GoalTextTooLongException.class);
        assertThat(Goal.create(GOAL_ID, USER_ID, "x".repeat(100), target, null, GoalPriority.LOW, null).name()).hasSize(100);
        assertThatThrownBy(() -> Goal.create(GOAL_ID, USER_ID, "a", target, null, GoalPriority.LOW, "d".repeat(501)))
                .isInstanceOf(GoalTextTooLongException.class);
        assertThatThrownBy(() -> Goal.create(GOAL_ID, USER_ID, "a\u0000b", target, null, GoalPriority.LOW, null))
                .isInstanceOf(BusinessRuleException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("TEXT_INVALID_CHARACTERS");
        assertThatThrownBy(() -> Goal.create(GOAL_ID, USER_ID, "a", target, null, null, null)).isInstanceOf(GoalFieldRequiredException.class);
        assertThatThrownBy(() -> Goal.create(GOAL_ID, null, "a", target, null, GoalPriority.LOW, null)).isInstanceOf(GoalFieldRequiredException.class);
        assertThatThrownBy(() -> Goal.create(GOAL_ID, USER_ID, "a", target, LocalDate.of(1999, 12, 31), GoalPriority.LOW, null))
                .isInstanceOf(BusinessRuleException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("DATE_OUT_OF_RANGE");
        assertThat(Goal.create(GOAL_ID, USER_ID, "  Nome  ", target, null, GoalPriority.LOW, "  ").description()).isNull();
        Goal goal = umaMetaDeEmergencia();
        assertThatThrownBy(() -> goal.reserve(UUID.randomUUID(), on(NUBANK, "1.00"), TODAY, "n".repeat(201), NOW))
                .isInstanceOf(GoalTextTooLongException.class);
        assertThatThrownBy(() -> goal.reserve(UUID.randomUUID(), on(NUBANK, "1.00"), null, null, NOW)).isInstanceOf(GoalFieldRequiredException.class);
        goal.reserve(UUID.randomUUID(), on(NUBANK, "1.00"), TODAY, "  ", NOW);
        assertThat(goal.reservations().get(0).note()).isNull();
    }

    @Test
    @DisplayName("update troca nome, alvo, prazo, prioridade e descrição sem mexer nas reservas; o inválido não muda nada")
    void deveEditarOsDados() {
        Goal goal = comReserva("1000.00");

        goal.update("Viagem", Money.of("5000.00"), null, GoalPriority.LOW, "Europa");

        assertThat(goal.name()).isEqualTo("Viagem");
        assertThat(goal.target()).isEqualTo(Money.of("5000.00"));
        assertThat(goal.deadline()).isNull();
        assertThat(goal.priority()).isEqualTo(GoalPriority.LOW);
        assertThat(goal.description()).isEqualTo("Europa");
        assertThat(goal.current(TODAY)).isEqualTo(Money.of("1000.00"));
        assertThatThrownBy(() -> goal.update("", Money.of("1.00"), null, GoalPriority.LOW, null)).isInstanceOf(GoalNameRequiredException.class);
        assertThat(goal.name()).isEqualTo("Viagem");
    }

    @Test
    @DisplayName("CA7: reserva acima do saldo é aceita (sem exceção) e sinalizada quando a conta está descoberta (S-148)")
    void deveSinalizarReservaDescoberta() {
        Goal goal = comReserva("25000.00");

        assertThat(goal.current(TODAY)).isEqualTo(Money.of("25000.00"));
        assertThat(goal.isUncovered(id -> id.equals(NUBANK_ID), TODAY)).isTrue();
        assertThat(goal.isUncovered(id -> false, TODAY)).isFalse();
        assertThat(goal.isUncovered(id -> id.equals(XP_INVEST_ID), TODAY)).isFalse();
    }

    @Test
    @DisplayName("reserva com data futura só conta a partir dela (current e reservedOn até a data consultada)")
    void deveContarReservaFuturaSoNaData() {
        Goal goal = umaMetaDeEmergencia();
        goal.reserve(UUID.randomUUID(), on(NUBANK, "100.00"), TODAY, null, NOW);
        goal.reserve(UUID.randomUUID(), on(NUBANK, "50.00"), TODAY.plusDays(5), null, at(1));

        assertThat(goal.current(TODAY)).isEqualTo(Money.of("100.00"));
        assertThat(goal.current(TODAY.plusDays(5))).isEqualTo(Money.of("150.00"));
        assertThat(goal.reservedOn(NUBANK_ID, TODAY)).isEqualTo(Money.of("100.00"));
        assertThat(goal.reservedOn(NUBANK_ID, TODAY.plusDays(5))).isEqualTo(Money.of("150.00"));
        assertThat(goal.reservedAccounts(TODAY.minusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("reservedAccounts lista só as contas com reserva líquida positiva, na ordem da primeira reserva")
    void deveListarContasComReserva() {
        Goal goal = umaMetaDeEmergencia();
        goal.reserve(UUID.randomUUID(), on(INVEST, "10.00"), TODAY, null, NOW);
        goal.reserve(UUID.randomUUID(), on(NUBANK, "10.00"), TODAY, null, at(1));
        goal.reserve(UUID.randomUUID(), on(INVEST, "-10.00"), TODAY, null, at(2));

        assertThat(goal.reservedAccounts(TODAY)).containsExactly(NUBANK_ID);
    }

    @Test
    @DisplayName("restore recusa reserva de outra meta (dado gravado corrompido) e aceita a das reservas da própria")
    void deveReidratar() {
        GoalReservation own = new GoalReservation(UUID.randomUUID(), GOAL_ID, NUBANK_ID, Money.of("10.00"), TODAY, null, NOW);
        GoalReservation foreign = new GoalReservation(UUID.randomUUID(), UUID.randomUUID(), NUBANK_ID, Money.of("10.00"), TODAY, null, NOW);

        Goal restored = Goal.restore(GOAL_ID, USER_ID, "Meta", Money.of("100.00"), null, GoalPriority.LOW, null, true, List.of(own));

        assertThat(restored.isArchived()).isTrue();
        assertThat(restored.reservations()).containsExactly(own);
        assertThatThrownBy(() -> Goal.restore(GOAL_ID, USER_ID, "Meta", Money.of("100.00"), null, GoalPriority.LOW, null, false, List.of(foreign)))
                .isInstanceOf(GoalStateInconsistentException.class).extracting(e -> ((DomainException) e).code())
                .isEqualTo("GOAL_STATE_INCONSISTENT");
    }

    @Test
    @DisplayName("meta de outro usuário é tratada como inexistente; igualdade e toString pelo id")
    void deveIsolarPorUsuario() {
        Goal goal = umaMetaDeEmergencia();

        goal.ensureBelongsTo(USER_ID);
        assertThatThrownBy(() -> goal.ensureBelongsTo(OTHER_USER_ID)).isInstanceOf(GoalNotFoundException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("GOAL_NOT_FOUND");
        assertThat(goal).isEqualTo(umaMetaDeEmergencia()).hasSameHashCodeAs(umaMetaDeEmergencia());
        assertThat(goal.toString()).contains("Reserva de emergência");
        assertThat(goal.userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("a reserva exige meta, conta, valor e data; zero e moeda diferente são recusados no registro")
    void deveValidarAReserva() {
        Money ten = Money.of("10.00");
        assertThatThrownBy(() -> new GoalReservation(UUID.randomUUID(), null, NUBANK_ID, ten, TODAY, null, NOW)).isInstanceOf(GoalFieldRequiredException.class);
        assertThatThrownBy(() -> new GoalReservation(UUID.randomUUID(), GOAL_ID, null, ten, TODAY, null, NOW)).isInstanceOf(GoalFieldRequiredException.class);
        assertThatThrownBy(() -> new GoalReservation(UUID.randomUUID(), GOAL_ID, NUBANK_ID, null, TODAY, null, NOW)).isInstanceOf(GoalFieldRequiredException.class);
        assertThatThrownBy(() -> new GoalReservation(UUID.randomUUID(), GOAL_ID, NUBANK_ID, Money.of("0.00"), TODAY, null, NOW))
                .isInstanceOf(GoalReservationAmountZeroException.class);
        assertThatThrownBy(() -> new GoalReservation(UUID.randomUUID(), GOAL_ID, NUBANK_ID, Money.of("1.00", com.moneylog.core.sk.money.CurrencyCode.of("USD")), TODAY, null, NOW))
                .isInstanceOf(GoalReservationAccountNotEligibleException.class);
    }
}
