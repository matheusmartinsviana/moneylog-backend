package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T3/T7: isolamento entre usuários nos endpoints existentes, com dois membros e com o dono")
class MultiUserIsolationQaIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager tm;
    @Autowired
    private MutableClock clock;

    private CardQaWorld world;
    private Actor a;
    private Actor b;
    private String marker;
    private Seed seedA;

    private record Seed(String marker, String expenseTx, String purchaseTx, String invoice, String goal, String note, String reminder, String person, String notification,
                        String push) {
    }

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("isoA");
        b = world.new Actor("isoB");
        seedA = seed(a);
    }

    private Seed seed(Actor x) throws Exception {
        String marker = "zqsecret" + UUID.randomUUID().toString().substring(0, 8);
        this.marker = marker;
        String expense = CardQaWorld.ok(x.call(HttpMethod.POST, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"12.34\",\"date\":\"2026-10-10\","
                + "\"description\":\"" + marker + " despesa\",\"accountId\":\"" + x.nubank + "\",\"categoryId\":\"" + x.food + "\",\"method\":\"PIX\"}",
                UUID.randomUUID().toString())).node().get("id").asString();
        String purchase = CardQaWorld.ok(x.purchase("300.00", 3, "2026-10-10", marker + " compra")).node().get("id").asString();
        String invoice = x.invoices().get(0).get("id").asString();
        String goal = GoalQa.goal(x, marker + " meta", "1000.00", null);
        String note = NoteQa.createOk(world, x, marker + "/nota.md", "corpo " + marker);
        String reminder = CardQaWorld.ok(x.call(HttpMethod.POST, "/v1/reminders", "{\"title\":\"" + marker + " lembrete\",\"remindOn\":\"2026-10-20\"}", null))
                .node().get("id").asString();
        String person = CardQaWorld.ok(x.call(HttpMethod.POST, "/v1/people", "{\"name\":\"" + marker + " pessoa\"}", null)).node().get("id").asString();
        UUID notification = UUID.randomUUID();
        UUID push = UUID.randomUUID();
        new TransactionTemplate(tm).executeWithoutResult(s -> {
            jdbc.update("insert into notifications (id, user_id, kind, ref_type, ref_id, on_date, title_key, params, created_at) "
                    + "values (?, ?, 'REMINDER', 'REMINDER', ?, '2026-10-10', 'reminder.title', ?, now())", notification, x.id, UUID.randomUUID(),
                    "{\"title\":\"" + marker + "\"}");
            jdbc.update("insert into push_subscriptions (id, user_id, endpoint, p256dh, auth, created_at) values (?, ?, ?, 'p', 'a', now())", push, x.id,
                    "https://push.example/" + marker);
        });
        return new Seed(marker, expense, purchase, invoice, goal, note, reminder, person, notification.toString(), push.toString());
    }

    private MvcResult send(String bearer, HttpMethod method, String url, String body) throws Exception {
        MockHttpServletRequestBuilder r = MockMvcRequestBuilders.request(method, url).header(HttpHeaders.AUTHORIZATION, bearer);
        if (body != null) {
            r.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (method == HttpMethod.POST && url.equals("/v1/transactions") || url.endsWith("/payments") || url.endsWith("/reservations")) {
            r.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return mvc.perform(r).andReturn();
    }

    private String fingerprint(UUID userId) {
        List<String> tables = jdbc.queryForList("select table_name from information_schema.columns where table_schema = 'public' and column_name = 'user_id' "
                + "and table_name not in ('refresh_tokens','idempotency_keys') order by 1", String.class);
        StringBuilder sb = new StringBuilder();
        for (String t : tables) {
            sb.append(t).append('=').append(jdbc.queryForObject("select coalesce(md5(string_agg(x::text, ',' order by x::text)), '-') from " + t
                    + " x where user_id = ?", String.class, userId)).append(';');
        }
        return sb.toString();
    }

    private record Attack(HttpMethod method, String url, String body, boolean bodyReferencesForeign) {
        static Attack by(HttpMethod m, String url, String body) {
            return new Attack(m, url, body, false);
        }

        static Attack withForeignBody(HttpMethod m, String url, String body) {
            return new Attack(m, url, body, true);
        }

        @Override
        public String toString() {
            return method + " " + url;
        }
    }

    private List<Attack> attacks(Seed s, Actor victim, String attackerAccount) {
        String v = victim.nubank;
        return List.of(
                Attack.by(HttpMethod.GET, "/v1/accounts/" + v, null),
                Attack.by(HttpMethod.POST, "/v1/accounts/" + v + "/archive", null),
                Attack.by(HttpMethod.GET, "/v1/transactions/" + s.expenseTx, null),
                Attack.by(HttpMethod.POST, "/v1/transactions/" + s.expenseTx + "/void", "{\"reason\":\"x\"}"),
                Attack.by(HttpMethod.POST, "/v1/transactions/" + s.purchaseTx + "/void", "{\"reason\":\"x\"}"),
                Attack.by(HttpMethod.POST, "/v1/transactions/" + s.purchaseTx + "/cancel-installments", "{\"reason\":\"x\"}"),
                Attack.by(HttpMethod.GET, "/v1/cards/" + victim.card, null),
                Attack.by(HttpMethod.POST, "/v1/cards/" + victim.card + "/archive", null),
                Attack.by(HttpMethod.GET, "/v1/cards/" + victim.card + "/invoices", null),
                Attack.by(HttpMethod.GET, "/v1/cards/" + victim.card + "/future-installments", null),
                Attack.by(HttpMethod.GET, "/v1/invoices/" + s.invoice, null),
                Attack.by(HttpMethod.GET, "/v1/invoices/" + s.invoice + "/payment-preview?accountId=" + attackerAccount + "&amount=1.00", null),
                Attack.withForeignBody(HttpMethod.POST, "/v1/invoices/" + s.invoice + "/payments",
                        "{\"accountId\":\"" + attackerAccount + "\",\"amount\":\"1.00\",\"date\":\"2026-10-10\"}"),
                Attack.by(HttpMethod.GET, "/v1/goals/" + s.goal, null),
                Attack.by(HttpMethod.POST, "/v1/goals/" + s.goal + "/archive", null),
                Attack.withForeignBody(HttpMethod.POST, "/v1/goals/" + s.goal + "/reservations",
                        "{\"accountId\":\"" + attackerAccount + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\"}"),
                Attack.by(HttpMethod.GET, "/v1/notes/" + s.note, null),
                Attack.by(HttpMethod.POST, "/v1/notes/" + s.note + "/trash", null),
                Attack.by(HttpMethod.GET, "/v1/notes/" + s.note + "/backlinks", null),
                Attack.by(HttpMethod.POST, "/v1/notifications/" + s.notification + "/read", null),
                Attack.by(HttpMethod.POST, "/v1/reminders/" + s.reminder + "/done", null),
                Attack.by(HttpMethod.POST, "/v1/reminders/" + s.reminder + "/snooze", "{\"days\":3}"),
                Attack.by(HttpMethod.GET, "/v1/people/" + s.person, null),
                Attack.by(HttpMethod.POST, "/v1/people/" + s.person + "/archive", null),
                Attack.by(HttpMethod.DELETE, "/v1/push/subscriptions/" + s.push, null));
    }

    private static final List<String> LISTS = List.of("/v1/accounts?pageSize=50", "/v1/categories?includeArchived=true", "/v1/transactions?pageSize=50",
            "/v1/entries?competence=2026-10&pageSize=50", "/v1/cards", "/v1/goals", "/v1/notes", "/v1/notes?status=TRASHED", "/v1/notes/tags",
            "/v1/notifications?status=ALL", "/v1/reminders", "/v1/people", "/v1/dashboard/summary", "/v1/dashboard/upcoming", "/v1/summaries/2026-10",
            "/v1/projections?horizon=M12", "/v1/insights", "/v1/debts", "/v1/recurrences", "/v1/commitments", "/v1/imports", "/v1/exports/backup",
            "/v1/exports/transactions.csv", "/v1/owner-statement", "/v1/purchase-groups", "/v1/categorization-rules", "/v1/settings/notifications",
            "/v1/reports/income-expenses?from=2026-10-01&to=2026-10-31", "/v1/reports/spending-by-category?from=2026-10-01&to=2026-10-31");

    private List<String> leaks(String bearer, Seed victimSeed, Actor victim) throws Exception {
        List<String> found = new ArrayList<>();
        List<String> ids = List.of(victimSeed.expenseTx, victimSeed.purchaseTx, victimSeed.invoice, victimSeed.goal, victimSeed.note, victimSeed.reminder,
                victimSeed.person, victimSeed.notification, victimSeed.push, victim.nubank, victim.card, victim.food, victim.tech, victim.income,
                victim.id.toString());
        List<String> urls = new ArrayList<>(LISTS);
        urls.add("/v1/search?q=" + victimSeed.marker);
        for (String url : urls) {
            MvcResult r = send(bearer, HttpMethod.GET, url, null);
            int status = r.getResponse().getStatus();
            String text = r.getResponse().getContentAsString();
            if (status >= 500) {
                found.add(url + " -> " + status);
            }
            if (text.contains(victimSeed.marker) && !url.startsWith("/v1/search")) {
                found.add(url + " vaza o marcador do outro usuário");
            }
            for (String id : ids) {
                if (text.contains(id) && !url.contains(id)) {
                    found.add(url + " vaza o id " + id);
                }
            }
        }
        return found;
    }

    private void attackAndVerify(String attackerBearer, Seed victimSeed, Actor victim, String attackerAccount, String label) throws Exception {
        String before = fingerprint(victim.id);
        long ledgerBefore = victim.ledgerSum();
        List<String> violations = new ArrayList<>();
        for (Attack attack : attacks(victimSeed, victim, attackerAccount)) {
            MvcResult r = send(attackerBearer, attack.method(), attack.url(), attack.body());
            int status = r.getResponse().getStatus();
            boolean ok = attack.bodyReferencesForeign() ? status == 404 || status == 422 : status == 404;
            if (!ok) {
                violations.add(label + " " + attack + " -> " + status + " " + r.getResponse().getContentAsString());
            } else if (r.getResponse().getContentAsString().contains(victim.id.toString())) {
                violations.add(label + " " + attack + " cita o id do usuário atacado");
            }
        }
        violations.addAll(leaks(attackerBearer, victimSeed, victim));
        assertThat(fingerprint(victim.id)).as(label + ": nenhuma linha de " + victim.id + " mudou").isEqualTo(before);
        assertThat(victim.ledgerSum()).isEqualTo(ledgerBefore);
        assertThat(violations).as(label).isEmpty();
    }

    @Test
    @DisplayName("membro B não lê nem altera nada de A: 404 em contas, lançamentos, cartões, faturas, metas, notas, notificações, lembretes, pessoas e push; nenhuma lista, busca nem exportação vaza A; nenhuma linha de A muda")
    void membroNaoVeNemAlteraOutroMembro() throws Exception {
        attackAndVerify(b.bearer, seedA, a, b.nubank, "B->A");
    }

    @Test
    @DisplayName("sentido inverso: A também não alcança B, e a notificação de A continua não lida, o push de A e o lembrete de A continuam intactos")
    void simetria() throws Exception {
        Seed seedB = seed(b);
        attackAndVerify(a.bearer, seedB, b, a.nubank, "A->B");
        assertThat(jdbc.queryForObject("select count(*) from push_subscriptions where user_id = ?", Integer.class, b.id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select read_at is null from notifications where id = ?::uuid", Boolean.class, seedB.notification)).isTrue();
    }

    @Test
    @DisplayName("o dono não é super-usuário dos dados do membro: 404 nos recursos do membro, nenhuma lista do dono contém dados do membro e nada do membro muda")
    void donoNaoVeDadosDoMembro() throws Exception {
        String ownerBearer = auth.ownerBearer();
        attackAndVerify(ownerBearer, seedA, a, ownerAccountOrAny(ownerBearer), "OWNER->A");
    }

    private String ownerAccountOrAny(String ownerBearer) throws Exception {
        MvcResult r = send(ownerBearer, HttpMethod.GET, "/v1/accounts?pageSize=1", null);
        var items = om.readTree(r.getResponse().getContentAsString()).path("items");
        return items.isEmpty() ? UUID.randomUUID().toString() : items.get(0).get("id").asString();
    }

    @Test
    @DisplayName("membro B não alcança dados do dono por id forjado nem por lista (o dono vira vítima)")
    void membroNaoVeDadosDoDono() throws Exception {
        String ownerBearer = auth.ownerBearer();
        String body = "{\"name\":\"Conta do dono " + marker + "\",\"type\":\"CHECKING\",\"institution\":\"X\",\"openingBalance\":{\"amount\":\"1.00\",\"currency\":\"BRL\"},"
                + "\"openingDate\":\"2026-10-01\"}";
        MvcResult created = send(ownerBearer, HttpMethod.POST, "/v1/accounts", body);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String ownerAccount = om.readTree(created.getResponse().getContentAsString()).get("id").asString();

        MvcResult read = send(b.bearer, HttpMethod.GET, "/v1/accounts/" + ownerAccount, null);
        MvcResult list = send(b.bearer, HttpMethod.GET, "/v1/accounts?pageSize=50", null);
        MvcResult search = send(b.bearer, HttpMethod.GET, "/v1/search?q=" + marker, null);
        MvcResult backup = send(b.bearer, HttpMethod.GET, "/v1/exports/backup", null);

        assertThat(read.getResponse().getStatus()).isEqualTo(404);
        for (MvcResult r : List.of(list, search, backup)) {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).doesNotContain(ownerAccount).doesNotContain("Conta do dono");
        }
    }
}
