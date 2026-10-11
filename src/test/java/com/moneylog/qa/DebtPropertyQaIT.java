package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T82: propriedade do ciclo de dívidas, divisão e recorrências")
class DebtPropertyQaIT {

    private static final long[] SEEDS = System.getProperty("qa.seeds") == null ? new long[] {20261007L, 11L, 90210L, 424242L}
            : java.util.Arrays.stream(System.getProperty("qa.seeds").split(",")).mapToLong(Long::parseLong).toArray();
    private static final int OPS = 170;

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

    private LocalDate today() {
        return clock.instant().atZone(ZoneId.of("America/Sao_Paulo")).toLocalDate();
    }

    @Test
    @DisplayName("4 sementes x 170 operações: razão, dívidas, pessoas, consumo, dashboard = lista = contas, /upcoming e ocorrências")
    void propriedade() throws Exception {
        List<String> violations = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        int totalOps = 0;
        for (long seed : SEEDS) {
            clock.set(MutableClockConfig.INITIAL);
            Actor a = world.new Actor("e4prop" + seed);
            Random rnd = new Random(seed);
            List<String> people = new ArrayList<>();
            for (String n : List.of("João", "Maria", "Ana", "Pedro")) {
                people.add(DebtQa.person(a, n));
            }
            Map<String, Integer> statuses = new TreeMap<>();
            for (int op = 0; op < OPS; op++) {
                String label = "seed " + seed + " op " + op + " (" + today() + ")";
                try {
                    String what = step(a, rnd, people, statuses, violations, label);
                    if (op % 5 == 4 || op == OPS - 1) {
                        verify(a, violations, label + " após " + what);
                    }
                } catch (AssertionError | RuntimeException e) {
                    violations.add(label + ": exceção " + e);
                }
                totalOps++;
            }
            summary.append("seed=").append(seed).append(' ').append(statuses).append('\n');
        }
        System.out.println("QA-E4-PROP ops=" + totalOps + " violations=" + violations.size() + " competências com consumo mensal negativo (reembolso em mês posterior): " + monthlyNegatives + "\n" + summary);
        violations.addAll(world.contractViolations);
        String yaml = java.nio.file.Files.readString(com.moneylog.util.ContractValidation.SPEC_FILE);
        String table = yaml.substring(yaml.indexOf("## Códigos de erro"), yaml.indexOf("Se o domínio usar outro nome"));
        Set<String> documented = new TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("`([A-Z][A-Z0-9_*]+)`").matcher(table);
        while (m.find()) {
            documented.add(m.group(1));
        }
        for (String code : world.seenCodes) {
            boolean ok = documented.contains(code) || documented.stream().filter(d -> d.startsWith("*")).anyMatch(d -> code.endsWith(d.substring(1)));
            if (!ok) {
                violations.add("code de erro fora da tabela do contrato: " + code);
            }
        }
        System.out.println("QA-E4-PROP codes vistos: " + new TreeSet<>(world.seenCodes));
        assertThat(violations).as("violações").isEmpty();
    }

    private static String pick(Random rnd, List<String> list) {
        return list.get(rnd.nextInt(list.size()));
    }

