package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;
import com.moneylog.util.SqlFixtures;

/**
 * T190 (QA independente): bordas dos relatórios e das variações. Mês e período inválidos, limite, valor máximo (teto S-78), categoria e conta
 * arquivadas, mês sem movimento, usuário sem contas, relógio em fronteiras de dia (inclusive a transição do horário de verão de 2018/2019) e
 * campos opcionais ausentes ou nulos conforme o contrato.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T190: bordas dos relatórios (valor máximo, arquivados, fusos, parâmetros)")
class RedesignFase0BordersQaIT {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

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
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
    }

    private JsonNode create(Actor a, String body) throws Exception {
        return CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", body, DebtQa.key())).node();
    }

    private Res get(Actor a, String url) throws Exception {
        return a.call(HttpMethod.GET, url, null, null);
    }

    private Res rawGet(String bearer, String url) throws Exception {
        return world.res(mvc.perform(MockMvcRequestBuilders.get(URI.create(url)).header(HttpHeaders.AUTHORIZATION, bearer)).andReturn());
    }

    @Test
    @DisplayName("month inválido, futuro e antigo; limit 1 e 10 e inválidos; period inválido: nunca 5xx, 400 com o code do contrato, sem eco do valor")
    void parametros() throws Exception {
        Actor a = world.new Actor("bord1");
        create(a, QaWorld.expense(a.nubank, a.food, "10.00", "2026-10-09", "Mercado"));
        for (String month : new String[] {"2026-13", "2026-00", "2026-1", "202610", "2026-10-01", "", " ", "abc", "+2026-10", "-2026-10", "2026-10%00", "２０２６-１０", "2026/10",
                "2026-10 ", "99999-10", "2026-100"}) {
            Res r = rawGet(a.bearer, "/v1/reports/spending-by-category?month=" + java.net.URLEncoder.encode(month, java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("QA-F0-BORDER month='" + month + "' -> " + r.status() + " " + r.code());
            assertThat(r.status()).as("month=" + month + " " + r).isEqualTo(400);
            assertThat(r.code()).isEqualTo("REPORT_MONTH_INVALID");
        }
        for (String month : new String[] {"0001-01", "0000-01", "1900-01", "2026-10", "2026-11", "2099-12", "9999-12"}) {
            Res r = rawGet(a.bearer, "/v1/reports/spending-by-category?month=" + month);
            System.out.println("QA-F0-BORDER month=" + month + " -> " + r.status() + " " + r.code());
            assertThat(r.status()).as("month=" + month + " " + r).isLessThan(500);
            if (r.status() == 200) {
                assertThat(CardQaWorld.cents(r.node().get("total"))).isEqualTo(month.equals("2026-10") ? 1000 : 0);
            }
        }
        for (String limit : new String[] {"0", "11", "-1", "1.5", "1e1", "abc", "2147483648"}) {
            Res r = rawGet(a.bearer, "/v1/reports/spending-by-category?limit=" + java.net.URLEncoder.encode(limit, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(r.status()).as("limit='" + limit + "' " + r).isEqualTo(400);
        }
        assertThat(get(a, "/v1/reports/spending-by-category?limit=1").status()).isEqualTo(200);
        assertThat(get(a, "/v1/reports/spending-by-category?limit=10").status()).isEqualTo(200);
        assertThat(rawGet(a.bearer, "/v1/reports/spending-by-category?limit=010").status()).as("010 é lido como 10").isEqualTo(200);
        for (String period : new String[] {"", "d7", "D7 ", " D7", "D1", "D8", "M1", "M6", "Y1", "7", "D7,D30", "D7%00", "ＤＳ", "null", "undefined", "D7;D30"}) {
            Res r = rawGet(a.bearer, "/v1/reports/income-expenses?period=" + java.net.URLEncoder.encode(period, java.nio.charset.StandardCharsets.UTF_8));
            assertThat(r.status()).as("period='" + period + "' " + r).isEqualTo(400);
            assertThat(r.code()).isEqualTo("REPORT_PERIOD_INVALID");
        }
        Res dup = rawGet(a.bearer, "/v1/reports/income-expenses?period=D7&period=D30");
        System.out.println("QA-F0-BORDER period duplicado -> " + dup.status() + " " + dup.code());
        assertThat(dup.status()).isLessThan(500);
        assertThat(rawGet(a.bearer, "/v1/reports/income-expenses?period=D7&x=1&month=2026-10").status()).as("desconhecidos são ignorados").isEqualTo(200);
        for (HttpMethod m : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
            for (String path : new String[] {"/v1/reports/income-expenses?period=D7", "/v1/reports/spending-by-category"}) {
                assertThat(a.raw(m, path, "{}", null).status()).as(m + " " + path).isIn(405, 404, 403);
            }
        }
    }

    @Test
    @DisplayName("dashboard: asOf inválido, muito antigo/futuro e bissexto; since = último dia do mês anterior (29/02 em 01/03/2028); campo ausente em projeção e sem contas")
    void asOfEBordasDoDashboard() throws Exception {
        Actor a = world.new Actor("bord2");
        for (String asOf : new String[] {"2026-02-30", "2026-13-01", "abc", "", "2026-1-1", "0000-01-01", "10000-01-01", "2026-10-10T10:00", "+2026-10-10"}) {
            Res r = rawGet(a.bearer, "/v1/dashboard/summary?asOf=" + java.net.URLEncoder.encode(asOf, java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("QA-F0-BORDER asOf='" + asOf + "' -> " + r.status() + " " + r.code());
            assertThat(r.status()).as("asOf=" + asOf + " " + r).isLessThan(500);
        }
        for (String asOf : new String[] {"2000-02-29", "2024-03-01", "2028-03-01", "2026-10-01", "2026-10-31"}) {
            Res r = get(a, "/v1/dashboard/summary?asOf=" + asOf);
            assertThat(r.status()).as("asOf=" + asOf + " " + r).isEqualTo(200);
            JsonNode change = r.node().get("balances").get("inAccountsChange");
            boolean projection = r.node().get("isProjection").asBoolean();
            System.out.println("QA-F0-BORDER asOf=" + asOf + " isProjection=" + projection + " inAccountsChange=" + change);
            if (projection) {
                assertThat(r.node().get("balances").has("inAccountsChange")).as("ausente em projeção " + asOf).isFalse();
            } else {
                LocalDate expectedSince = YearMonth.parse(asOf.substring(0, 7)).atDay(1).minusDays(1);
                assertThat(change.get("since").asString()).isEqualTo(expectedSince.toString());
            }
        }
        // usuário sem nenhuma conta
        UUID id = UUID.randomUUID();
        new TransactionTemplate(tm).executeWithoutResult(s -> SqlFixtures.insertUser(jdbc, id, "bare-" + id + "@moneylog.com"));
        String bearer = auth.bearer(id);
        for (String path : new String[] {"/v1/dashboard/summary", "/v1/projections", "/v1/projections?horizon=D30&includeTrend=true", "/v1/summaries/2026-10",
                "/v1/reports/income-expenses?period=M12", "/v1/reports/income-expenses?period=D7", "/v1/reports/spending-by-category"}) {
            var r = world.res(mvc.perform(MockMvcRequestBuilders.get(path).header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(ContractValidation.conforms()).andReturn());
            assertThat(r.status()).as(path + " " + r).isEqualTo(200);
            if (path.equals("/v1/dashboard/summary")) {
                assertThat(r.node().get("balances").has("inAccountsChange")).as("sem contas: campo ausente").isFalse();
            }
            if (path.equals("/v1/reports/spending-by-category")) {
                assertThat(r.node().get("items")).isEmpty();
                assertThat(r.node().get("other").isNull()).isTrue();
                assertThat(CardQaWorld.cents(r.node().get("base"))).isZero();
            }
            if (path.startsWith("/v1/summaries")) {
                assertThat(r.node().get("savingsRate").isNull()).isTrue();
                assertThat(r.node().get("comparison").get("savingsRateDelta").isNull()).as("delta null presente (não ausente)").isTrue();
            }
        }
    }

    @Test
    @DisplayName("valor máximo (teto S-78): 3 despesas de 999.999.999,99 em dias e categorias diferentes: série, totais, share 1.0000, taxa de poupança extrema e percent extremo cabem no contrato")
    void valorMaximo() throws Exception {
        Actor a = world.new Actor("bord3");
        String max = "999999999.99";
        // conta com saldo grande para não esbarrar em saldo
        String big = GoalQa.account(a, "Grande", "CHECKING", "999999999.99", "2026-09-01");
        String lazer = a.category("Lazer", CategoryKind.EXPENSE);
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(big, a.food, max, "2026-10-10", "Max 1"), DebtQa.key()));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(big, lazer, max, "2026-10-09", "Max 2"), DebtQa.key()));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(big, a.food, max, "2026-09-30", "Max 3"), DebtQa.key()));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", QaWorld.income(big, a.income, "0.01", "2026-10-08"), DebtQa.key()));
        Res rejected = a.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(big, a.food, "1000000000.00", "2026-10-10", "Acima"), DebtQa.key());
        assertThat(rejected.status()).as("acima do teto: " + rejected).isBetween(400, 422);

        JsonNode d7 = a.getOk("/v1/reports/income-expenses?period=D7");
        assertThat(CardQaWorld.cents(d7.get("totals").get("expenses"))).isEqualTo(2 * 99_999_999_999L);
        assertThat(CardQaWorld.cents(d7.get("totals").get("net"))).isEqualTo(1 - 2 * 99_999_999_999L);
        JsonNode m12 = a.getOk("/v1/reports/income-expenses?period=M12");
        assertThat(CardQaWorld.cents(m12.get("totals").get("expenses"))).isEqualTo(3 * 99_999_999_999L);
        JsonNode spending = a.getOk("/v1/reports/spending-by-category?limit=1");
        assertThat(CardQaWorld.cents(spending.get("total"))).isEqualTo(2 * 99_999_999_999L);
        assertThat(spending.get("items").get(0).get("share").asString()).isEqualTo("0.5000");
        assertThat(spending.get("other").get("share").asString()).isEqualTo("0.5000");
        JsonNode summary = a.getOk("/v1/summaries/2026-10");
        System.out.println("QA-F0-BORDER taxa de poupança extrema: " + summary.get("savingsRate") + " delta=" + summary.get("comparison").get("savingsRateDelta"));
        assertThat(summary.get("savingsRate").asString()).matches("^-?\\d+\\.\\d{4}$");
        JsonNode balances = a.getOk("/v1/dashboard/summary").get("balances");
        System.out.println("QA-F0-BORDER inAccountsChange extremo: " + balances.get("inAccountsChange"));
        // previous pequeno e amount enorme: percent gigante cabe no padrão do Ratio
        Actor t = world.new Actor("bord3b");
        String acc = GoalQa.account(t, "Pequena", "CHECKING", "0.01", "2026-09-01");
        CardQaWorld.ok(t.call(HttpMethod.POST, "/v1/transactions", QaWorld.income(acc, t.income, max, "2026-10-05"), DebtQa.key()));
        JsonNode change = t.getOk("/v1/dashboard/summary").get("balances").get("inAccountsChange");
        System.out.println("QA-F0-BORDER percent gigante: " + change);
        assertThat(change.get("percent").asString()).matches("^-?\\d+\\.\\d{4}$");
        assertThat(world.contractViolations).as("violações do contrato").isEmpty();
    }

    @Test
    @DisplayName("categoria só com reembolso de mês seguinte: total negativo, base 0.00, itens vazios, other null e share não existe; D-série mostra expenses negativo no dia do reembolso")
    void baseZeroECategoriaNegativa() throws Exception {
        Actor a = world.new Actor("bord4");
        String orig = create(a, QaWorld.expense(a.nubank, a.food, "10.00", "2026-09-10", "Setembro")).get("id").asString();
        CardQaWorld.ok(DebtQa.accountRefund(a, "10.00", "2026-10-05", a.food, orig));
        JsonNode oct = a.getOk("/v1/reports/spending-by-category?month=2026-10");
        assertThat(CardQaWorld.cents(oct.get("total"))).isEqualTo(-1000);
        assertThat(CardQaWorld.cents(oct.get("base"))).isZero();
        assertThat(oct.get("items")).isEmpty();
        assertThat(oct.get("other").isNull()).isTrue();
        JsonNode d7 = a.getOk("/v1/reports/income-expenses?period=D7");
        boolean found = false;
        for (JsonNode p : d7.get("points")) {
            if (p.get("periodStart").asString().equals("2026-10-05")) {
                assertThat(CardQaWorld.cents(p.get("expenses"))).isEqualTo(-1000);
                assertThat(CardQaWorld.cents(p.get("net"))).isEqualTo(1000);
                found = true;
            }
        }
        assertThat(found).isTrue();
        JsonNode sep = a.getOk("/v1/reports/spending-by-category?month=2026-09");
        assertThat(sep.get("items").get(0).get("share").asString()).isEqualTo("1.0000");
    }

    @Test
    @DisplayName("conta e categoria arquivadas: o histórico continua nos relatórios e nos totais (arquivar não apaga consumo)")
    void arquivadas() throws Exception {
        Actor a = world.new Actor("bord5");
        String second = GoalQa.account(a, "Velha", "CHECKING", "100.00");
        String cat = a.category("Antiga", CategoryKind.EXPENSE);
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(second, cat, "30.00", "2026-10-09", "Antes de arquivar"), DebtQa.key()));
        long before = CardQaWorld.cents(a.getOk("/v1/reports/income-expenses?period=M12").get("totals").get("expenses"));
        long inAccountsBefore = CardQaWorld.cents(a.getOk("/v1/dashboard/summary").get("balances").get("inAccounts"));
        CardQaWorld.ok(a.call(HttpMethod.POST, "/v1/categories/" + cat + "/archive", null, null));
        Res archivedAccount = a.call(HttpMethod.POST, "/v1/accounts/" + second + "/archive", null, null);
        System.out.println("QA-F0-BORDER arquivar conta com saldo -> " + archivedAccount);
        assertThat(CardQaWorld.cents(a.getOk("/v1/reports/income-expenses?period=M12").get("totals").get("expenses"))).isEqualTo(before);
        JsonNode spending = a.getOk("/v1/reports/spending-by-category?month=2026-10");
        assertThat(spending.get("items").toString()).contains(cat);
        long inAccountsAfter = CardQaWorld.cents(a.getOk("/v1/dashboard/summary").get("balances").get("inAccounts"));
        System.out.println("QA-F0-BORDER inAccounts antes/depois de arquivar conta: " + inAccountsBefore + " / " + inAccountsAfter);
        JsonNode change = a.getOk("/v1/dashboard/summary").get("balances").get("inAccountsChange");
        long previous = CardQaWorld.cents(change.get("previous"));
        long amount = CardQaWorld.cents(change.get("amount"));
        assertThat(previous + amount).as("previous + amount = inAccounts").isEqualTo(inAccountsAfter);
    }

    @Test
    @DisplayName("fusos: 365 dias de 2027 e 2028 (bissexto) em ledger vazio: janelas D7/D30/M3/M12 corretas em todo dia, sem ponto duplicado")
    void todasAsJanelas() throws Exception {
        Actor a = world.new Actor("bord6");
        LocalDate day = LocalDate.of(2027, 12, 20);
        // um lançamento por dia de 2027-11-01 a 2028-04-30 (1 centavo no dia do mês), para cada janela ter conteúdo e o oráculo SQL conferir
        for (LocalDate d = LocalDate.of(2027, 11, 1); !d.isAfter(LocalDate.of(2028, 4, 30)); d = d.plusDays(1)) {
            jdbc.update("insert into transactions (id, user_id, type, status, amount_cents, currency, transaction_date, description, payment_method, account_id, "
                    + "category_id, source, created_at, updated_at, version) values (?, ?, 'EXPENSE', 'ACTIVE', ?, 'BRL', ?, 'x', 'PIX', ?, ?, 'MANUAL', now(), now(), 0)",
                    UUID.randomUUID(), a.id, 1L + d.getDayOfMonth(), java.sql.Date.valueOf(d), UUID.fromString(a.nubank), UUID.fromString(a.food));
        }
        // postings correspondentes
        jdbc.update("insert into ledger_postings (id, user_id, source_type, source_id, transaction_id, book_type, book_id, competence, effective_date, amount_cents, "
                + "currency, active, created_at, updated_at) select gen_random_uuid(), user_id, 'TRANSACTION', id, id, 'EXPENSE', category_id, to_char(transaction_date,'YYYY-MM'), "
                + "transaction_date, amount_cents, 'BRL', true, now(), now() from transactions where user_id = ?", a.id);
        jdbc.update("insert into ledger_postings (id, user_id, source_type, source_id, transaction_id, book_type, book_id, competence, effective_date, amount_cents, "
                + "currency, active, created_at, updated_at) select gen_random_uuid(), user_id, 'TRANSACTION', id, id, 'ACCOUNT', account_id, to_char(transaction_date,'YYYY-MM'), "
                + "transaction_date, -amount_cents, 'BRL', true, now(), now() from transactions where user_id = ?", a.id);
        List<String> problems = new ArrayList<>();
        int checks = 0;
        for (LocalDate today = LocalDate.of(2027, 12, 1); !today.isAfter(LocalDate.of(2028, 3, 31)); today = today.plusDays(1)) {
            clock.set(today.atTime(rnd(today), 30, 0).atZone(SP).toInstant());
            a.bearer = auth.bearer(a.id);
            for (String period : new String[] {"D7", "D30", "M3", "M12"}) {
                JsonNode s = a.getOk("/v1/reports/income-expenses?period=" + period);
                LocalDate from = switch (period) {
                    case "D7" -> today.minusDays(6);
                    case "D30" -> today.minusDays(29);
                    case "M3" -> today.minusMonths(3);
                    default -> today.minusMonths(11).withDayOfMonth(1);
                };
                LocalDate to = period.equals("M12") ? YearMonth.from(today).atEndOfMonth() : today;
                Long sql = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'EXPENSE' "
                        + "and effective_date between ? and ?", Long.class, a.id, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
                if (!s.get("from").asString().equals(from.toString()) || !s.get("to").asString().equals(to.toString())) {
                    problems.add(today + " " + period + " janela " + s.get("from").asString() + ".." + s.get("to").asString());
                }
                if (CardQaWorld.cents(s.get("totals").get("expenses")) != sql) {
                    problems.add(today + " " + period + " totals " + CardQaWorld.cents(s.get("totals").get("expenses")) + " != " + sql);
                }
                long sum = 0;
                LocalDate expectedNext = from;
                for (JsonNode p : s.get("points")) {
                    sum += CardQaWorld.cents(p.get("expenses"));
                    LocalDate start = LocalDate.parse(p.get("periodStart").asString());
                    if (!start.equals(expectedNext)) {
                        problems.add(today + " " + period + " balde começa em " + start + " esperado " + expectedNext);
                    }
                    expectedNext = LocalDate.parse(p.get("periodEnd").asString()).plusDays(1);
                }
                if (!expectedNext.equals(to.plusDays(1))) {
                    problems.add(today + " " + period + " baldes não terminam em " + to + " (próximo " + expectedNext + ")");
                }
                if (sum != sql) {
                    problems.add(today + " " + period + " Σ pontos " + sum + " != " + sql);
                }
                checks++;
            }
        }
        System.out.println("QA-F0-BORDER janelas verificadas=" + checks + " problemas=" + problems.size());
        problems.stream().limit(15).forEach(p -> System.out.println("QA-F0-BORDER PROBLEMA " + p));
        assertThat(problems).isEmpty();
    }

    private static int rnd(LocalDate d) {
        return (d.getDayOfYear() * 7) % 24;
    }

    @Test
    @DisplayName("horário de verão histórico: 04/11/2018 (a hora 00:00 não existe) e 17/02/2019 (o dia 16 tem 25 horas): o dia do usuário vira na hora certa")
    void horarioDeVerao() throws Exception {
        Actor a = world.new Actor("bord7");
        String[][] instants = {
                {"2018-11-04T02:59:59Z", "2018-11-03"}, // 23:59:59 -03 de 03/11
                {"2018-11-04T03:00:00Z", "2018-11-04"}, // 01:00:00 -02 de 04/11 (00:00 não existiu)
                {"2019-02-17T01:59:59Z", "2019-02-16"}, // 23:59:59 -02 de 16/02
                {"2019-02-17T02:00:00Z", "2019-02-16"}, // 23:00:00 -03 de 16/02 (a hora repete)
                {"2019-02-17T02:59:59Z", "2019-02-16"},
                {"2019-02-17T03:00:00Z", "2019-02-17"}, // 00:00:00 -03 de 17/02
                {"2028-02-29T02:59:59Z", "2028-02-28"}, // 23:59:59 -03 de 28/02 (bissexto)
                {"2028-02-29T03:00:00Z", "2028-02-29"},
                {"2028-03-01T02:59:59Z", "2028-02-29"},
                {"2028-03-01T03:00:00Z", "2028-03-01"},
                {"2026-12-31T23:59:59Z", "2026-12-31"}, // 20:59:59 -03
                {"2027-01-01T02:59:59Z", "2026-12-31"},
                {"2027-01-01T03:00:00Z", "2027-01-01"}};
        for (String[] i : instants) {
            clock.set(Instant.parse(i[0]));
            a.bearer = auth.bearer(a.id);
            JsonNode s = a.getOk("/v1/reports/income-expenses?period=D7");
            assertThat(s.get("to").asString()).as("D7 em " + i[0]).isEqualTo(i[1]);
            assertThat(s.get("points").get(6).get("partial").asBoolean()).isTrue();
            assertThat(s.get("points")).hasSize(7);
            JsonNode dash = a.getOk("/v1/dashboard/summary");
            assertThat(dash.get("asOf").asString()).as("dashboard.asOf em " + i[0]).isEqualTo(i[1]);
            JsonNode m12 = a.getOk("/v1/reports/income-expenses?period=M12");
            assertThat(m12.get("to").asString()).isEqualTo(YearMonth.parse(i[1].substring(0, 7)).atEndOfMonth().toString());
        }
    }

    @Test
    @DisplayName("contrato: campos opcionais da 2.6.0 (inAccountsChange ausente em projeção; savingsRate/savingsRateDelta, other, share e percent presentes como null, nunca ausentes)")
    void opcionaisAusentesVersusNull() throws Exception {
        Actor a = world.new Actor("bord8");
        create(a, QaWorld.expense(a.nubank, a.food, "10.00", "2026-10-09", "Sem receita"));
        JsonNode sum = a.getOk("/v1/summaries/2026-10");
        assertThat(sum.has("savingsRate")).isTrue();
        assertThat(sum.get("savingsRate").isNull()).isTrue();
        assertThat(sum.get("comparison").get("previousMonth").has("savingsRate")).isTrue();
        assertThat(sum.get("comparison").has("savingsRateDelta")).isTrue();
        JsonNode spending = a.getOk("/v1/reports/spending-by-category?limit=1");
        assertThat(spending.has("other")).isTrue();
        assertThat(spending.has("base")).isTrue();
        JsonNode search = a.getOk("/v1/search?q=sem");
        System.out.println("QA-F0-BORDER search keys: " + search.propertyNames());
        JsonNode first = search.get("transactions").get(0);
        System.out.println("QA-F0-BORDER search transaction keys: " + first.propertyNames() + " truncated=" + search.get("truncated"));
        assertThat(world.contractViolations).isEmpty();
    }
}
