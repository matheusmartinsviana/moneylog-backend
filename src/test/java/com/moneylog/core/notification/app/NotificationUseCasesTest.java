package com.moneylog.core.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.notification.app.gateways.NotificationGateway;
import com.moneylog.core.notification.app.gateways.NotificationSettingsGateway;
import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.notification.app.gateways.ReminderGateway;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.SaveSettingsCommand;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.SubscribeCommand;
import com.moneylog.core.notification.app.usecase.NotificationUseCasesImpl;
import com.moneylog.core.notification.domain.Notification;
import com.moneylog.core.notification.domain.NotificationKind;
import com.moneylog.core.notification.domain.NotificationSettings;
import com.moneylog.core.notification.domain.QuietHours;
import com.moneylog.core.notification.domain.Reminder;
import com.moneylog.core.notification.domain.SummaryFrequency;
import com.moneylog.core.notification.exception.NotificationExceptions.InvalidPushSubscriptionException;
import com.moneylog.core.notification.exception.NotificationExceptions.InvalidReminderException;
import com.moneylog.core.notification.exception.NotificationExceptions.NotificationNotFoundException;
import com.moneylog.core.notification.exception.NotificationExceptions.NotificationSettingsVersionConflictException;
import com.moneylog.core.notification.exception.NotificationExceptions.PushNotEnabledException;
import com.moneylog.core.notification.exception.NotificationExceptions.PushSubscriptionLimitException;
import com.moneylog.core.notification.exception.NotificationExceptions.ReminderAlreadyDoneException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.util.Ids;

@DisplayName("Casos de uso de notificações (T127 a T130)")
class NotificationUseCasesTest {

    private static final UUID USER = Ids.USER_ID;
    private static final Instant NOW = Instant.parse("2026-11-02T11:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    static final class MemoryNotifications implements NotificationGateway {
        final Map<String, Notification> byIdentity = new HashMap<>();

        @Override
        public boolean insertIfAbsent(Notification n) {
            return byIdentity.putIfAbsent(n.userId() + "|" + n.draft().identity(), n) == null;
        }

        @Override
        public Slice<Notification> list(UUID userId, boolean unreadOnly, NotificationKind kind, PageQuery page) {
            return new Slice<>(byIdentity.values().stream().filter(n -> n.userId().equals(userId) && !n.archived() && (!unreadOnly || n.isUnread()))
                    .toList(), false);
        }

        @Override
        public long unreadCount(UUID userId) {
            return byIdentity.values().stream().filter(n -> n.userId().equals(userId) && !n.archived() && n.isUnread()).count();
        }

        @Override
        public Optional<Notification> find(UUID userId, UUID id) {
            return byIdentity.values().stream().filter(n -> n.userId().equals(userId) && n.id().equals(id)).findFirst();
        }

        @Override
        public void save(Notification notification) {
        }

        @Override
        public int markAllRead(UUID userId, Instant now) {
            byIdentity.values().stream().filter(n -> n.userId().equals(userId)).forEach(n -> n.markRead(now));
            return 0;
        }

        @Override
        public Set<String> identitiesOn(UUID userId, LocalDate day) {
            return byIdentity.values().stream().filter(n -> n.userId().equals(userId) && n.onDate().equals(day)).map(n -> n.draft().identity())
                    .collect(Collectors.toSet());
        }

        @Override
        public int countOn(UUID userId, LocalDate day) {
            return identitiesOn(userId, day).size();
        }

        @Override
        public List<Notification> pushPending(int limit) {
            return byIdentity.values().stream().filter(n -> n.pushEligible() && n.pushSentAt() == null).toList();
        }

        @Override
        public int archiveReadBefore(Instant cutoff) {
            int[] count = {0};
            byIdentity.values().stream().filter(n -> n.readAt() != null && n.readAt().isBefore(cutoff) && !n.archived()).forEach(n -> {
                n.archive();
                count[0]++;
            });
            return count[0];
        }

        @Override
        public List<UUID> allUserIds() {
            return List.of(USER);
        }
    }

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();
    private final MemoryNotifications notifications = new MemoryNotifications();
    private NotificationSettingsGateway settingsGateway;
    private ReminderGateway reminders;
    private ClockTimeProvider time;
    private GenerationContextLoader loader;
    private NotificationUseCasesImpl.GenerateNotifications generate;
    private IdGenerator ids;