    private String step(Actor a, Random rnd, List<String> people, Map<String, Integer> statuses, List<String> violations, String label) throws Exception {
        int roll = rnd.nextInt(100);
        Res r;
        String what;
        LocalDate today = today();
        String date = today.minusDays(rnd.nextInt(10)).toString();
        if (roll < 9) {
            long cents = 1 + rnd.nextInt(60_000);
            String exp = rnd.nextInt(3) == 0 ? null : today.plusDays(rnd.nextInt(60) - 15).toString();
            r = DebtQa.loan(a, CardQaWorld.money(cents), date, pick(rnd, people), exp);
            what = "empréstimo";
        } else if (roll < 22) {
            List<JsonNode> open = openDebts(a, "RECEIVABLE");
            if (open.isEmpty()) {
                return "sem dívida a receber";
            }
            JsonNode d = open.get(rnd.nextInt(open.size()));
            long out = CardQaWorld.cents(d.get("outstanding"));
            long cents = switch (rnd.nextInt(5)) {
                case 0 -> out;
                case 1 -> Math.max(1, out / 2);
                case 2 -> out + 1;
                case 3 -> Math.max(1, 1 + rnd.nextInt((int) Math.max(1, out)));
                default -> 1;
            };
            r = DebtQa.received(a, CardQaWorld.money(cents), date, d.get("id").asString());
            what = "recebimento";
        } else if (roll < 26) {
            r = DebtQa.borrowed(a, CardQaWorld.money(1 + rnd.nextInt(50_000)), date, pick(rnd, people));
            what = "captação";
        } else if (roll < 34) {
            List<JsonNode> open = openDebts(a, "PAYABLE");
            if (open.isEmpty()) {
                return "sem dívida a pagar";
            }
            JsonNode d = open.get(rnd.nextInt(open.size()));
            long out = CardQaWorld.cents(d.get("outstanding"));
            long cents = switch (rnd.nextInt(4)) {
                case 0 -> out;
                case 1 -> Math.max(1, out / 3);
                case 2 -> out + 1;
                default -> Math.max(1, 1 + rnd.nextInt((int) Math.max(1, out)));
            };
            r = DebtQa.payment(a, CardQaWorld.money(cents), date, d.get("id").asString());
            what = "pagamento";
        } else if (roll < 40) {
            boolean payable = rnd.nextBoolean();
            String category = payable && rnd.nextBoolean() ? a.food : null;
            String exp = rnd.nextInt(3) == 0 ? null : today.plusDays(rnd.nextInt(60) - 15).toString();
            r = DebtQa.plainDebt(a, pick(rnd, people), payable ? "PAYABLE" : "RECEIVABLE", CardQaWorld.money(1 + rnd.nextInt(40_000)), date, exp, category);
            what = "dívida sem movimentação";
        } else if (roll < 45) {
            List<JsonNode> all = DebtQa.debts(a);
            if (all.isEmpty()) {
                return "sem dívida";
            }
            String id = all.get(rnd.nextInt(all.size())).get("id").asString();
            r = rnd.nextInt(3) == 0 ? DebtQa.voidDebt(a, id) : DebtQa.cancelDebt(a, id);
            what = "perdão/estorno do registro";
        } else if (roll < 60) {
            int n = rnd.nextInt(5) == 0 ? 1 + rnd.nextInt(20) : 1 + rnd.nextInt(4);
            long total = Math.max(n + 1, 100 + rnd.nextInt(120_000));
            List<String> shares = new ArrayList<>();
            long left = total - 1 - rnd.nextInt((int) Math.min(total - n, 3));
            long used = 0;
            for (int i = 0; i < n; i++) {
                long remainingParts = n - i;
                long share = i == n - 1 ? left - used : Math.max(1, (left - used) / remainingParts - rnd.nextInt(2));
                share = Math.max(1, Math.min(share, left - used - (remainingParts - 1)));
                used += share;
                shares.add(DebtQa.shareJson(CardQaWorld.money(share), pick(rnd, people)));
            }
            r = DebtQa.sharedExpense(a, CardQaWorld.money(total), date, shares);
            what = "despesa dividida " + (n == 1 ? "1" : n <= 4 ? "2-4" : "5+") + " partes";
        } else if (roll < 66) {
            List<Map<String, Object>> expenses = jdbc.queryForList("select id::text as id, category_id::text as cat, amount_cents as c from transactions "
                    + "where user_id = ? and type = 'EXPENSE' and status = 'ACTIVE' and account_id is not null", a.id);
            if (expenses.isEmpty()) {
                return "sem despesa";
            }
            Map<String, Object> e = expenses.get(rnd.nextInt(expenses.size()));
            long bound = ((Number) e.get("c")).longValue();
            r = DebtQa.accountRefund(a, CardQaWorld.money(1 + rnd.nextInt((int) Math.max(1, bound))), today.toString(), (String) e.get("cat"), (String) e.get("id"));
            what = "reembolso de despesa";
        } else if (roll < 74) {
            List<String> all = jdbc.queryForList("select id::text from transactions where user_id = ?", String.class, a.id);
            if (all.isEmpty()) {
                return "sem movimentação";
            }
            r = a.voidTx(pick(rnd, all));
            what = "estorno";
        } else if (roll < 79) {
            int op = rnd.nextInt(3);
            if (op == 0) {
                r = a.purchase(CardQaWorld.money(1 + rnd.nextInt(90_000)), new int[] {1, 1, 2, 3, 6}[rnd.nextInt(5)], date, "Compra");
                what = "compra no cartão";
            } else if (op == 1) {
                List<JsonNode> invoices = a.invoices();
                if (invoices.isEmpty()) {
                    return "sem fatura";
                }
                JsonNode inv = invoices.get(rnd.nextInt(invoices.size()));
                long out = CardQaWorld.cents(inv.get("outstanding"));
                r = a.pay(UUID.fromString(inv.get("id").asString()), CardQaWorld.money(Math.max(1, out)), today.toString(), null);
                what = "pagamento de fatura";
            } else {
                r = a.changeDays(a.card, 1 + rnd.nextInt(31), 1 + rnd.nextInt(31));
                what = "mudança de dias";
            }
        } else if (roll < 86) {
            r = createRecurrence(a, rnd, today);
            what = "recorrência";
        } else if (roll < 91) {
            List<JsonNode> pend = DebtQa.pending(a, today.plusDays(90));
            if (pend.isEmpty()) {
                return "sem ocorrência";
            }
            JsonNode o = pend.get(rnd.nextInt(pend.size()));
            String amount = rnd.nextInt(3) == 0 ? CardQaWorld.money(1 + rnd.nextInt(30_000)) : null;
            r = DebtQa.realize(a, o.get("recurrenceId").asString(), o.get("date").asString(), amount);
            what = "realizar " + (o.get("cardId").isNull() ? "conta" : "cartão");
        } else if (roll < 94) {
            List<JsonNode> pend = DebtQa.pending(a, today.plusDays(90));
            if (pend.isEmpty()) {
                return "sem ocorrência";
            }
            JsonNode o = pend.get(rnd.nextInt(pend.size()));
            r = DebtQa.skip(a, o.get("recurrenceId").asString(), o.get("date").asString());
            what = "pular";
        } else if (roll < 95) {
            List<JsonNode> recs = DebtQa.all(a, "/v1/recurrences");
            if (recs.isEmpty()) {
                return "sem recorrência";
            }
            String id = recs.get(rnd.nextInt(recs.size())).get("id").asString();
            boolean off = rnd.nextBoolean();
            r = a.call(HttpMethod.POST, "/v1/recurrences/" + id + (off ? "/deactivate" : "/end"), off ? null : "{}", null);
            what = "encerrar/desativar";
        } else if (roll < 98) {
            int days = 1 + rnd.nextInt(20);
            a.at(clock.instant().plusSeconds(days * 86_400L));
            return "tempo +" + days + "d";
        } else {
            r = switch (rnd.nextInt(4)) {
                case 0 -> DebtQa.received(a, "10.00", date, UUID.randomUUID().toString());
                case 1 -> DebtQa.realize(a, UUID.randomUUID().toString(), date, null);
                case 2 -> DebtQa.cancelDebt(a, UUID.randomUUID().toString());
                default -> DebtQa.loan(a, "0.00", date, pick(rnd, people), null);
            };
            what = "inválida";
        }
        statuses.merge(what + ":" + r.status(), 1, Integer::sum);
        if (r.status() >= 500) {
            violations.add(label + ": " + what + " respondeu " + r);
        } else if (r.status() == 400) {
            violations.add(label + ": " + what + " com corpo válido deu 400 " + r);
        }
        return what;
    }

