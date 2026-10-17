package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@Tag("slow")
@DisplayName("QA T190 (fase 0): propriedade da série, do consumo por categoria e das variações contra oráculo SQL")
class RedesignFase0PropertyQaIT {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final int OPS = Integer.getInteger("qa.ops", 160);

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

    @BeforeEach
    void cenario() {
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
    }

    record Era(String name, long seed, Instant start) {
    }

    private static final List<Era> ERAS = List.of(
            new Era("verao-2018 (inicio 04/11)", 20181104L, Instant.parse("2018-11-01T15:00:00Z")),
            new Era("verao-2019 (fim 17/02)", 20190217L, Instant.parse("2019-02-10T15:00:00Z")),
            new Era("bissexto-2028", 20280229L, Instant.parse("2028-02-18T15:00:00Z")),
            new Era("virada-de-ano", 20261231L, Instant.parse("2026-12-10T15:00:00Z")),
            new Era("normal-2026", 20261010L, Instant.parse("2026-10-10T15:00:00Z")));

    private Actor a;
    private Random rnd;
    private String acc1;
    private String acc2;
    private String inv;
    private String lazer;
    private String saude;
    private String freela;
    private List<String> people;
    private final Map<String, Integer> statuses = new TreeMap<>();
    private final List<String> violations = new ArrayList<>();
    private final Map<UUID, String> categoryNames = new HashMap<>();
    private final Set<String> forbidden500 = new TreeSet<>();

    private LocalDate today() {
        return clock.instant().atZone(SP).toLocalDate();
    }

    @Test
    @DisplayName("5 épocas x semente fixa: série D7/D30/M3/M12, consumo por categoria e variações batem com o SQL, o dashboard, entries e o resumo")
    void propriedade() throws Exception {
        StringBuilder summary = new StringBuilder();
        for (Era era : ERAS) {
            statuses.clear();
            clock.set(era.start());
            a = world.new Actor("f0prop" + era.seed());
            rnd = new Random(era.seed());
            LocalDate startDay = era.start().atZone(SP).toLocalDate();
            String opening = startDay.minusDays(400).toString();
            acc1 = GoalQa.account(a, "Corrente", "CHECKING", "1000.00", opening);
            acc2 = GoalQa.account(a, "Poupança", "SAVINGS", "500.00", opening);
            inv = GoalQa.account(a, "CDB", "INVESTMENT", "0.00", opening);
            lazer = a.category("Lazer", CategoryKind.EXPENSE);
            saude = a.category("Saúde", CategoryKind.EXPENSE);
            freela = a.category("Freela", CategoryKind.INCOME);
            categoryNames.put(UUID.fromString(a.food), "Alimentação");
            categoryNames.put(UUID.fromString(a.tech), "Tecnologia");
            categoryNames.put(UUID.fromString(lazer), "Lazer");
            categoryNames.put(UUID.fromString(saude), "Saúde");
            people = new ArrayList<>();
            for (String n : List.of("João", "Maria", "Ana")) {
                people.add(DebtQa.person(a, n));
            }
            a.at(era.start());
            int checks = 0;
            for (int op = 0; op < OPS; op++) {
                String label = era.name() + " op " + op + " (" + today() + ")";
                try {
                    step(label);
                    if (op % 12 == 11 || op == OPS - 1) {
                        verify(label);
                        checks++;
                    }
                } catch (AssertionError | RuntimeException e) {
                    violations.add(label + ": exceção " + e);
                }
            }
            summary.append(era.name()).append(" verificações=").append(checks).append(' ').append(statuses).append('\n');
            typesContributing(era.name());
        }
        System.out.println("QA-F0-PROP violations=" + violations.size() + "\n" + summary);
        violations.addAll(world.contractViolations);
        assertThat(violations).as("violações").isEmpty();
    }

    private String dateBack(int max) {
        return today().minusDays(rnd.nextInt(max)).toString();
    }

    private String money(int maxCents) {
        return CardQaWorld.money(1 + rnd.nextInt(maxCents));
    }

    private String pickPerson() {
        return people.get(rnd.nextInt(people.size()));
    }

    private String anyExpenseCategory() {
        String[] cats = {a.food, a.tech, lazer, saude};
        return cats[rnd.nextInt(cats.length)];
    }