    @BeforeEach
    void setUp() {
        AtomicLong sequence = new AtomicLong(1000);
        ids = () -> new UUID(0, sequence.incrementAndGet());
        time = new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("America/Sao_Paulo"));
        FinancialRecordsGateway records = userId -> scenario.records();
        CommitmentSourcesGateway sources = userId -> List.of();
        GoalGateway goals = mock(GoalGateway.class);
        when(goals.findAll(USER)).thenReturn(List.of());
        reminders = mock(ReminderGateway.class);
        when(reminders.openUntil(any(), any())).thenReturn(List.of());
        settingsGateway = mock(NotificationSettingsGateway.class);
        when(settingsGateway.find(USER)).thenReturn(Optional.empty());
        loader = new GenerationContextLoader(records, sources, goals, reminders, settingsGateway, time);
        generate = new NotificationUseCasesImpl.GenerateNotifications(loader, notifications, ids, time);
        scenario.caso2CompraParcelada();
        scenario.compraAVista1083();
        scenario.caso6Emprestimo();
    }

    @Test
    @DisplayName("02/11 08:30 com fatura vencendo em 3 dias: gera o resumo e o aviso; rodar o job de novo não cria nada (idempotência)")
    void idempotencia() {
        int first = generate.handle(USER);
        assertThat(first).isEqualTo(2);
        assertThat(notifications.byIdentity.values()).extracting(Notification::kind)
                .containsExactlyInAnyOrder(NotificationKind.DAILY_SUMMARY, NotificationKind.INVOICE_DUE);
        assertThat(generate.handle(USER)).isZero();
        assertThat(generate.handle(USER)).isZero();
        assertThat(notifications.byIdentity).hasSize(2);
        Notification invoice = notifications.byIdentity.values().stream().filter(n -> n.kind() == NotificationKind.INVOICE_DUE).findFirst().orElseThrow();
        assertThat(invoice.titleKey()).isEqualTo("notification.invoice.due3");
        assertThat(invoice.params()).containsEntry("amount", "1283.00");
        assertThat(invoice.isUnread()).isTrue();
    }

    @Test
    @DisplayName("fatura paga antes do dia cancela a geração futura")
    void faturaPaga() {
        scenario.caso3PagarFatura("1283.00");
        generate.handle(USER);
        assertThat(notifications.byIdentity.values()).extracting(Notification::kind).doesNotContain(NotificationKind.INVOICE_DUE);
    }

    @Test
    @DisplayName("fora da janela de silêncio e com push ligado: elegível a push; na janela só vai para a central")
    void silencio() {
        NotificationSettings pushOn = NotificationSettings.defaults(USER);
        pushOn.update(true, LocalTime.of(8, 0), SummaryFrequency.DAILY, Map.of(), QuietHours.DEFAULT, true, null);
        when(settingsGateway.find(USER)).thenReturn(Optional.of(pushOn));
        generate.handle(USER);
        assertThat(notifications.byIdentity.values()).allSatisfy(n -> assertThat(n.pushEligible()).isTrue());

        MemoryNotifications night = new MemoryNotifications();
        ClockTimeProvider nightTime = new ClockTimeProvider(Clock.fixed(Instant.parse("2026-11-03T01:30:00Z"), ZoneOffset.UTC), ZoneId.of("America/Sao_Paulo"));
        NotificationSettings quiet = NotificationSettings.defaults(USER);
        quiet.update(true, LocalTime.of(0, 0), SummaryFrequency.DAILY, Map.of(), QuietHours.DEFAULT, true, null);
        when(settingsGateway.find(USER)).thenReturn(Optional.of(quiet));
        GenerationContextLoader nightLoader = new GenerationContextLoader(u -> scenario.records(), u -> List.of(), mock(GoalGateway.class), reminders,
                settingsGateway, nightTime);
        new NotificationUseCasesImpl.GenerateNotifications(nightLoader, night, ids, nightTime).handle(USER);
        assertThat(night.byIdentity.values()).isNotEmpty().allSatisfy(n -> assertThat(n.pushEligible()).isFalse());
    }

    @Test
    @DisplayName("mais de 5 candidatas no dia: 4 + 'N lembretes' agrupada; segunda passada não muda nada")
    void limiteDiario() {
        for (int i = 0; i < 8; i++) {
            Reminder r = Reminder.create(new UUID(2, i), USER, "Lembrete " + i, TODAY, null, null, null, null, NOW);
            when(reminders.openUntil(USER, TODAY)).thenReturn(append(r));
        }
        generate.handle(USER);
        assertThat(notifications.byIdentity).hasSize(5);
        assertThat(notifications.byIdentity.values()).extracting(Notification::kind).contains(NotificationKind.GROUPED);
        int before = notifications.byIdentity.size();
        generate.handle(USER);
        assertThat(notifications.byIdentity).hasSize(before);
    }

    private final List<Reminder> accumulated = new ArrayList<>();

    private List<Reminder> append(Reminder r) {
        accumulated.add(r);
        return List.copyOf(accumulated);
    }

    @Test
    @DisplayName("configurações: salvar com a versão certa avança; versão velha é conflito; padrão tem versão 0")
    void configuracoes() {
        NotificationUseCasesImpl.SaveSettings save = new NotificationUseCasesImpl.SaveSettings(settingsGateway);
        when(settingsGateway.save(any(), org.mockito.ArgumentMatchers.eq(0L))).thenAnswer(inv -> Optional.of(inv.getArgument(0)));
        NotificationSettings saved = save.handle(new SaveSettingsCommand(USER, true, LocalTime.of(7, 30), SummaryFrequency.ONLY_DUE,
                Map.of(NotificationKind.INVOICE_DUE, false), QuietHours.DEFAULT, false, "Viana", 0));
        assertThat(saved.summaryTime()).isEqualTo(LocalTime.of(7, 30));
        assertThat(saved.isEnabled(NotificationKind.INVOICE_DUE)).isFalse();
        assertThat(saved.displayName()).isEqualTo("Viana");
        NotificationSettings stored = NotificationSettings.restore(USER, true, LocalTime.of(8, 0), SummaryFrequency.DAILY, Map.of(), QuietHours.DEFAULT, false,
                null, 3);
        when(settingsGateway.find(USER)).thenReturn(Optional.of(stored));
        assertThatThrownBy(() -> save.handle(new SaveSettingsCommand(USER, true, LocalTime.of(8, 0), SummaryFrequency.DAILY, Map.of(),
                QuietHours.DEFAULT, false, null, 2))).isInstanceOf(NotificationSettingsVersionConflictException.class);
        verify(settingsGateway, never()).save(any(), org.mockito.ArgumentMatchers.eq(2L));
        org.mockito.Mockito.doReturn(Optional.empty()).when(settingsGateway).save(any(), org.mockito.ArgumentMatchers.eq(3L));
        assertThatThrownBy(() -> save.handle(new SaveSettingsCommand(USER, true, LocalTime.of(8, 0), SummaryFrequency.DAILY, Map.of(),
                QuietHours.DEFAULT, false, null, 3))).isInstanceOf(NotificationSettingsVersionConflictException.class);
        assertThat(new NotificationUseCasesImpl.GetSettings(mock(NotificationSettingsGateway.class)).handle(USER).version()).isZero();
    }

    @Test
    @DisplayName("ler: marca (idempotente, mantém o 1º instante), contador cai, notificação de outro usuário é 404; retenção arquiva lidas > 90 dias sem apagar")
    void lerEArquivar() {
        generate.handle(USER);
        Notification n = notifications.byIdentity.values().iterator().next();
        NotificationUseCasesImpl.MarkRead markRead = new NotificationUseCasesImpl.MarkRead(notifications, time);
        long unread = notifications.unreadCount(USER);
        markRead.handle(USER, n.id());
        Instant firstRead = n.readAt();
        markRead.handle(USER, n.id());
        assertThat(n.readAt()).isEqualTo(firstRead);
        assertThat(notifications.unreadCount(USER)).isEqualTo(unread - 1);
        assertThatThrownBy(() -> markRead.handle(Ids.OTHER_USER_ID, n.id())).isInstanceOf(NotificationNotFoundException.class);

        ClockTimeProvider future = new ClockTimeProvider(Clock.fixed(NOW.plus(Duration.ofDays(91)), ZoneOffset.UTC), ZoneId.of("America/Sao_Paulo"));
        assertThat(new NotificationUseCasesImpl.ArchiveOld(notifications, future).handle()).isEqualTo(1);
        assertThat(n.archived()).isTrue();
        assertThat(notifications.byIdentity).hasSize(2);
        assertThat(new NotificationUseCasesImpl.ArchiveOld(notifications, time).handle()).isZero();
    }

    @Test
    @DisplayName("lembrete: concluir e depois editar/adiar é recusado; título vazio é inválido")
    void lembretes() {
        Reminder r = Reminder.create(new UUID(3, 3), USER, "Cobrar João", TODAY, null, null, null, null, NOW);
        r.done(NOW);
        assertThatThrownBy(() -> r.snooze(TODAY.plusDays(1))).isInstanceOf(ReminderAlreadyDoneException.class);
        assertThatThrownBy(() -> Reminder.create(new UUID(3, 4), USER, " ", TODAY, null, null, null, null, NOW)).isInstanceOf(InvalidReminderException.class);
        assertThatThrownBy(() -> Reminder.create(new UUID(3, 4), USER, "x", TODAY, null, null, "DEBT", null, NOW)).isInstanceOf(InvalidReminderException.class);
        Reminder open = Reminder.create(new UUID(3, 5), USER, "Pagar", TODAY, LocalTime.of(9, 0), "nota", "DEBT", new UUID(3, 6), NOW);
        open.snooze(TODAY.plusDays(3));
        assertThat(open.remindOn()).isEqualTo(TODAY.plusDays(3));
        assertThat(open.remindAt()).isEqualTo(LocalTime.of(9, 0));
    }

    private static final class FakeSender implements PushSender {
        final boolean enabled;
        Result result = Result.SENT;
        final List<PushMessage> sent = new ArrayList<>();

        FakeSender(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public Result send(PushSubscription subscription, PushMessage message) {
            sent.add(message);
            return result;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public String publicKey() {
            return enabled ? "chave-publica" : null;
        }
    }

    private PushSubscription subscription(UUID id, String endpoint) {
        return new PushSubscription(id, USER, endpoint, "p", "a", null, NOW);
    }

    @Test
    @DisplayName("assinar: push desligado = PUSH_NOT_ENABLED; endpoint http e chaves vazias inválidos; no 11º dispositivo, limite; endpoint repetido não conta")
    void assinar() {
        PushSubscriptionGateway gateway = mock(PushSubscriptionGateway.class);
        when(gateway.upsert(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThatThrownBy(() -> new NotificationUseCasesImpl.SubscribePush(gateway, new FakeSender(false), ids, time)
                .handle(new SubscribeCommand(USER, "https://fcm.googleapis.com/1", "p", "a", null))).isInstanceOf(PushNotEnabledException.class);
        NotificationUseCasesImpl.SubscribePush subscribe = new NotificationUseCasesImpl.SubscribePush(gateway, new FakeSender(true), ids, time);
        assertThatThrownBy(() -> subscribe.handle(new SubscribeCommand(USER, "http://inseguro", "p", "a", null)))
                .isInstanceOf(InvalidPushSubscriptionException.class);
        assertThatThrownBy(() -> subscribe.handle(new SubscribeCommand(USER, "https://fcm.googleapis.com/1", " ", "a", null)))
                .isInstanceOf(InvalidPushSubscriptionException.class);
        when(gateway.listByUser(USER)).thenReturn(List.of(subscription(new UUID(4, 1), "https://fcm.googleapis.com/known")));
        when(gateway.count(USER)).thenReturn(10);
        assertThat(subscribe.handle(new SubscribeCommand(USER, "https://fcm.googleapis.com/known", "p", "a", "UA")).endpoint())
                .isEqualTo("https://fcm.googleapis.com/known");
        assertThatThrownBy(() -> subscribe.handle(new SubscribeCommand(USER, "https://fcm.googleapis.com/new", "p", "a", null)))
                .isInstanceOf(PushSubscriptionLimitException.class);
    }

    @Test
    @DisplayName("despacho: push desligado não envia; ligado envia só os parâmetros mínimos (sem valores por padrão), marca como enviado e remove assinatura morta")
    void despacho() {
        generate.handle(USER);
        notifications.byIdentity.values().forEach(n -> {
            Notification pushed = Notification.create(n.id(), n.userId(), n.draft(), NOW, true);
            notifications.byIdentity.put(n.userId() + "|" + n.draft().identity(), pushed);
        });
        PushSubscriptionGateway gateway = mock(PushSubscriptionGateway.class);
        PushSubscription live = subscription(new UUID(4, 1), "https://fcm.googleapis.com/live");
        when(gateway.listByUser(USER)).thenReturn(List.of(live));

        FakeSender off = new FakeSender(false);
        assertThat(new NotificationUseCasesImpl.DispatchPush(notifications, gateway, off, time, false).handle()).isZero();
        assertThat(off.sent).isEmpty();

        FakeSender on = new FakeSender(true);
        assertThat(new NotificationUseCasesImpl.DispatchPush(notifications, gateway, on, time, false).handle()).isEqualTo(2);
        assertThat(on.sent).hasSize(2).allSatisfy(m -> assertThat(m.params()).doesNotContainKeys("amount", "total", "available", "committed7d"));
        assertThat(notifications.byIdentity.values()).allSatisfy(n -> assertThat(n.pushSentAt()).isNotNull());
        assertThat(new NotificationUseCasesImpl.DispatchPush(notifications, gateway, on, time, false).handle()).isZero();

        FakeSender withAmounts = new FakeSender(true);
        notifications.byIdentity.values().forEach(n -> n.pushSent(null));
        new NotificationUseCasesImpl.DispatchPush(notifications, gateway, withAmounts, time, true).handle();
        assertThat(withAmounts.sent).anySatisfy(m -> assertThat(m.params()).containsKey("amount"));
    }

    @Test
    @DisplayName("despacho: 410 remove a assinatura; 429 deixa na fila para tentar de novo (recuo)")
    void despachoFalhas() {
        generate.handle(USER);
        notifications.byIdentity.replaceAll((k, n) -> Notification.create(n.id(), n.userId(), n.draft(), NOW, true));
        PushSubscriptionGateway gateway = mock(PushSubscriptionGateway.class);
        when(gateway.listByUser(USER)).thenReturn(List.of(subscription(new UUID(4, 1), "https://fcm.googleapis.com/dead")));
        FakeSender gone = new FakeSender(true);
        gone.result = PushSender.Result.GONE;
        new NotificationUseCasesImpl.DispatchPush(notifications, gateway, gone, time, false).handle();
        verify(gateway, org.mockito.Mockito.atLeastOnce()).deleteByEndpoint("https://fcm.googleapis.com/dead");

        notifications.byIdentity.replaceAll((k, n) -> Notification.create(n.id(), n.userId(), n.draft(), NOW, true));
        FakeSender busy = new FakeSender(true);
        busy.result = PushSender.Result.RETRY_LATER;
        assertThat(new NotificationUseCasesImpl.DispatchPush(notifications, gateway, busy, time, false).handle()).isZero();
        assertThat(notifications.pushPending(10)).hasSize(2);
    }

    @Test
    @DisplayName("mensagem diária pela mesma fonte do dashboard: available e committed7d do FinancialRecords; sem nome, sem 'name'")
    void mensagemDiaria() {
        var view = new NotificationUseCasesImpl.GetDailyMessage(loader, time).handle(USER);
        assertThat(view.source()).isEqualTo("STATIC");
        assertThat(view.message().key()).isEqualTo("daily.summary");
        assertThat(view.message().params()).containsKeys("available", "committed7d").doesNotContainKey("name");
        assertThat(view.generatedAt()).isEqualTo(NOW);
        verify(settingsGateway).find(USER);
    }
}