    private Res createRecurrence(Actor a, Random rnd, LocalDate today) throws Exception {
        String type = new String[] {"EXPENSE", "EXPENSE", "INCOME", "CARD_PURCHASE"}[rnd.nextInt(4)];
        String freq = new String[] {"DAILY", "WEEKLY", "MONTHLY", "YEARLY", "CUSTOM"}[rnd.nextInt(5)];
        LocalDate start = today.minusDays(rnd.nextInt(25)).plusDays(rnd.nextInt(3) == 0 ? 20 : 0);
        String target = "CARD_PURCHASE".equals(type) ? "\"cardId\":\"" + a.card + "\"" : DebtQa.accountTarget(a, type);
        String category = "INCOME".equals(type) ? a.income : "CARD_PURCHASE".equals(type) ? a.tech : (rnd.nextBoolean() ? a.food : a.tech);
        List<String> extra = new ArrayList<>();
        if ("CUSTOM".equals(freq)) {
            extra.add("\"interval\":" + (1 + rnd.nextInt(4)) + ",\"unit\":\"" + new String[] {"DAYS", "WEEKS", "MONTHS"}[rnd.nextInt(3)] + "\"");
        }
        if ("DAILY".equals(freq) || rnd.nextInt(3) == 0) {
            extra.add("\"maxOccurrences\":" + (2 + rnd.nextInt(8)));
        }
        return a.call(HttpMethod.POST, "/v1/recurrences", DebtQa.recurrenceBody(type, CardQaWorld.money(100 + rnd.nextInt(30_000)), "Rec " + rnd.nextInt(100),
                category, target, freq, start.toString(), extra.isEmpty() ? null : String.join(",", extra)), null);
    }