    private void record(String what, Res r) {
        statuses.merge(what + ":" + r.status(), 1, Integer::sum);
        if (r.status() >= 500) {
            forbidden500.add(what);
            violations.add(what + " respondeu " + r);
        }
    }

    private void passTime() {
        ZonedDateTime now = clock.instant().atZone(SP);
        ZonedDateTime next;
        switch (rnd.nextInt(6)) {
            case 0 -> next = now.toLocalDate().plusDays(rnd.nextInt(2)).atTime(23, 59, 59).atZone(SP);
            case 1 -> next = now.toLocalDate().plusDays(1 + rnd.nextInt(2)).atStartOfDay(SP);
            case 2 -> next = now.plusHours(1 + rnd.nextInt(30));
            case 3 -> next = now.plusDays(1 + rnd.nextInt(4)).with(LocalTime.of(rnd.nextInt(24), rnd.nextInt(60), rnd.nextInt(60)));
            case 4 -> next = now.plusMinutes(1 + rnd.nextInt(200));
            default -> next = now.plusDays(1 + rnd.nextInt(9)).with(LocalTime.NOON);
        }
        if (!next.toInstant().isAfter(clock.instant())) {
            next = now.plusHours(1);
        }
        a.at(next.toInstant());
    }

    private void step(String label) throws Exception {
        int roll = rnd.nextInt(100);
        Res r;
        String what;
        if (roll < 14) {
            r = a.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(rnd.nextInt(4) == 0 ? acc2 : acc1, anyExpenseCategory(), money(150_000),
                    rnd.nextInt(8) == 0 ? today().plusDays(1 + rnd.nextInt(25)).toString() : dateBack(45), "Despesa"), DebtQa.key());
            what = "despesa";
        } else if (roll < 22) {
            r = a.call(HttpMethod.POST, "/v1/transactions", QaWorld.income(acc1, rnd.nextBoolean() ? freela : a.income, money(500_000),
                    rnd.nextInt(8) == 0 ? today().plusDays(1 + rnd.nextInt(20)).toString() : dateBack(45)), DebtQa.key());
            what = "receita";
        } else if (roll < 34) {
            r = a.purchaseOn(a.card, anyExpenseCategory(), money(300_000), new int[] {1, 1, 2, 3, 6, 12}[rnd.nextInt(6)], dateBack(70), "Compra");
            what = "compra no cartão";
        } else if (roll < 39) {
            List<String> ids = jdbc.queryForList("select id::text from transactions where user_id = ? and type = 'CARD_PURCHASE' and status = 'ACTIVE'",
                    String.class, a.id);
            if (ids.isEmpty()) {
                return;
            }
            r = a.cardRefund(money(5_000), today().toString(), rnd.nextBoolean() ? ids.get(rnd.nextInt(ids.size())) : null);
            what = "reembolso no cartão";
        } else if (roll < 44) {
            List<Map<String, Object>> expenses = jdbc.queryForList("select id::text as id, category_id::text as cat, amount_cents as c from transactions "
                    + "where user_id = ? and type = 'EXPENSE' and status = 'ACTIVE' and account_id is not null", a.id);
            if (expenses.isEmpty()) {
                return;
            }
            Map<String, Object> e = expenses.get(rnd.nextInt(expenses.size()));
            long bound = ((Number) e.get("c")).longValue();
            r = DebtQa.accountRefund(a, CardQaWorld.money(1 + rnd.nextInt((int) Math.max(1, Math.min(bound, 100_000)))), today().toString(),
                    (String) e.get("cat"), (String) e.get("id"));
            what = "reembolso em conta";
        } else if (roll < 48) {
            r = a.call(HttpMethod.POST, "/v1/transactions", QaWorld.transfer(acc1, acc2, money(50_000), dateBack(30)), DebtQa.key());
            what = "transferência";
        } else if (roll < 52) {
            r = a.call(HttpMethod.POST, "/v1/transactions", QaWorld.investment(acc1, inv, rnd.nextInt(4) == 0 ? "REDEMPTION" : "CONTRIBUTION",
                    money(80_000), dateBack(30)), DebtQa.key());
            what = "aplicação/resgate";
        } else if (roll < 54) {
            r = a.call(HttpMethod.POST, "/v1/transactions", QaWorld.adjustment(acc1, rnd.nextBoolean() ? "INCREASE" : "DECREASE", money(10_000), dateBack(10)),
                    DebtQa.key());
            what = "ajuste";
        } else if (roll < 58) {
            r = DebtQa.createTx(a, DebtQa.loanBody(a, money(50_000), dateBack(20), pickPerson(), null).replace(a.nubank, acc1));
            what = "empréstimo";
        } else if (roll < 61) {
            List<JsonNode> open = openDebts("RECEIVABLE");
            if (open.isEmpty()) {
                return;
            }
            JsonNode d = open.get(rnd.nextInt(open.size()));
            long out = CardQaWorld.cents(d.get("outstanding"));
            r = DebtQa.createTx(a, DebtQa.receivedBody(a, CardQaWorld.money(Math.max(1, 1 + rnd.nextInt((int) Math.max(1, out)))), today().toString(),
                    d.get("id").asString()).replace(a.nubank, acc1));
            what = "devolução";
        } else if (roll < 63) {
            r = DebtQa.plainDebt(a, pickPerson(), rnd.nextBoolean() ? "PAYABLE" : "RECEIVABLE", money(40_000), dateBack(20), null,
                    rnd.nextBoolean() ? a.food : null);
            what = "dívida sem movimentação";
        } else if (roll < 68) {
            int n = 1 + rnd.nextInt(3);
            long total = 1000 + rnd.nextInt(100_000);
            List<String> shares = new ArrayList<>();
            long used = 0;
            for (int i = 0; i < n; i++) {
                long share = Math.max(1, (total - 1 - used) / (n - i) - rnd.nextInt(3));
                share = Math.min(share, total - 1 - used - (n - i - 1));
                if (share < 1) {
                    share = 1;
                }
                used += share;
                shares.add(DebtQa.shareJson(CardQaWorld.money(share), pickPerson()));
            }
            r = DebtQa.createTx(a, DebtQa.sharedExpenseBody(a, CardQaWorld.money(total), dateBack(20), shares).replace(a.nubank, acc1));
            what = "despesa dividida";
        } else if (roll < 73) {
            List<String> all = jdbc.queryForList("select id::text from transactions where user_id = ? and status = 'ACTIVE'", String.class, a.id);
            if (all.isEmpty()) {
                return;
            }
            r = a.voidTx(all.get(rnd.nextInt(all.size())));
            what = "estorno";
        } else if (roll < 75) {
            List<String> all = jdbc.queryForList("select id::text from transactions where user_id = ? and type = 'CARD_PURCHASE' and status = 'ACTIVE'",
                    String.class, a.id);
            if (all.isEmpty()) {
                return;
            }
            r = a.cancel(all.get(rnd.nextInt(all.size())));
            what = "cancelar parcelas";
        } else if (roll < 80) {
            List<JsonNode> invoices = a.invoices();
            if (invoices.isEmpty()) {
                return;
            }
            JsonNode inv0 = invoices.get(rnd.nextInt(invoices.size()));
            long out = CardQaWorld.cents(inv0.get("outstanding"));
            if (out <= 0) {
                return;
            }
            r = a.call(HttpMethod.POST, "/v1/invoices/" + inv0.get("id").asString() + "/payments", "{\"accountId\":\"" + acc1 + "\",\"amount\":\""
                    + CardQaWorld.money(1 + rnd.nextInt((int) out)) + "\",\"date\":\"" + today() + "\"}", DebtQa.key());
            what = "pagamento de fatura";
        } else if (roll < 82) {
            r = a.changeDays(a.card, 1 + rnd.nextInt(31), 1 + rnd.nextInt(31));
            what = "mudança de dias";
        } else if (roll < 86) {
            String type = new String[] {"EXPENSE", "INCOME", "CARD_PURCHASE"}[rnd.nextInt(3)];
            String freq = new String[] {"DAILY", "WEEKLY", "MONTHLY", "YEARLY"}[rnd.nextInt(4)];
            String target = "CARD_PURCHASE".equals(type) ? "\"cardId\":\"" + a.card + "\""
                    : "\"accountId\":\"" + acc1 + "\",\"method\":\"" + ("INCOME".equals(type) ? "TRANSFER" : "PIX") + "\"";
            String category = "INCOME".equals(type) ? a.income : "CARD_PURCHASE".equals(type) ? a.tech : a.food;
            r = a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody(type, money(30_000), "Rec", category, target, freq,
                    today().minusDays(rnd.nextInt(25)).toString(), "DAILY".equals(freq) ? "\"maxOccurrences\":" + (2 + rnd.nextInt(6)) : null), null);
            what = "recorrência";
        } else if (roll < 90) {
            List<JsonNode> pend = DebtQa.pending(a, today().plusDays(40));
            if (pend.isEmpty()) {
                return;
            }
            JsonNode o = pend.get(rnd.nextInt(pend.size()));
            r = rnd.nextInt(3) == 0 ? DebtQa.skip(a, o.get("recurrenceId").asString(), o.get("date").asString())
                    : DebtQa.realize(a, o.get("recurrenceId").asString(), o.get("date").asString(), null);
            what = "ocorrência";
        } else if (roll < 92) {
            String goal = GoalQa.goal(a, "Meta " + rnd.nextInt(1000), "5000.00", null);
            r = GoalQa.reserve(a, goal, acc1, money(50_000), today());
            what = "meta e reserva";
        } else {
            passTime();
            return;
        }
        record(what, r);
    }

    private List<JsonNode> openDebts(String direction) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode d : DebtQa.debts(a)) {
            String status = d.get("status").asString();
            if (direction.equals(d.get("direction").asString()) && ("OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status))) {
                out.add(d);
            }
        }
        return out;
    }

    private long book(String book, LocalDate from, LocalDate to) {
        Long v = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = ? "
                + "and effective_date between ? and ?", Long.class, a.id, book, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        return v == null ? 0 : v;
    }

    private long incomeSql(LocalDate from, LocalDate to) {
        return -book("INCOME", from, to);
    }

    private long expensesSql(LocalDate from, LocalDate to) {
        return book("EXPENSE", from, to);
    }

    private long inAccountsSql(LocalDate asOf) {
        Long v = jdbc.queryForObject("select coalesce(sum(p.amount_cents),0) from ledger_postings p join accounts ac on ac.id = p.book_id "
                + "where p.user_id = ? and p.active and p.book_type = 'ACCOUNT' and ac.type <> 'INVESTMENT' and p.effective_date <= ?", Long.class, a.id,
                java.sql.Date.valueOf(asOf));
        return v == null ? 0 : v;
    }

    private static String ratio(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(Math.abs(denominator)), 4, RoundingMode.HALF_UP).toPlainString();
    }

    private void verify(String label) throws Exception {
        LocalDate today = today();
        long postingsBefore = fingerprint();
        Long wrongCompetence = jdbc.queryForObject("select count(*) from ledger_postings where user_id = ? and active and book_type in ('INCOME','EXPENSE') "
                + "and competence <> to_char(effective_date, 'YYYY-MM')", Long.class, a.id);
        if (wrongCompetence != null && wrongCompetence > 0) {
            violations.add(label + ": " + wrongCompetence + " postings com competência diferente do mês da data efetiva");
        }
        for (String period : List.of("D7", "D30", "M3", "M12")) {
            checkSeries(label, period, today);
        }
        checkMonthsAgainstReads(label, today);
        for (YearMonth ym : monthsToCheck(today)) {
            checkSpending(label, ym);
        }
        checkChanges(label, today);
        long postingsAfter = fingerprint();
        if (postingsBefore != postingsAfter) {
            violations.add(label + ": a leitura alterou o razão (" + postingsBefore + " -> " + postingsAfter + ")");
        }
        Long unbalanced = a.unbalancedGroups();
        if (unbalanced != 0) {
            violations.add(label + ": " + unbalanced + " grupos do razão não fecham em zero");
        }
    }

    private long fingerprint() {
        Map<String, Object> row = jdbc.queryForMap("select count(*) as n, coalesce(sum(amount_cents),0) as s, count(*) filter (where active) as act, "
                + "coalesce(sum(abs(amount_cents)) filter (where active),0) as abs from ledger_postings where user_id = ?", a.id);
        return ((Number) row.get("n")).longValue() * 1_000_003L + ((Number) row.get("s")).longValue() * 31 + ((Number) row.get("act")).longValue() * 7
                + ((Number) row.get("abs")).longValue();
    }

    private List<YearMonth> monthsToCheck(LocalDate today) {
        TreeSet<YearMonth> months = new TreeSet<>();
        months.add(YearMonth.from(today));
        months.add(YearMonth.from(today).minusMonths(1));
        months.add(YearMonth.from(today).minusMonths(2));
        List<String> withData = jdbc.queryForList("select distinct competence from ledger_postings where user_id = ? and active and book_type in ('EXPENSE','INCOME') "
                + "and effective_date <= ?", String.class, a.id, java.sql.Date.valueOf(YearMonth.from(today).atEndOfMonth()));
        for (String c : withData) {
            YearMonth ym = YearMonth.parse(c);
            if (!ym.isBefore(YearMonth.from(today).minusMonths(11))) {
                months.add(ym);
            }
        }
        return new ArrayList<>(months);
    }

    private static LocalDate expectedFrom(String period, LocalDate today) {
        return switch (period) {
            case "D7" -> today.minusDays(6);
            case "D30" -> today.minusDays(29);
            case "M3" -> today.minusMonths(3);
            default -> today.minusMonths(11).withDayOfMonth(1);
        };
    }

    private void checkSeries(String label, String period, LocalDate today) throws Exception {
        JsonNode s = a.getOk("/v1/reports/income-expenses?period=" + period);
        String ctx = label + " " + period;
        LocalDate from = expectedFrom(period, today);
        LocalDate to = period.equals("M12") ? YearMonth.from(today).atEndOfMonth() : today;
        if (!s.get("from").asString().equals(from.toString()) || !s.get("to").asString().equals(to.toString())) {
            violations.add(ctx + ": janela " + s.get("from").asString() + ".." + s.get("to").asString() + " esperada " + from + ".." + to);
            return;
        }
        List<LocalDate[]> buckets = new ArrayList<>();
        switch (period) {
            case "D7", "D30" -> {
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    buckets.add(new LocalDate[] {d, d});
                }
            }
            case "M3" -> {
                LocalDate start = from;
                while (!start.isAfter(to)) {
                    LocalDate sunday = start.plusDays(7 - start.getDayOfWeek().getValue());
                    LocalDate end = sunday.isAfter(to) ? to : sunday;
                    buckets.add(new LocalDate[] {start, end});
                    start = end.plusDays(1);
                }
            }
            default -> {
                for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
                    buckets.add(new LocalDate[] {m.atDay(1), m.atEndOfMonth()});
                }
            }
        }
        JsonNode points = s.get("points");
        if (points.size() != buckets.size()) {
            violations.add(ctx + ": " + points.size() + " pontos, esperados " + buckets.size());
            return;
        }
        String basis = period.equals("M12") ? "COMPETENCE" : "DATE";
        String granularity = period.equals("M12") ? "MONTH" : period.equals("M3") ? "WEEK" : "DAY";
        if (!s.get("basis").asString().equals(basis) || !s.get("granularity").asString().equals(granularity)) {
            violations.add(ctx + ": basis/granularity " + s.get("basis").asString() + "/" + s.get("granularity").asString());
        }
        if (period.equals("M3") && (points.size() < 13 || points.size() > 15)) {
            violations.add(ctx + ": M3 com " + points.size() + " pontos (esperado 13 a 15)");
        }
        long totalIncome = 0;
        long totalExpenses = 0;
        for (int i = 0; i < buckets.size(); i++) {
            LocalDate[] b = buckets.get(i);
            JsonNode p = points.get(i);
            if (!p.get("periodStart").asString().equals(b[0].toString()) || !p.get("periodEnd").asString().equals(b[1].toString())) {
                violations.add(ctx + ": balde " + i + " " + p.get("periodStart").asString() + ".." + p.get("periodEnd").asString() + " esperado " + b[0] + ".." + b[1]);
                continue;
            }
            long income = incomeSql(b[0], b[1]);
            long expenses = expensesSql(b[0], b[1]);
            totalIncome += income;
            totalExpenses += expenses;
            if (CardQaWorld.cents(p.get("income")) != income || CardQaWorld.cents(p.get("expenses")) != expenses
                    || CardQaWorld.cents(p.get("net")) != income - expenses) {
                violations.add(ctx + " [" + b[0] + ".." + b[1] + "]: ponto income/expenses/net = " + CardQaWorld.cents(p.get("income")) + "/"
                        + CardQaWorld.cents(p.get("expenses")) + "/" + CardQaWorld.cents(p.get("net")) + " oráculo SQL " + income + "/" + expenses + "/" + (income - expenses));
            }
            boolean partial = !today.isBefore(b[0]) && !today.isAfter(b[1]);
            if (p.get("partial").asBoolean() != partial) {
                violations.add(ctx + " [" + b[0] + "]: partial=" + p.get("partial").asBoolean() + " esperado " + partial);
            }
        }
        JsonNode totals = s.get("totals");
        if (CardQaWorld.cents(totals.get("income")) != totalIncome || CardQaWorld.cents(totals.get("expenses")) != totalExpenses
                || CardQaWorld.cents(totals.get("net")) != totalIncome - totalExpenses) {
            violations.add(ctx + ": totals " + totals + " oráculo " + totalIncome + "/" + totalExpenses);
        }
    }

    private void checkMonthsAgainstReads(String label, LocalDate today) throws Exception {
        JsonNode m12 = a.getOk("/v1/reports/income-expenses?period=M12");
        for (JsonNode p : m12.get("points")) {
            String month = p.get("periodStart").asString().substring(0, 7);
            YearMonth ym = YearMonth.parse(month);
            LocalDate asOf = ym.equals(YearMonth.from(today)) ? today : ym.atEndOfMonth();
            JsonNode dash = a.getOk("/v1/dashboard/summary?asOf=" + asOf).get("month");
            JsonNode entriesC = a.getOk("/v1/entries?competence=" + month + "&lens=CONSUMPTION").get("totals");
            JsonNode entriesI = a.getOk("/v1/entries?competence=" + month + "&lens=INCOME").get("totals");
            JsonNode sum = a.getOk("/v1/summaries/" + month);
            long expenses = CardQaWorld.cents(p.get("expenses"));
            long income = CardQaWorld.cents(p.get("income"));
            String ctx = label + " " + month;
            if (expenses != CardQaWorld.cents(dash.get("consumption"))) {
                violations.add(ctx + ": série expenses " + expenses + " != dashboard.month.consumption " + CardQaWorld.cents(dash.get("consumption")));
            }
            if (income != CardQaWorld.cents(dash.get("income"))) {
                violations.add(ctx + ": série income " + income + " != dashboard.month.income " + CardQaWorld.cents(dash.get("income")));
            }
            if (expenses != CardQaWorld.cents(entriesC.get("total"))) {
                violations.add(ctx + ": série expenses != entries(CONSUMPTION).total " + CardQaWorld.cents(entriesC.get("total")));
            }
            if (income != CardQaWorld.cents(entriesI.get("total"))) {
                violations.add(ctx + ": série income != entries(INCOME).total " + CardQaWorld.cents(entriesI.get("total")));
            }
            if (expenses != CardQaWorld.cents(sum.get("expenses")) || income != CardQaWorld.cents(sum.get("income"))) {
                violations.add(ctx + ": série != resumo mensal " + sum.get("income") + "/" + sum.get("expenses"));
            }
            long net = income - expenses;
            String expectedRate = income == 0 ? null : ratio(net, income);
            JsonNode rate = sum.get("savingsRate");
            String actualRate = rate == null || rate.isNull() ? null : rate.asString();
            if (income < 0) {
                expectedRate = actualRate;
            }
            if (expectedRate == null ? actualRate != null : !expectedRate.equals(actualRate)) {
                violations.add(ctx + ": savingsRate " + actualRate + " esperado " + expectedRate);
            }
            YearMonth prev = ym.minusMonths(1);
            long pIncome = incomeSql(prev.atDay(1), prev.atEndOfMonth());
            long pExpenses = expensesSql(prev.atDay(1), prev.atEndOfMonth());
            String prevRate = pIncome == 0 ? null : ratio(pIncome - pExpenses, pIncome);
            JsonNode cmp = sum.get("comparison");
            JsonNode actualPrevRate = cmp.get("previousMonth").get("savingsRate");
            String ap = actualPrevRate == null || actualPrevRate.isNull() ? null : actualPrevRate.asString();
            if (pIncome >= 0 && (prevRate == null ? ap != null : !prevRate.equals(ap))) {
                violations.add(ctx + ": previousMonth.savingsRate " + ap + " esperado " + prevRate);
            }
            String expectedDelta = expectedRate == null || prevRate == null ? null
                    : new BigDecimal(expectedRate).subtract(new BigDecimal(prevRate)).setScale(4, RoundingMode.HALF_UP).toPlainString();
            JsonNode delta = cmp.get("savingsRateDelta");
            String ad = delta == null || delta.isNull() ? null : delta.asString();
            if (income >= 0 && pIncome >= 0 && (expectedDelta == null ? ad != null : !expectedDelta.equals(ad))) {
                violations.add(ctx + ": savingsRateDelta " + ad + " esperado " + expectedDelta);
            }
        }
    }

    private void checkSpending(String label, YearMonth ym) throws Exception {
        String month = ym.toString();
        String ctx = label + " spending " + month;
        Map<UUID, Long> byCategory = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("select book_id, sum(amount_cents) as c from ledger_postings where user_id = ? and active "
                + "and book_type = 'EXPENSE' and competence = ? group by book_id", a.id, month)) {
            byCategory.put((UUID) row.get("book_id"), ((Number) row.get("c")).longValue());
        }
        long total = byCategory.values().stream().mapToLong(Long::longValue).sum();
        long base = byCategory.values().stream().filter(v -> v > 0).mapToLong(Long::longValue).sum();
        JsonNode entries = a.getOk("/v1/entries?competence=" + month + "&lens=CONSUMPTION").get("totals");
        if (CardQaWorld.cents(entries.get("total")) != total) {
            violations.add(ctx + ": entries.totals.total != SQL " + total);
        }
        Map<UUID, Long> entriesBy = new HashMap<>();
        for (JsonNode c : entries.get("byCategory")) {
            entriesBy.put(UUID.fromString(c.get("category").get("id").asString()), CardQaWorld.cents(c.get("amount")));
        }
        Map<UUID, Long> nonZero = new HashMap<>();
        byCategory.forEach((k, v) -> {
            if (v != 0) {
                nonZero.put(k, v);
            }
        });
        Map<UUID, Long> entriesNonZero = new HashMap<>();
        entriesBy.forEach((k, v) -> {
            if (v != 0) {
                entriesNonZero.put(k, v);
            }
        });
        if (!nonZero.equals(entriesNonZero)) {
            violations.add(ctx + ": entries.byCategory " + entriesNonZero + " != SQL " + nonZero);
        }
        for (int limit : new int[] {1, 2, 5, 10}) {
            JsonNode r = a.getOk("/v1/reports/spending-by-category?month=" + month + "&limit=" + limit);
            if (CardQaWorld.cents(r.get("total")) != total) {
                violations.add(ctx + " limit " + limit + ": total " + CardQaWorld.cents(r.get("total")) + " != " + total);
            }
            if (CardQaWorld.cents(r.get("base")) != base) {
                violations.add(ctx + " limit " + limit + ": base " + CardQaWorld.cents(r.get("base")) + " != " + base);
            }
            List<Map.Entry<UUID, Long>> positives = new ArrayList<>();
            for (Map.Entry<UUID, Long> e : byCategory.entrySet()) {
                if (e.getValue() > 0) {
                    positives.add(e);
                }
            }
            positives.sort(Comparator.<Map.Entry<UUID, Long>, Long>comparing(Map.Entry::getValue).reversed()
                    .thenComparing(e -> categoryNames.getOrDefault(e.getKey(), "").toLowerCase(java.util.Locale.ROOT)).thenComparing(Map.Entry::getKey));
            JsonNode items = r.get("items");
            int expectedItems = Math.min(limit, positives.size());
            if (items.size() != expectedItems) {
                violations.add(ctx + " limit " + limit + ": " + items.size() + " itens, esperados " + expectedItems);
                continue;
            }
            double shares = 0;
            for (int i = 0; i < expectedItems; i++) {
                Map.Entry<UUID, Long> e = positives.get(i);
                JsonNode item = items.get(i);
                if (!item.get("category").get("id").asString().equals(e.getKey().toString()) || CardQaWorld.cents(item.get("amount")) != e.getValue()) {
                    violations.add(ctx + " limit " + limit + ": item " + i + " " + item + " esperado " + e.getKey() + "=" + e.getValue());
                }
                String share = ratio(e.getValue(), base);
                if (!share.equals(item.get("share").asString())) {
                    violations.add(ctx + " limit " + limit + ": share " + item.get("share").asString() + " esperado " + share);
                }
                shares += Double.parseDouble(item.get("share").asString());
            }
            JsonNode other = r.get("other");
            List<Map.Entry<UUID, Long>> rest = positives.subList(expectedItems, positives.size());
            if (rest.isEmpty()) {
                if (!other.isNull()) {
                    violations.add(ctx + " limit " + limit + ": other deveria ser null: " + other);
                }
            } else {
                long restSum = rest.stream().mapToLong(Map.Entry::getValue).sum();
                if (other.isNull() || CardQaWorld.cents(other.get("amount")) != restSum || other.get("categories").asInt() != rest.size()
                        || !ratio(restSum, base).equals(other.get("share").asString())) {
                    violations.add(ctx + " limit " + limit + ": other " + other + " esperado " + restSum + " x" + rest.size());
                }
                shares += Double.parseDouble(other.get("share").asString());
            }
            if (base > 0 && Math.abs(shares - 1.0) > (expectedItems + 1) * 0.00005 + 1e-9) {
                violations.add(ctx + " limit " + limit + ": Σ share " + shares);
            }
            if (base <= 0) {
                for (JsonNode item : items) {
                    if (!item.get("share").isNull()) {
                        violations.add(ctx + ": share deveria ser null com base <= 0");
                    }
                }
            }
        }
    }

    private void checkChanges(String label, LocalDate today) throws Exception {
        JsonNode dash = a.getOk("/v1/dashboard/summary").get("balances");
        LocalDate since = YearMonth.from(today).atDay(1).minusDays(1);
        long previous = inAccountsSql(since);
        long now = inAccountsSql(today);
        String ctx = label + " inAccountsChange";
        if (CardQaWorld.cents(dash.get("inAccounts")) != now) {
            violations.add(ctx + ": inAccounts " + CardQaWorld.cents(dash.get("inAccounts")) + " != SQL " + now);
        }
        JsonNode change = dash.get("inAccountsChange");
        if (change == null || change.isNull()) {
            violations.add(ctx + ": ausente com contas e asOf = hoje");
            return;
        }
        if (!change.get("since").asString().equals(since.toString())) {
            violations.add(ctx + ": since " + change.get("since").asString() + " esperado " + since);
        }
        if (CardQaWorld.cents(change.get("previous")) != previous || CardQaWorld.cents(change.get("amount")) != now - previous) {
            violations.add(ctx + ": previous/amount " + change + " esperado " + previous + "/" + (now - previous));
        }
        String percent = previous == 0 ? null : ratio(now - previous, previous);
        JsonNode pn = change.get("percent");
        String actual = pn == null || pn.isNull() ? null : pn.asString();
        if (percent == null ? actual != null : !percent.equals(actual)) {
            violations.add(ctx + ": percent " + actual + " esperado " + percent);
        }
        JsonNode atSince = a.getOk("/v1/dashboard/summary?asOf=" + since).get("balances");
        if (CardQaWorld.cents(atSince.get("inAccounts")) != previous) {
            violations.add(ctx + ": dashboard(asOf=since).inAccounts " + CardQaWorld.cents(atSince.get("inAccounts")) + " != previous " + previous);
        }
        JsonNode future = a.getOk("/v1/dashboard/summary?asOf=" + today.plusDays(3)).get("balances");
        if (future.has("inAccountsChange")) {
            violations.add(ctx + ": presente em projeção (asOf futuro): " + future.get("inAccountsChange"));
        }
    }

    private void typesContributing(String era) {
        List<Map<String, Object>> rows = jdbc.queryForList("select p.book_type, p.source_type, t.type as ttype, count(*) as n from ledger_postings p "
                + "left join transactions t on t.id = p.transaction_id where p.user_id = ? and p.active and p.book_type in ('INCOME','EXPENSE') "
                + "group by 1, 2, 3 order by 1, 2, 3", a.id);
        Set<String> allowed = Set.of("EXPENSE", "INCOME", "CARD_PURCHASE", "REFUND");
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            String type = (String) row.get("ttype");
            sb.append(row.get("book_type")).append('/').append(row.get("source_type")).append('/').append(type).append('=').append(row.get("n")).append(' ');
            boolean ok = type == null ? "DEBT_RECOGNITION".equals(row.get("source_type")) || "DEBT_CANCELLATION".equals(row.get("source_type")) : allowed.contains(type);
            if (!ok) {
                violations.add(era + ": tipo inesperado nos livros da série: " + row);
            }
        }
        System.out.println("QA-F0-PROP " + era + " livros INCOME/EXPENSE por origem: " + sb);
    }
}
