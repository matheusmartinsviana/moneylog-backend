package com.moneylog.core.finance.app.cards;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountArchivedException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.CardAlreadyArchivedException;
import com.moneylog.core.card.exception.CardExceptions.CardHasPendingBalanceException;
import com.moneylog.core.card.exception.CardExceptions.CardNotArchivedException;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.card.exception.CardExceptions.InvalidBillingDayException;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.finance.app.cards.usecase.ArchiveCardUseCase.ArchiveCardCommand;
import com.moneylog.core.finance.app.cards.usecase.ArchiveCardUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.ChangeCardBillingDaysUseCase;
import com.moneylog.core.finance.app.cards.usecase.CreateCardUseCase.CreateCardCommand;
import com.moneylog.core.finance.app.cards.usecase.CreateCardUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.GetCardUseCase.GetCardQuery;
import com.moneylog.core.finance.app.cards.usecase.GetCardUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.ListCardsUseCase.ListCardsQuery;
import com.moneylog.core.finance.app.cards.usecase.ListCardsUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.UnarchiveCardUseCase.UnarchiveCardCommand;
import com.moneylog.core.finance.app.cards.usecase.UnarchiveCardUseCaseImpl;
import com.moneylog.core.finance.app.cards.usecase.UpdateCardDetailsUseCase.UpdateCardDetailsCommand;
import com.moneylog.core.finance.app.cards.usecase.UpdateCardDetailsUseCaseImpl;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Casos de uso de cartão (T51)")
class CardUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final UUID CARD_ID = CreditCardTestFactory.ROXINHO_ID;

    @Mock
    private CardGateway cards;
    @Mock
    private AccountGateway accounts;
    @Mock
    private FinancialRecordsGateway records;
    @Mock
    private IdGenerator ids;
    @Mock
    private TimeProvider time;

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final Account nubank = AccountTestFactory.umaContaCorrente();

    @BeforeEach
    void setUp() {
        when(time.today()).thenReturn(TODAY);
        when(ids.next()).thenReturn(CARD_ID);
        when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.of(new Persisted<>(nubank, NOW, NOW)));
        when(cards.save(any())).thenAnswer(call -> new Persisted<>(call.getArgument(0), NOW, NOW));
        when(records.load(USER_ID)).thenAnswer(call -> scenario.records());
    }

    private void cardExists(CreditCard card) {
        when(cards.findById(USER_ID, card.id())).thenReturn(Optional.of(new Persisted<>(card, NOW, NOW)));
    }

    @Nested
    @DisplayName("criar")
    class Criar {

        private CreateCardUseCaseImpl useCase;

        @BeforeEach
        void montar() {
            useCase = new CreateCardUseCaseImpl(cards, accounts, records, ids, time);
        }

        @Test
        @DisplayName("cadastra o Roxinho com uma regra de fechamento e devolve os números calculados")
        void deveCadastrar() {
            CardDetails details = useCase.handle(new CreateCardCommand(USER_ID, "Roxinho", "Nubank", Money.of("10000.00"), 25, 5,
                    nubank.id()));

            ArgumentCaptor<CreditCard> saved = ArgumentCaptor.forClass(CreditCard.class);
            verify(cards).save(saved.capture());
            CreditCard card = saved.getValue();
            assertThat(card.id()).isEqualTo(CARD_ID);
            assertThat(card.name()).isEqualTo("Roxinho");
            assertThat(card.closingDay()).isEqualTo(25);
            assertThat(card.dueDay()).isEqualTo(5);
            assertThat(card.billingRules()).hasSize(1);
            assertThat(card.billingRules().getFirst().effectiveFrom().toString()).isEqualTo("2026-10");
            assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
            assertThat(card.defaultPaymentAccountId()).isEqualTo(nubank.id());
            assertThat(details.card()).isSameAs(card);
            assertThat(details.limit().available()).isEqualTo(Money.of("10000.00"));
        }

        @Test
        @DisplayName("sem conta padrão também cadastra (e não consulta contas)")
        void deveCadastrarSemContaPadrao() {
            useCase.handle(new CreateCardCommand(USER_ID, "Roxinho", "Nubank", Money.of("10000.00"), 25, 5, null));

            verify(cards).save(any());
            verify(accounts, never()).findById(any(), any());
        }

        @Test
        @DisplayName("dia de fechamento fora de 1..31, limite não positivo e nome vazio: recusa sem gravar")
        void deveRecusarDadosInvalidos() {
            assertThatThrownBy(() -> useCase.handle(new CreateCardCommand(USER_ID, "Roxinho", null, Money.of("10000.00"), 0, 5, null)))
                    .isInstanceOf(InvalidBillingDayException.class).hasFieldOrPropertyWithValue("code", "CARD_INVALID_BILLING_DAY");
            assertThatThrownBy(() -> useCase.handle(new CreateCardCommand(USER_ID, "Roxinho", null, Money.of("0.00"), 25, 5, null)))
                    .hasFieldOrPropertyWithValue("code", "CARD_INVALID_LIMIT");
            assertThatThrownBy(() -> useCase.handle(new CreateCardCommand(USER_ID, " ", null, Money.of("10.00"), 25, 5, null)))
                    .hasFieldOrPropertyWithValue("code", "CARD_NAME_REQUIRED");
            verify(cards, never()).save(any());
        }

        @Test
        @DisplayName("conta padrão inexistente, arquivada ou não líquida: recusa sem gravar")
        void deveValidarContaPadrao() {
            UUID missing = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
            when(accounts.findById(USER_ID, missing)).thenReturn(Optional.empty());
            Account archived = AccountTestFactory.umaContaArquivada();
            when(accounts.findById(USER_ID, archived.id())).thenReturn(Optional.of(new Persisted<>(archived, NOW, NOW)));
            Account invest = AccountTestFactory.umaContaDeInvestimento();
            when(accounts.findById(USER_ID, invest.id())).thenReturn(Optional.of(new Persisted<>(invest, NOW, NOW)));

            assertThatThrownBy(() -> useCase.handle(card(missing))).isInstanceOf(AccountNotFoundException.class);
            assertThatThrownBy(() -> useCase.handle(card(archived.id()))).isInstanceOf(AccountArchivedException.class);
            assertThatThrownBy(() -> useCase.handle(card(invest.id()))).isInstanceOf(AccountNotCashException.class);
            verify(cards, never()).save(any());
        }

        private CreateCardCommand card(UUID accountId) {
            return new CreateCardCommand(USER_ID, "Roxinho", null, Money.of("10000.00"), 25, 5, accountId);
        }
    }

    @Nested
    @DisplayName("editar detalhes")
    class Editar {

        private UpdateCardDetailsUseCaseImpl useCase;
        private final CreditCard card = CreditCardTestFactory.umCartao();
        private final ChangeCardBillingDaysUseCase billingDays = org.mockito.Mockito.mock(ChangeCardBillingDaysUseCase.class);

        @BeforeEach
        void cartao() {
            useCase = new UpdateCardDetailsUseCaseImpl(cards, accounts, records, time, billingDays);
            cardExists(card);
            when(cards.currentBillingDays(USER_ID, CARD_ID)).thenReturn(Optional.of(new CardGateway.BillingDays(card.closingDay(), card.dueDay())));
        }

        private UpdateCardDetailsCommand update(long version, int closing, int due, UUID accountId) {
            return new UpdateCardDetailsCommand(USER_ID, CARD_ID, "Roxinho Ultra", "Nu", Money.of("12000.00"), closing, due, accountId,
                    version);
        }

        @Test
        @DisplayName("troca nome, instituição, limite e conta padrão, travando o cartão antes de ler")
        void deveEditar() {
            UpdateCardDetailsCommand command = update(card.version(), 25, 5, AccountTestFactory.XP_ID);
            Account xp = AccountTestFactory.umaContaSemSaldo();
            when(accounts.findById(USER_ID, xp.id())).thenReturn(Optional.of(new Persisted<>(xp, NOW, NOW)));

            CardDetails details = useCase.handle(command);

            InOrder order = inOrder(cards);
            order.verify(cards).lockForUpdate(USER_ID, CARD_ID);
            order.verify(cards).findById(USER_ID, CARD_ID);
            order.verify(cards).save(card);
            assertThat(details.card().name()).isEqualTo("Roxinho Ultra");
            assertThat(details.card().institution()).isEqualTo("Nu");
            assertThat(details.card().limit()).isEqualTo(Money.of("12000.00"));
            assertThat(details.card().defaultPaymentAccountId()).isEqualTo(xp.id());
        }

        @Test
        @DisplayName("versão desatualizada: VersionConflictException e nada gravado")
        void deveRecusarVersaoVelha() {
            assertThatThrownBy(() -> useCase.handle(update(card.version() + 5, 25, 5, nubank.id())))
                    .isInstanceOf(VersionConflictException.class);
            verify(cards, never()).save(any());
        }

        @Test
        @DisplayName("fechamento ou vencimento diferentes: delega à mudança de dias (S-128) com tudo do PUT, sem travar o cartão aqui nem gravar")
        void deveDelegarAMudancaDeDias() {
            CardDetails changed = CardViews.of(new Persisted<>(card, NOW, NOW), scenario.records(), TODAY);
            when(billingDays.handle(any())).thenReturn(changed);
            LocalDate confirmed = LocalDate.of(2026, 11, 25);

            CardDetails details = useCase.handle(new UpdateCardDetailsCommand(USER_ID, CARD_ID, "Roxinho Ultra", "Nu", Money.of("12000.00"), 10, 5,
                    nubank.id(), confirmed, card.version()));

            assertThat(details).isSameAs(changed);
            ArgumentCaptor<ChangeCardBillingDaysUseCase.ChangeCardBillingDaysCommand> sent =
                    ArgumentCaptor.forClass(ChangeCardBillingDaysUseCase.ChangeCardBillingDaysCommand.class);
            verify(billingDays).handle(sent.capture());
            assertThat(sent.getValue().closingDay()).isEqualTo(10);
            assertThat(sent.getValue().dueDay()).isEqualTo(5);
            assertThat(sent.getValue().confirmedEffectiveFrom()).isEqualTo(confirmed);
            assertThat(sent.getValue().name()).isEqualTo("Roxinho Ultra");
            assertThat(sent.getValue().version()).isEqualTo(card.version());
            verify(cards, never()).lockForUpdate(any(), any());
            verify(cards, never()).save(any());
            useCase.handle(update(card.version(), 25, 28, nubank.id()));
            verify(billingDays, org.mockito.Mockito.times(2)).handle(any());
        }

        @Test
        @DisplayName("a conta padrão só é validada se mudou: editar o nome não falha por causa da conta antiga")
        void naoDeveRevalidarContaInalterada() {
            when(accounts.findById(USER_ID, nubank.id())).thenReturn(Optional.empty());

            useCase.handle(update(card.version(), 25, 5, card.defaultPaymentAccountId()));

            verify(cards).save(card);
        }

        @Test
        @DisplayName("conta padrão nova inexistente: AccountNotFoundException e nada gravado")
        void deveRecusarContaNovaInexistente() {
            UUID missing = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
            when(accounts.findById(USER_ID, missing)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.handle(update(card.version(), 25, 5, missing)))
                    .isInstanceOf(AccountNotFoundException.class);
            verify(cards, never()).save(any());
        }

        @Test
        @DisplayName("cartão inexistente ou de outro usuário: CardNotFoundException")
        void deveRecusarCartaoInexistente() {
            when(cards.findById(USER_ID, CARD_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.handle(update(0, 25, 5, null))).isInstanceOf(CardNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("arquivar e desarquivar")
    class Arquivar {

        private ArchiveCardUseCaseImpl archive;
        private UnarchiveCardUseCaseImpl unarchive;

        @BeforeEach
        void montar() {
            archive = new ArchiveCardUseCaseImpl(cards, records, time);
            unarchive = new UnarchiveCardUseCaseImpl(cards, records, time);
        }

        @Test
        @DisplayName("sem saldo, crédito nem parcela futura: arquiva (travando o cartão primeiro)")
        void deveArquivarCartaoLimpo() {
            CreditCard card = scenario.roxinho;
            cardExists(card);

            CardDetails details = archive.handle(new ArchiveCardCommand(USER_ID, CARD_ID));

            InOrder order = inOrder(cards);
            order.verify(cards).lockForUpdate(USER_ID, CARD_ID);
            order.verify(cards).save(card);
            assertThat(details.card().status()).isEqualTo(CardStatus.ARCHIVED);
        }

        @Test
        @DisplayName("fatura com saldo em aberto: CARD_HAS_PENDING_BALANCE e nada gravado")
        void deveRecusarComSaldo() {
            scenario.compraNoCartao("1283.00", 1);
            cardExists(scenario.roxinho);

            assertThatThrownBy(() -> archive.handle(new ArchiveCardCommand(USER_ID, CARD_ID)))
                    .isInstanceOf(CardHasPendingBalanceException.class)
                    .hasFieldOrPropertyWithValue("code", "CARD_HAS_PENDING_BALANCE");
            verify(cards, never()).save(any());
            assertThat(scenario.roxinho.status()).isEqualTo(CardStatus.ACTIVE);
        }

        @Test
        @DisplayName("parcela futura não cancelada também impede")
        void deveRecusarComParcelaFutura() {
            scenario.caso2CompraParcelada();
            cardExists(scenario.roxinho);

            assertThatThrownBy(() -> archive.handle(new ArchiveCardCommand(USER_ID, CARD_ID)))
                    .isInstanceOf(CardHasPendingBalanceException.class);
        }

        @Test
        @DisplayName("arquivar de novo: CARD_ALREADY_ARCHIVED")
        void deveRecusarArquivarDeNovo() {
            cardExists(CreditCardTestFactory.umCartaoArquivado());

            assertThatThrownBy(() -> archive.handle(new ArchiveCardCommand(USER_ID, CARD_ID)))
                    .isInstanceOf(CardAlreadyArchivedException.class);
            verify(cards, never()).save(any());
        }

        @Test
        @DisplayName("cartão de outro usuário ou inexistente: CardNotFoundException")
        void deveRecusarInexistente() {
            when(cards.findById(USER_ID, CARD_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> archive.handle(new ArchiveCardCommand(USER_ID, CARD_ID))).isInstanceOf(CardNotFoundException.class);
            assertThatThrownBy(() -> unarchive.handle(new UnarchiveCardCommand(USER_ID, CARD_ID)))
                    .isInstanceOf(CardNotFoundException.class);
        }

        @Test
        @DisplayName("desarquivar volta o cartão a ACTIVE; se não está arquivado, CARD_NOT_ARCHIVED")
        void deveDesarquivar() {
            CreditCard archived = CreditCardTestFactory.umCartaoArquivado();
            cardExists(archived);

            CardDetails details = unarchive.handle(new UnarchiveCardCommand(USER_ID, CARD_ID));

            assertThat(details.card().status()).isEqualTo(CardStatus.ACTIVE);
            verify(cards).lockForUpdate(USER_ID, CARD_ID);
            verify(cards).save(archived);
            assertThatThrownBy(() -> unarchive.handle(new UnarchiveCardCommand(USER_ID, CARD_ID)))
                    .isInstanceOf(CardNotArchivedException.class);
        }
    }

    @Nested
    @DisplayName("consultar e listar")
    class Consultar {

        @Test
        @DisplayName("get devolve o cartão com os números do carregamento único")
        void deveConsultar() {
            scenario.caso2CompraParcelada();
            cardExists(scenario.roxinho);

            CardDetails details = new GetCardUseCaseImpl(cards, records, time).handle(new GetCardQuery(USER_ID, CARD_ID));

            assertThat(details.limit().available()).isEqualTo(Money.of("8800.00"));
            assertThat(details.committedFuture()).isEqualTo(Money.of("1000.00"));
        }

        @Test
        @DisplayName("get de cartão inexistente: CardNotFoundException")
        void deveRecusarInexistente() {
            when(cards.findById(USER_ID, CARD_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> new GetCardUseCaseImpl(cards, records, time).handle(new GetCardQuery(USER_ID, CARD_ID)))
                    .isInstanceOf(CardNotFoundException.class);
        }

        @Test
        @DisplayName("list sem status pede ACTIVE ao gateway (S-126) e preserva hasNext")
        void devePedirAtivosPorPadrao() {
            PageQuery page = PageQuery.first();
            when(cards.list(USER_ID, CardStatus.ACTIVE, page))
                    .thenReturn(new Slice<>(List.of(new Persisted<>(scenario.roxinho, NOW, NOW)), true));

            Slice<CardDetails> slice = new ListCardsUseCaseImpl(cards, records, time).handle(new ListCardsQuery(USER_ID, null, page));

            assertThat(slice.items()).hasSize(1);
            assertThat(slice.hasNext()).isTrue();
        }

        @Test
        @DisplayName("list com ARCHIVED repassa o filtro; página vazia não carrega o razão")
        void deveFiltrarArquivados() {
            PageQuery page = PageQuery.first();
            when(cards.list(eq(USER_ID), eq(CardStatus.ARCHIVED), any())).thenReturn(Slice.empty());

            Slice<CardDetails> slice = new ListCardsUseCaseImpl(cards, records, time)
                    .handle(new ListCardsQuery(USER_ID, CardStatus.ARCHIVED, page));

            assertThat(slice.items()).isEmpty();
            verify(records, never()).load(any());
        }
    }
}