    private List<JsonNode> openDebts(Actor a, String direction) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode d : DebtQa.debts(a)) {
            String status = d.get("status").asString();
            if (direction.equals(d.get("direction").asString()) && ("OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status))) {
                out.add(d);
            }
        }
        return out;
    }

    private final Set<String> monthlyNegatives = new TreeSet<>();

    private void verify(Actor a, List<String> v, String label) throws Exception {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> m : jdbc.queryForList("""
                select p.id::text as id, sum(lp.amount_cents) as consumption from transactions p
                join ledger_postings lp on lp.active and lp.book_type = 'EXPENSE'
                  and (lp.transaction_id = p.id or lp.transaction_id in (select r.id from transactions r where r.refund_of_id = p.id and r.status = 'ACTIVE'))
                where p.user_id = ? and p.status = 'ACTIVE' and p.type in ('EXPENSE','CARD_PURCHASE') group by p.id having sum(lp.amount_cents) < 0""", a.id)) {
            found.add("consumo negativo na despesa/compra " + m.get("id") + ": " + m.get("consumption"));
        }
        if (a.ledgerSum() != 0) {
            found.add("razão não fecha em zero: " + a.ledgerSum());
        }
        if (a.unbalancedGroups() != 0) {
            found.add("grupos desbalanceados: " + a.unbalancedGroups());
        }
        found.addAll(CardInvariants.check(a, a.card));

        List<JsonNode> debts = DebtQa.debts(a);
        long recvOut = 0;
        long payOut = 0;
        Map<String, Long> netByPerson = new HashMap<>();
        for (JsonNode d : debts) {
            long principal = CardQaWorld.cents(d.get("principal"));
            long settled = CardQaWorld.cents(d.get("settled"));
            long outstanding = CardQaWorld.cents(d.get("outstanding"));
            String status = d.get("status").asString();
            String id = d.get("id").asString();
            if (outstanding < 0 || settled < 0) {
                found.add("dívida " + id + " com saldo negativo: settled " + settled + " outstanding " + outstanding);
            }
            if (settled > principal) {
                found.add("dívida " + id + " quitada acima do principal: " + settled + " > " + principal);
            }
            boolean open = "OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status);
            if (open && settled + outstanding != principal) {
                found.add("dívida aberta " + id + ": settled + outstanding != principal");
            }
            if ("SETTLED".equals(status) && outstanding != 0) {
                found.add("dívida SETTLED " + id + " com saldo " + outstanding);
            }
            if (("CANCELED".equals(status) || "VOIDED".equals(status)) && outstanding != 0) {
                found.add("dívida " + status + " " + id + " com saldo " + outstanding);
            }
            long sumSettlements = 0;
            for (JsonNode s : d.get("settlements")) {
                if (s.get("active").asBoolean()) {
                    sumSettlements += CardQaWorld.cents(s.get("amount"));
                }
            }
            if (!"VOIDED".equals(status) && sumSettlements != settled && !"CANCELED".equals(status)) {
                found.add("dívida " + id + ": Σ settlements " + sumSettlements + " != settled " + settled);
            }
            if (open) {
                boolean recv = "RECEIVABLE".equals(d.get("direction").asString());
                if (recv) {
                    recvOut += outstanding;
                } else {
                    payOut += outstanding;
                }
                netByPerson.merge(d.get("person").get("id").asString(), recv ? outstanding : -outstanding, Long::sum);
            }
        }
        long book = bookSum(a, "RECEIVABLE");
        if (book != recvOut) {
            found.add("livro RECEIVABLE " + book + " != Σ saldo a receber " + recvOut);
        }
        long payBook = bookSum(a, "PAYABLE");
        if (payBook != -payOut) {
            found.add("livro PAYABLE " + payBook + " != −Σ saldo a pagar " + (-payOut));
        }

        for (JsonNode p : DebtQa.all(a, "/v1/people")) {
            String pid = p.get("id").asString();
            long net = CardQaWorld.cents(p.get("net"));
            long expected = netByPerson.getOrDefault(pid, 0L);
            if (net != expected) {
                found.add("pessoa " + p.get("name").asString() + " net " + net + " != Σ dívidas " + expected);
            }
            JsonNode detail = a.getOk("/v1/people/" + pid);
            if (CardQaWorld.cents(detail.get("net")) != net) {
                found.add("pessoa " + pid + ": net do detalhe != net da lista");
            }
            long detailSum = 0;
            for (JsonNode dd : detail.get("debts")) {
                String st = dd.get("status").asString();
                if ("OPEN".equals(st) || "PARTIALLY_SETTLED".equals(st)) {
                    long o = CardQaWorld.cents(dd.get("outstanding"));
                    detailSum += "RECEIVABLE".equals(dd.get("direction").asString()) ? o : -o;
                }
            }
            if (detailSum != net) {
                found.add("pessoa " + pid + ": Σ debts[] do detalhe " + detailSum + " != net " + net);
            }
        }

        Long leaked = jdbc.queryForObject("select count(*) from ledger_postings lp join transactions t on t.id = lp.transaction_id where lp.user_id = ? and lp.active "
                + "and lp.book_type in ('INCOME') and t.type in ('LOAN_GIVEN','MONEY_RECEIVED','DEBT_PAYMENT')", Long.class, a.id);
        Long leakedExpense = jdbc.queryForObject("select count(*) from ledger_postings lp join transactions t on t.id = lp.transaction_id where lp.user_id = ? and lp.active "
                + "and lp.book_type = 'EXPENSE' and t.type in ('LOAN_GIVEN','MONEY_RECEIVED','DEBT_PAYMENT')", Long.class, a.id);
        if (leaked != null && leaked > 0 || leakedExpense != null && leakedExpense > 0) {
            found.add("movimentação de dívida gerou lançamento de receita/despesa: INCOME " + leaked + " EXPENSE " + leakedExpense);
        }

        List<String> competences = jdbc.queryForList("select distinct competence from ledger_postings where user_id = ? and active and book_type = 'EXPENSE' order by 1",
                String.class, a.id);
        for (String c : competences) {
            long ledger = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'EXPENSE' and competence = ?",
                    Long.class, a.id, c);
            JsonNode entries = a.getOk("/v1/entries?competence=" + c + "&pageSize=50");
            long total = CardQaWorld.cents(entries.get("totals").get("total"));
            if (total != ledger) {
                found.add("entries " + c + " total " + total + " != razão EXPENSE " + ledger);
            }
            if (total < 0) {
                monthlyNegatives.add(c + ":" + total);
            }
            long byCategory = 0;
            for (JsonNode ca : entries.get("totals").get("byCategory")) {
                long amount = CardQaWorld.cents(ca.get("amount"));
                byCategory += amount;
                if (amount < 0) {
                    monthlyNegatives.add(c + "/" + ca.get("category").get("name").asString() + ":" + amount);
                }
            }
            if (byCategory != total) {
                found.add("entries " + c + ": Σ byCategory " + byCategory + " != total " + total);
            }
            if (!entries.get("hasNext").asBoolean()) {
                long rows = 0;
                for (JsonNode e : entries.get("items")) {
                    rows += CardQaWorld.cents(e.get("amount"));
                }
                if (entries.has("debtItems")) {
                    for (JsonNode e : entries.get("debtItems")) {
                        rows += CardQaWorld.cents(e.get("amount"));
                    }
                }
                if (rows != total) {
                    found.add("entries " + c + ": Σ linhas (itens + debtItems) " + rows + " != total " + total);
                }
            }
        }

        LocalDate today = today();
        JsonNode summary = a.getOk("/v1/dashboard/summary");
        JsonNode month = summary.get("month");
        String competence = month.get("competence").asString();
        long monthConsumption = CardQaWorld.cents(month.get("consumption"));
        long entriesTotal = CardQaWorld.cents(a.getOk("/v1/entries?competence=" + competence).get("totals").get("total"));
        if (monthConsumption != entriesTotal) {
            found.add("month.consumption " + monthConsumption + " != entries " + entriesTotal);
        }
        LocalDate first = LocalDate.parse(competence + "-01");
        JsonNode cashFlow = a.getOk("/v1/transactions?from=" + first + "&to=" + first.withDayOfMonth(first.lengthOfMonth())).get("totals").get("cashFlow");
        for (String f : List.of("in", "out", "net")) {
            if (CardQaWorld.cents(cashFlow.get(f)) != CardQaWorld.cents(month.get("cashFlow").get(f))) {
                found.add("lista.totals.cashFlow." + f + " " + cashFlow.get(f).get("amount") + " != month.cashFlow " + month.get("cashFlow").get(f).get("amount"));
            }
        }
        long income = CardQaWorld.cents(month.get("income"));
        long ledgerIncome = -jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'INCOME' and competence = ?",
                Long.class, a.id, competence);
        if (income != ledgerIncome) {
            found.add("month.income " + income + " != razão INCOME " + ledgerIncome);
        }
        long accountBalance = CardQaWorld.cents(a.getOk("/v1/accounts/" + a.nubank).get("currentBalance"));
        long ledgerBalance = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'ACCOUNT' "
                + "and book_id = ?::uuid and effective_date <= ?", Long.class, a.id, a.nubank, java.sql.Date.valueOf(today));
        if (accountBalance != ledgerBalance) {
            found.add("saldo da conta " + accountBalance + " != abertura + razão ACCOUNT " + ledgerBalance);
        }
        long inAccounts = CardQaWorld.cents(summary.get("balances").get("inAccounts"));
        if (inAccounts != accountBalance) {
            found.add("balances.inAccounts " + inAccounts + " != saldo da conta " + accountBalance);
        }
        JsonNode nw = summary.get("netWorth");
        if (CardQaWorld.cents(nw.get("receivables")) != recvOut) {
            found.add("netWorth.receivables " + nw.get("receivables").get("amount") + " != Σ saldo a receber " + recvOut);
        }
        if (CardQaWorld.cents(nw.get("payables")) != -payOut) {
            found.add("netWorth.payables " + nw.get("payables").get("amount") + " != Σ saldo a pagar " + payOut);
        }
        long expectedTotal = CardQaWorld.cents(nw.get("accounts")) + CardQaWorld.cents(nw.get("receivables")) + CardQaWorld.cents(nw.get("cardLiabilities"))
                + CardQaWorld.cents(nw.get("payables"));
        if (CardQaWorld.cents(nw.get("total")) != expectedTotal) {
            found.add("netWorth.total " + nw.get("total").get("amount") + " != contas + a receber − cartão − a pagar " + expectedTotal);
        }
        long equity = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type in ('ACCOUNT','RECEIVABLE','PAYABLE','CARD')",
                Long.class, a.id);
        long ledgerNetWorth = equity;
        long ledgerNetWorthToday = ledgerNetWorth;
        if (CardQaWorld.cents(nw.get("total")) != ledgerNetWorthToday) {
            long upToToday = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active "
                    + "and book_type in ('ACCOUNT','RECEIVABLE','PAYABLE','CARD') and effective_date <= ?", Long.class, a.id, java.sql.Date.valueOf(today));
            if (CardQaWorld.cents(nw.get("total")) != upToToday) {
                found.add("netWorth.total " + nw.get("total").get("amount") + " != Σ livros (abertura + ACCOUNT + RECEIVABLE + PAYABLE + CARD) " + upToToday);
            }
        }

        Long dup = jdbc.queryForObject("select count(*) from (select recurrence_id, occurrence_date from transactions where user_id = ? and status = 'ACTIVE' "
                + "and recurrence_id is not null group by 1, 2 having count(*) > 1) t", Long.class, a.id);
        if (dup != null && dup > 0) {
            found.add("ocorrência realizada duas vezes: " + dup);
        }
        Long skippedAndRealized = jdbc.queryForObject("select count(*) from recurrence_skips s join transactions t on t.recurrence_id = s.recurrence_id "
                + "and t.occurrence_date = s.occurrence_date and t.status = 'ACTIVE' where s.user_id = ?", Long.class, a.id);
        if (skippedAndRealized != null && skippedAndRealized > 0) {
            found.add("ocorrência pulada e realizada ao mesmo tempo: " + skippedAndRealized);
        }
        LocalDate to = today.plusDays(120);
        JsonNode upcoming = a.getOk("/v1/dashboard/upcoming?from=" + today + "&to=" + to);
        long outExpected = 0;
        long inExpected = 0;
        Map<String, Long> expectedByKey = new TreeMap<>();
        for (JsonNode inv : a.invoices()) {
            long o = CardQaWorld.cents(inv.get("outstanding"));
            if (!"PAID".equals(inv.get("status").asString()) && o > 0 && !LocalDate.parse(inv.get("dueDate").asString()).isAfter(to)) {
                outExpected += o;
                expectedByKey.merge("INVOICE|" + inv.get("dueDate").asString(), o, Long::sum);
            }
        }
        for (JsonNode o : DebtQa.pending(a, to)) {
            long amount = CardQaWorld.cents(o.get("amount"));
            if ("OUT".equals(o.get("direction").asString())) {
                outExpected += amount;
            } else {
                inExpected += amount;
            }
            expectedByKey.merge("RECURRENCE|" + o.get("recurrenceId").asString() + "|" + o.get("date").asString(), amount, Long::sum);
        }
        for (JsonNode d : debts) {
            String status = d.get("status").asString();
            if (("OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status)) && !d.get("expectedDate").isNull()
                    && !LocalDate.parse(d.get("expectedDate").asString()).isAfter(to)) {
                long o = CardQaWorld.cents(d.get("outstanding"));
                if ("PAYABLE".equals(d.get("direction").asString())) {
                    outExpected += o;
                } else {
                    inExpected += o;
                }
                expectedByKey.merge("DEBT|" + d.get("id").asString(), o, Long::sum);
            }
        }
        for (Map<String, Object> row : jdbc.queryForList("select lp.transaction_id::text tx, lp.effective_date d, sum(lp.amount_cents) net from ledger_postings lp "
                + "join accounts ac on ac.id = lp.book_id where lp.user_id = ? and lp.active and lp.source_type = 'TRANSACTION' and lp.book_type = 'ACCOUNT' "
                + "and ac.type <> 'INVESTMENT' and lp.effective_date > ? and lp.effective_date <= ? group by lp.transaction_id, lp.effective_date having sum(lp.amount_cents) <> 0",
                a.id, java.sql.Date.valueOf(today), java.sql.Date.valueOf(to))) {
            long net = ((Number) row.get("net")).longValue();
            if (net < 0) {
                outExpected += -net;
            } else {
                inExpected += net;
            }
            expectedByKey.merge("SCHEDULED|" + row.get("tx") + "|" + row.get("d"), Math.abs(net), Long::sum);
        }
        long upOut = CardQaWorld.cents(upcoming.get("totals").get("out"));
        long upIn = CardQaWorld.cents(upcoming.get("totals").get("in"));
        if (upOut != outExpected) {
            found.add("Σ /upcoming OUT " + upOut + " != faturas + ocorrências + dívidas a pagar datadas " + outExpected);
        }
        if (upIn != inExpected) {
            found.add("Σ /upcoming IN " + upIn + " != ocorrências de receita + dívidas a receber datadas " + inExpected);
        }
        Map<String, Long> actualByKey = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        long sumItems = 0;
        for (JsonNode i : upcoming.get("items")) {
            String kind = i.get("kind").asString();
            String key = kind.startsWith("INVOICE") ? "INVOICE|" + i.get("dueDate").asString()
                    : "DEBT_PAYABLE".equals(kind) || "DEBT_RECEIVABLE".equals(kind) ? "DEBT|" + i.get("refId").asString()
                    : "SCHEDULED_TRANSACTION".equals(kind) ? "SCHEDULED|" + i.get("refId").asString() + "|" + i.get("dueDate").asString()
                    : "RECURRENCE|" + i.get("refId").asString() + "|" + i.get("dueDate").asString();
            long amount = CardQaWorld.cents(i.get("amount"));
            actualByKey.merge(key, amount, Long::sum);
            if (!seen.add(kind + "|" + i.get("refId").asString() + "|" + i.get("dueDate").asString())) {
                found.add("item duplicado em /upcoming: " + kind + " " + i.get("refId") + " " + i.get("dueDate"));
            }
            sumItems += "OUT".equals(i.get("direction").asString()) ? amount : 0;
        }
        if (!actualByKey.equals(expectedByKey)) {
            Map<String, Long> missing = new TreeMap<>(expectedByKey);
            actualByKey.forEach((k, val) -> missing.remove(k, val));
            Map<String, Long> extra = new TreeMap<>(actualByKey);
            expectedByKey.forEach((k, val) -> extra.remove(k, val));
            found.add("itens de /upcoming != fontes: faltam/diferem " + missing + " ; sobram/diferem " + extra);
        }
        if (sumItems != upOut) {
            found.add("Σ itens OUT " + sumItems + " != totals.out " + upOut);
        }
        Set<String> undatedExpected = new TreeSet<>();
        for (JsonNode d : debts) {
            String status = d.get("status").asString();
            if (("OPEN".equals(status) || "PARTIALLY_SETTLED".equals(status)) && d.get("expectedDate").isNull()) {
                undatedExpected.add(d.get("id").asString());
            }
        }
        Set<String> undatedActual = new TreeSet<>();
        for (JsonNode i : upcoming.get("undated")) {
            undatedActual.add(i.get("refId").asString());
        }
        if (!undatedActual.equals(undatedExpected)) {
            found.add("undated " + undatedActual + " != dívidas abertas sem data " + undatedExpected);
        }
        found.forEach(f -> v.add(label + ": " + f));
    }

    private long bookSum(Actor a, String book) {
        Long s = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = ?", Long.class, a.id, book);
        return s == null ? 0 : s;
    }
}
