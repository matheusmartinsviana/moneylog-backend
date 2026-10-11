package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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

import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T61: propriedade do ciclo do cartão")
class CardPropertyQaIT {

    private static final long[] SEEDS = {20261005L, 7L, 42L, 1234567L};
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
        return clock.instant().atZone(java.time.ZoneId.of("America/Sao_Paulo")).toLocalDate();
    }

    private boolean bigRefunds;

    @Test
    @DisplayName("4 sementes x 170 operações: invariantes do ciclo, consumo por compra >= 0, PAID imutável, dashboard = lista = cartão")
    void propriedade() throws Exception {
        bigRefunds = false;
        run();
    }

    @Test
    @DisplayName("BUG-QA-14: mesma propriedade com reembolsos grandes ligados à compra")
    void propriedadeComReembolsosGrandes() throws Exception {
        bigRefunds = true;
        run();
    }

    private void run() throws Exception {
        List<String> violations = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        int totalOps = 0;
        for (long seed : SEEDS) {
            clock.set(MutableClockConfig.INITIAL);
            CardQaWorld.Actor a = world.new Actor("prop" + seed);
            Random rnd = new Random(seed);
            Map<String, Integer> statuses = new HashMap<>();
            Map<String, Set<String>> paidFrozen = new HashMap<>();
            for (int op = 0; op < OPS; op++) {
                String label = "seed " + seed + " op " + op + " (" + today() + ")";
                try {
                    String what = step(a, rnd, statuses, violations, label);
                    if (op % 5 == 4 || op == OPS - 1) {
                        verify(a, paidFrozen, violations, label + " após " + what);
                    }
                } catch (AssertionError | RuntimeException e) {
                    violations.add(label + ": exceção " + e);
                }
                totalOps++;
            }
            summary.append("seed=").append(seed).append(' ').append(statuses).append(' ');
        }
        System.out.println("QA-CARD-PROP ops=" + totalOps + " " + summary + "violations=" + violations.size());
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
        System.out.println("QA-CARD-PROP codes vistos: " + new TreeSet<>(world.seenCodes));
        assertThat(violations).as("violações").isEmpty();
    }

    private String step(CardQaWorld.Actor a, Random rnd, Map<String, Integer> statuses, List<String> violations, String label) throws Exception {
        int roll = rnd.nextInt(100);
        CardQaWorld.Res r;
        String what;
        LocalDate today = today();
        if (roll < 28) {
            long cents = 1 + rnd.nextInt(200_000);
            int[] opts = {1, 1, 2, 3, 6, 12};
            int n = opts[rnd.nextInt(opts.length)];
            r = a.purchase(CardQaWorld.money(cents), n, today.minusDays(rnd.nextInt(21)).toString(), "Compra " + rnd.nextInt(1000));
            what = "compra";
        } else if (roll < 36) {
            List<String> purchases = jdbc.queryForList("select id::text from transactions where user_id = ? and type = 'CARD_PURCHASE' and status = 'ACTIVE'", String.class, a.id);
            String original = !purchases.isEmpty() && rnd.nextBoolean() ? purchases.get(rnd.nextInt(purchases.size())) : null;
            int bound = 60_000;
            if (original != null) {
                Long amount = jdbc.queryForObject("select amount_cents from transactions where id = ?::uuid", Long.class, original);
                bound = bigRefunds ? (int) Math.max(1, amount) : 8_000;
            }
            r = a.cardRefund(CardQaWorld.money(1 + rnd.nextInt(bound)), today.toString(), original);
            what = "reembolso";
        } else if (roll < 58) {
            List<JsonNode> invoices = a.invoices();
            JsonNode inv = invoices.isEmpty() ? a.cardNow().get("currentInvoice") : invoices.get(rnd.nextInt(invoices.size()));
            long out = CardQaWorld.cents(inv.get("outstanding"));
            long cents = switch (rnd.nextInt(5)) {
                case 0 -> Math.max(1, out);
                case 1 -> Math.max(1, out / 2);
                case 2 -> out + 1_700;
                case 3 -> 1 + rnd.nextInt(30_000);
                default -> Math.max(1, out);
            };
            r = a.pay(UUID.fromString(inv.get("id").asString()), CardQaWorld.money(cents), today.toString(), null);
            what = "pagamento";
        } else if (roll < 66) {
            List<String> all = jdbc.queryForList("select id::text from transactions where user_id = ? and card_id is not null", String.class, a.id);
            String id = all.isEmpty() ? UUID.randomUUID().toString() : all.get(rnd.nextInt(all.size()));
            r = a.voidTx(id);
            what = "estorno";
        } else if (roll < 74) {
            List<String> all = jdbc.queryForList("select id::text from transactions where user_id = ? and type = 'CARD_PURCHASE'", String.class, a.id);
            String id = all.isEmpty() ? UUID.randomUUID().toString() : all.get(rnd.nextInt(all.size()));
            r = a.cancel(id);
            what = "cancelamento";
        } else if (roll < 82) {
            r = a.changeDays(a.card, 1 + rnd.nextInt(31), 1 + rnd.nextInt(31));
            what = "mudança de dias";
        } else if (roll < 92) {
            int days = 1 + rnd.nextInt(45);
            a.at(clock.instant().plusSeconds(days * 86_400L));
            return "tempo +" + days + "d";
        } else {
            r = switch (rnd.nextInt(3)) {
                case 0 -> a.pay(UUID.randomUUID(), "10.00", today.toString(), null);
                case 1 -> a.raw(HttpMethod.POST, "/v1/invoices/" + a.invoiceId(today.plusMonths(1)) + "/payments", "{\"accountId\":\"" + a.nubank
                        + "\",\"amount\":\"0.00\",\"date\":\"" + today + "\"}", UUID.randomUUID().toString());
                default -> a.voidTx(UUID.randomUUID().toString());
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

    private void verify(CardQaWorld.Actor a, Map<String, Set<String>> paidFrozen, List<String> violations, String label) throws Exception {
        for (String v : CardInvariants.check(a, a.card)) {
            violations.add(label + ": " + v);
        }
        List<Map<String, Object>> negative = jdbc.queryForList("""
                select p.id::text as id, sum(lp.amount_cents) as consumption from transactions p
                join ledger_postings lp on lp.active and lp.book_type = 'EXPENSE'
                  and (lp.transaction_id = p.id or lp.transaction_id in (select r.id from transactions r where r.refund_of_id = p.id))
                where p.user_id = ? and p.type = 'CARD_PURCHASE' group by p.id having sum(lp.amount_cents) < 0""", a.id);
        negative.forEach(m -> violations.add(label + ": consumo negativo na compra " + m.get("id") + ": " + m.get("consumption")));

        List<JsonNode> invoices = a.invoices();
        Set<String> paidNow = new TreeSet<>();
        for (JsonNode summary : invoices) {
            String id = summary.get("id").asString();
            if (!"PAID".equals(summary.get("status").asString())) {
                paidFrozen.remove(id);
                continue;
            }
            paidNow.add(id);
            Set<String> items = new TreeSet<>();
            a.getOk("/v1/invoices/" + id).get("items").forEach(i -> items.add(i.get("transactionId").asString() + "#" + i.get("number")));
            Set<String> before = paidFrozen.putIfAbsent(id, items);
            if (before != null && !before.equals(items)) {
                violations.add(label + ": a fatura PAID " + summary.get("closingDate") + " mudou de itens: antes " + before + " agora " + items);
                paidFrozen.put(id, items);
            }
        }

        LocalDate today = today();
        JsonNode upcoming = a.getOk("/v1/dashboard/upcoming?from=" + today + "&to=" + today.plusYears(3));
        long expectedOut = 0;
        Map<String, Long> upcomingByDue = new HashMap<>();
        for (JsonNode i : upcoming.get("items")) {
            if ("OUT".equals(i.get("direction").asString())) {
                if (!a.card.equals(i.get("refId").asString())) {
                    violations.add(label + ": item de /upcoming com refId diferente do cartão: " + i.get("refId"));
                }
                upcomingByDue.merge(i.get("dueDate").asString(), CardQaWorld.cents(i.get("amount")), Long::sum);
            }
        }
        Map<String, Long> invoicesByDue = new HashMap<>();
        long d30 = 0;
        for (JsonNode inv : invoices) {
            long out = CardQaWorld.cents(inv.get("outstanding"));
            if ("PAID".equals(inv.get("status").asString()) || out == 0) {
                continue;
            }
            expectedOut += out;
            invoicesByDue.merge(inv.get("dueDate").asString(), out, Long::sum);
            if (!LocalDate.parse(inv.get("dueDate").asString()).isAfter(today.plusDays(30))) {
                d30 += out;
            }
        }
        if (!upcomingByDue.equals(invoicesByDue)) {
            violations.add(label + ": /upcoming por vencimento " + upcomingByDue + " != faturas não pagas por vencimento " + invoicesByDue);
        }
        long upcomingOut = CardQaWorld.cents(upcoming.get("totals").get("out"));
        if (upcomingOut != expectedOut) {
            violations.add(label + ": Σ /upcoming OUT " + upcomingOut + " != Σ outstanding das faturas não pagas " + expectedOut);
        }
        JsonNode summary = a.getOk("/v1/dashboard/summary");
        long committed30 = CardQaWorld.cents(summary.get("committed").get("d30"));
        if (committed30 != d30) {
            violations.add(label + ": committed.d30 " + committed30 + " != Σ outstanding com vencimento em 30 dias ou vencido " + d30);
        }
        long inAccounts = CardQaWorld.cents(summary.get("balances").get("inAccounts"));
        long available = CardQaWorld.cents(summary.get("balances").get("available"));
        if (available != inAccounts - committed30) {
            violations.add(label + ": available " + available + " != inAccounts − committed.d30 " + (inAccounts - committed30));
        }
        String competence = summary.get("month").get("competence").asString();
        long monthConsumption = CardQaWorld.cents(summary.get("month").get("consumption"));
        long entries = CardQaWorld.cents(a.getOk("/v1/entries?competence=" + competence).get("totals").get("total"));
        if (monthConsumption != entries) {
            violations.add(label + ": month.consumption " + monthConsumption + " != Σ lançamentos da competência " + competence + " " + entries);
        }
    }
}
