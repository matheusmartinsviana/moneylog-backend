package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * T43 (QA): propriedade com semente fixa. Sequências aleatórias de criar, editar e estornar pela API (casos de uso reais,
 * Postgres real) contra um modelo independente em BigDecimal. Invariantes: o razão fecha em zero (total e por grupo de
 * origem), saldo das contas = modelo, lista = entries = dashboard, consumo por categoria nunca negativo, estornada sem
 * lançamento ativo, sem dupla contagem.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T43: propriedade do razão com semente fixa")
class LedgerPropertyQaIT {

    private static final String TODAY = "2026-10-31";

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

    static Stream<Long> seeds() {
        return Stream.of(20261005L, 7L, 42L, 1234567L, 987654321L);
    }

    /** Estado esperado de uma movimentação no modelo. */
    private static final class M {
        String id;
        String type;
        boolean active = true;
        BigDecimal amount;
        String date;
        String description;
        String account;        // conta principal (EXPENSE/INCOME/ADJUSTMENT/REFUND) ou origem/caixa
        String account2;       // destino (TRANSFER) ou investimento
        String category;
        String direction;      // CONTRIBUTION/REDEMPTION | INCREASE/DECREASE
        String refundOf;
        long version;
    }

    private final Map<String, M> txs = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private QaWorld world;
    private QaWorld.Actor u;
    private final Map<String, String> accountType = new HashMap<>();
    private List<String> cashAccounts;
    private List<String> investAccounts;
    private List<String> expCategories;
    private List<String> incCategories;

    @ParameterizedTest(name = "semente {0}")
    @MethodSource("seeds")
    @DisplayName("120 operações aleatórias: razão fecha, saldos = modelo, lista = entries = dashboard")
    void invariantesDoRazao(long seed) throws Exception {
        clock.set(Instant.parse("2026-10-31T15:00:00Z"));
        world = new QaWorld(mvc, om, jdbc, auth, tm);
        u = world.new Actor("prop");
        // token emitido com o relógio de hoje
        u = reissue(u);
        cashAccounts = List.of(u.nubank, u.xp, u.account("Carteira", "WALLET", "150.00"));
        investAccounts = List.of(u.xpInvest, u.account("Poupança Invest", "INVESTMENT", "300.00"));
        accountType.put(u.nubank, "CASH");
        accountType.put(u.xp, "CASH");
        accountType.put(cashAccounts.get(2), "CASH");
        investAccounts.forEach(i -> accountType.put(i, "INV"));
        Map<String, BigDecimal> opening = Map.of(u.nubank, bd("20000.00"), u.xp, bd("0.00"), cashAccounts.get(2), bd("150.00"),
                u.xpInvest, bd("0.00"), investAccounts.get(1), bd("300.00"));
        expCategories = List.of(u.food, u.category("Transporte", CategoryKind.EXPENSE), u.category("Lazer", CategoryKind.EXPENSE));
        incCategories = List.of(u.salary, u.category("Extra", CategoryKind.INCOME));

        Random rnd = new Random(seed);
        List<String> log = new ArrayList<>();
        for (int step = 1; step <= 120; step++) {
            String op = step(rnd);
            log.add(step + ":" + op);
            if (step % 8 == 0) {
                check(opening, seed, step, log);
            }
        }
        check(opening, seed, 120, log);
        System.out.println("QA-PROP seed=" + seed + " txs=" + txs.size() + " voided=" + txs.values().stream().filter(t -> !t.active).count()
                + " violations=" + failures.size());
        assertThat(failures).as("seed " + seed).isEmpty();
    }

    private QaWorld.Actor reissue(QaWorld.Actor actor) throws Exception {
        // o Actor fixou o bearer com o relógio anterior; como o token vale 15 min a partir de iat, basta recriar o usuário com o relógio atual
        return world.new Actor("prop");
    }

    // ---------------------------------------------------------------- operações

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private String money(Random r, int maxCents) {
        return BigDecimal.valueOf(1 + r.nextInt(maxCents)).movePointLeft(2).toPlainString();
    }

    private String date(Random r) {
        int day = 1 + r.nextInt(30);
        return r.nextInt(10) == 0 ? "2026-11-" + String.format("%02d", 1 + r.nextInt(20)) : "2026-10-" + String.format("%02d", day);
    }

    /** Data no mesmo mês de {@code ref} (reembolso e despesa na mesma competência: o consumo por categoria nunca fica negativo). */
    private String sameMonth(Random r, String ref) {
        return ref.substring(0, 8) + String.format("%02d", 1 + r.nextInt(28));
    }

    private String pick(Random r, List<String> l) {
        return l.get(r.nextInt(l.size()));
    }

    private String step(Random r) throws Exception {
        int dice = r.nextInt(100);
        if (dice < 30) {
            return createExpense(r);
        } else if (dice < 40) {
            return createIncome(r);
        } else if (dice < 50) {
            return createTransfer(r);
        } else if (dice < 58) {
            return createInvestment(r);
        } else if (dice < 63) {
            return createAdjustment(r);
        } else if (dice < 73) {
            return createRefund(r);
        } else if (dice < 88) {
            return edit(r);
        } else if (dice < 97) {
            return voidOne(r);
        } else {
            return invalidAttempt(r);
        }
    }

    private M record(JsonNode created, String type, BigDecimal amount, String date, String desc, String account, String account2,
                     String category, String direction, String refundOf) {
        M m = new M();
        m.id = created.get("id").asString();
        m.type = type;
        m.amount = amount;
        m.date = date;
        m.description = desc;
        m.account = account;
        m.account2 = account2;
        m.category = category;
        m.direction = direction;
        m.refundOf = refundOf;
        m.version = created.get("version").asLong();
        txs.put(m.id, m);
        return m;
    }

    private JsonNode tryCreate(String body, String what) throws Exception {
        MvcResult r = u.post(body);
        int s = r.getResponse().getStatus();
        if (s != 201) {
            failures.add(what + " válido recusado: " + s + " " + r.getResponse().getContentAsString() + " <- " + body);
            return null;
        }
        try {
            ContractValidation.conforms().match(r);
        } catch (AssertionError | Exception e) {
            failures.add(what + " resposta fora do contrato: " + e.getMessage().lines().findFirst().orElse(""));
        }
        return world.body(r);
    }

    private String createExpense(Random r) throws Exception {
        String acc = pick(r, cashAccounts);
        String cat = pick(r, expCategories);
        BigDecimal amount = bd(money(r, 50000));
        String date = date(r);
        JsonNode c = tryCreate(QaWorld.expense(acc, cat, amount.toPlainString(), date, "E" + r.nextInt(1000)), "EXPENSE");
        if (c != null) {
            record(c, "EXPENSE", amount, date, "", acc, null, cat, null, null);
        }
        return "expense " + amount;
    }

    private String createIncome(Random r) throws Exception {
        String acc = pick(r, cashAccounts);
        String cat = pick(r, incCategories);
        BigDecimal amount = bd(money(r, 300000));
        String date = date(r);
        JsonNode c = tryCreate(QaWorld.income(acc, cat, amount.toPlainString(), date), "INCOME");
        if (c != null) {
            record(c, "INCOME", amount, date, "", acc, null, cat, null, null);
        }
        return "income " + amount;
    }

    private String createTransfer(Random r) throws Exception {
        String from = pick(r, cashAccounts);
        String to;
        do {
            to = pick(r, cashAccounts);
        } while (to.equals(from));
        BigDecimal amount = bd(money(r, 100000));
        String date = date(r);
        JsonNode c = tryCreate(QaWorld.transfer(from, to, amount.toPlainString(), date), "TRANSFER");
        if (c != null) {
            record(c, "TRANSFER", amount, date, "", from, to, null, null, null);
        }
        return "transfer " + amount;
    }

    private String createInvestment(Random r) throws Exception {
        String cash = pick(r, cashAccounts);
        String inv = pick(r, investAccounts);
        String dir = r.nextBoolean() ? "CONTRIBUTION" : "REDEMPTION";
        BigDecimal amount = bd(money(r, 80000));
        String date = date(r);
        JsonNode c = tryCreate(QaWorld.investment(cash, inv, dir, amount.toPlainString(), date), "INVESTMENT");
        if (c != null) {
            record(c, "INVESTMENT", amount, date, "", cash, inv, null, dir, null);
        }
        return "invest " + dir + " " + amount;
    }

    private String createAdjustment(Random r) throws Exception {
        String acc = pick(r, cashAccounts);
        String dir = r.nextBoolean() ? "INCREASE" : "DECREASE";
        BigDecimal amount = bd(money(r, 5000));
        String date = date(r);
        JsonNode c = tryCreate(QaWorld.adjustment(acc, dir, amount.toPlainString(), date), "ADJUSTMENT");
        if (c != null) {
            record(c, "ADJUSTMENT", amount, date, "", acc, null, null, dir, null);
        }
        return "adjust " + dir + " " + amount;
    }

    private BigDecimal refunded(String originalId, String exceptId) {
        return txs.values().stream().filter(t -> t.active && "REFUND".equals(t.type) && originalId.equals(t.refundOf) && !t.id.equals(exceptId))
                .map(t -> t.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<M> active(String type) {
        return txs.values().stream().filter(t -> t.active && t.type.equals(type)).toList();
    }

    private String createRefund(Random r) throws Exception {
        List<M> originals = active("EXPENSE").stream().filter(e -> bd("0.01").compareTo(e.amount.subtract(refunded(e.id, null))) <= 0).toList();
        if (originals.isEmpty()) {
            return createExpense(r);
        }
        M original = originals.get(r.nextInt(originals.size()));
        BigDecimal cap = original.amount.subtract(refunded(original.id, null));
        BigDecimal amount = cap.multiply(BigDecimal.valueOf(1 + r.nextInt(100))).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.DOWN);
        if (amount.signum() == 0) {
            amount = bd("0.01");
        }
        String acc = pick(r, cashAccounts);
        String date = sameMonth(r, original.date);
        JsonNode c = tryCreate(QaWorld.refund(acc, original.category, original.id, amount.toPlainString(), date), "REFUND");
        if (c != null) {
            record(c, "REFUND", amount, date, "", acc, null, original.category, null, original.id);
        }
        return "refund " + amount + " of " + original.amount;
    }

    private String voidOne(Random r) throws Exception {
        List<M> candidates = txs.values().stream().filter(t -> t.active).toList();
        if (candidates.isEmpty()) {
            return "void none";
        }
        M t = candidates.get(r.nextInt(candidates.size()));
        boolean hasRefunds = "EXPENSE".equals(t.type) && refunded(t.id, null).signum() > 0;
        MvcResult res = mvc.perform(u.req(HttpMethod.POST, "/v1/transactions/" + t.id + "/void", "{\"reason\":\"qa\"}")).andReturn();
        int s = res.getResponse().getStatus();
        if (hasRefunds) {
            if (s != 422) {
                failures.add("estorno de despesa com reembolso ativo devia ser 422 e foi " + s);
            }
            return "void REFUSED(refunds)";
        }
        if (s == 200) {
            t.active = false;
            t.version = world.body(res).get("version").asLong();
        } else {
            failures.add("estorno válido de " + t.type + " recusado: " + s + " " + res.getResponse().getContentAsString());
        }
        return "void " + t.type;
    }

    private String edit(Random r) throws Exception {
        List<M> candidates = txs.values().stream().filter(t -> t.active).toList();
        if (candidates.isEmpty()) {
            return "edit none";
        }
        M t = candidates.get(r.nextInt(candidates.size()));
        BigDecimal newAmount = bd(money(r, 60000));
        String newDate = date(r);
        if ("REFUND".equals(t.type)) {
            newDate = sameMonth(r, txs.get(t.refundOf).date);
        } else if ("EXPENSE".equals(t.type) && refunded(t.id, null).signum() > 0) {
            newDate = sameMonth(r, t.date);
        }
        String body;
        String newAccount = t.account;
        String newCategory = t.category;
        switch (t.type) {
            case "EXPENSE" -> {
                BigDecimal refunds = refunded(t.id, null);
                if (refunds.signum() > 0) {
                    newAmount = refunds.add(bd(money(r, 5000)));  // não pode baixar de Σ reembolsos; categoria travada
                } else {
                    newCategory = pick(r, expCategories);
                }
                newAccount = pick(r, cashAccounts);
                body = QaWorld.expense(newAccount, newCategory, newAmount.toPlainString(), newDate, "ed");
            }
            case "INCOME" -> {
                newAccount = pick(r, cashAccounts);
                newCategory = pick(r, incCategories);
                body = QaWorld.income(newAccount, newCategory, newAmount.toPlainString(), newDate);
            }
            case "TRANSFER" -> {
                String to = t.account2;
                String from = pick(r, cashAccounts);
                if (from.equals(to)) {
                    from = t.account;
                }
                newAccount = from;
                body = QaWorld.transfer(from, to, newAmount.toPlainString(), newDate);
            }
            case "INVESTMENT" -> body = QaWorld.investment(t.account, t.account2, t.direction, newAmount.toPlainString(), newDate);
            case "ADJUSTMENT" -> body = QaWorld.adjustment(t.account, t.direction, newAmount.toPlainString(), newDate);
            default -> {
                M original = txs.get(t.refundOf);
                BigDecimal cap = original.amount.subtract(refunded(original.id, t.id));
                newAmount = cap.multiply(BigDecimal.valueOf(1 + r.nextInt(100))).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.DOWN);
                if (newAmount.signum() == 0) {
                    newAmount = bd("0.01");
                }
                newAccount = pick(r, cashAccounts);
                body = QaWorld.refund(newAccount, t.category, t.refundOf, newAmount.toPlainString(), newDate);
            }
        }
        body = body.replace("}", ",\"version\":" + t.version + "}");
        MvcResult res = mvc.perform(u.req(HttpMethod.PUT, "/v1/transactions/" + t.id, body)).andReturn();
        if (res.getResponse().getStatus() == 200) {
            try {
                ContractValidation.conforms().match(res);
            } catch (AssertionError | Exception e) {
                failures.add("PUT fora do contrato: " + e.getMessage().lines().findFirst().orElse(""));
            }
            JsonNode j = world.body(res);
            t.amount = newAmount;
            t.date = newDate;
            t.account = newAccount;
            t.category = newCategory;
            t.version = j.get("version").asLong();
        } else {
            failures.add("edição válida de " + t.type + " recusada: " + res.getResponse().getStatus() + " " + res.getResponse().getContentAsString() + " <- " + body);
        }
        return "edit " + t.type + " -> " + newAmount;
    }

    /** Operações que o domínio precisa recusar sem mudar nada (o estado é conferido nos checks). */
    private String invalidAttempt(Random r) throws Exception {
        List<M> exp = active("EXPENSE");
        int k = r.nextInt(5);
        String body;
        int expected = 422;
        switch (k) {
            case 0 -> body = QaWorld.transfer(u.nubank, u.nubank, "1.00", "2026-10-10");
            case 1 -> body = QaWorld.transfer(u.nubank, u.xpInvest, "1.00", "2026-10-10");
            case 2 -> body = QaWorld.investment(u.nubank, u.xp, "CONTRIBUTION", "1.00", "2026-10-10");
            case 3 -> body = QaWorld.adjustment(u.xp, "INCREASE", "1.00", "2026-10-10").replace("\"reason\":\"conferência\"", "\"reason\":\"\"");
            default -> {
                if (exp.isEmpty()) {
                    return "invalid skipped";
                }
                M o = exp.get(r.nextInt(exp.size()));
                BigDecimal over = o.amount.subtract(refunded(o.id, null)).add(bd("0.01"));
                body = QaWorld.refund(u.nubank, o.category, o.id, over.toPlainString(), "2026-10-10");
            }
        }
        MvcResult res = u.post(body);
        int s = res.getResponse().getStatus();
        if (k == 3) {
            expected = 400;
        }
        if (s != expected) {
            failures.add("tentativa inválida #" + k + " devia ser " + expected + " e foi " + s + " " + res.getResponse().getContentAsString());
            if (s == 201) {
                M m = new M();
                m.id = world.body(res).get("id").asString();
                m.type = "UNEXPECTED";
                txs.put(m.id, m);
            }
        }
        return "invalid#" + k;
    }

    // ---------------------------------------------------------------- invariantes

    private BigDecimal balanceOf(String account, Map<String, BigDecimal> opening) {
        BigDecimal b = opening.get(account);
        for (M t : txs.values()) {
            if (!t.active || t.date.compareTo(TODAY) > 0) {
                continue;
            }
            switch (t.type) {
                case "EXPENSE" -> b = t.account.equals(account) ? b.subtract(t.amount) : b;
                case "INCOME", "REFUND" -> b = t.account.equals(account) ? b.add(t.amount) : b;
                case "TRANSFER" -> {
                    if (t.account.equals(account)) {
                        b = b.subtract(t.amount);
                    }
                    if (t.account2.equals(account)) {
                        b = b.add(t.amount);
                    }
                }
                case "INVESTMENT" -> {
                    boolean contribution = t.direction.equals("CONTRIBUTION");
                    if (t.account.equals(account)) {
                        b = contribution ? b.subtract(t.amount) : b.add(t.amount);
                    }
                    if (t.account2.equals(account)) {
                        b = contribution ? b.add(t.amount) : b.subtract(t.amount);
                    }
                }
                case "ADJUSTMENT" -> {
                    if (t.account.equals(account)) {
                        b = t.direction.equals("INCREASE") ? b.add(t.amount) : b.subtract(t.amount);
                    }
                }
                default -> {
                }
            }
        }
        return b;
    }

    private static BigDecimal amount(JsonNode money) {
        return new BigDecimal(money.get("amount").asString());
    }

    private BigDecimal modelConsumption(String monthPrefix, String category) {
        BigDecimal sum = BigDecimal.ZERO;
        for (M t : txs.values()) {
            if (!t.active || !t.date.startsWith(monthPrefix) || (category != null && !category.equals(t.category))) {
                continue;
            }
            if (t.type.equals("EXPENSE")) {
                sum = sum.add(t.amount);
            } else if (t.type.equals("REFUND")) {
                sum = sum.subtract(t.amount);
            }
        }
        return sum;
    }

    private BigDecimal modelIncome(String monthPrefix) {
        return txs.values().stream().filter(t -> t.active && t.type.equals("INCOME") && t.date.startsWith(monthPrefix)).map(t -> t.amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void expect(String what, Object expected, Object actual, long seed, int step, List<String> log) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            failures.add("seed " + seed + " passo " + step + ": " + what + " esperado " + expected + " obtido " + actual
                    + " (últimas ops: " + log.subList(Math.max(0, log.size() - 4), log.size()) + ")");
        }
    }

    /** Soma os itens de todas as páginas (paginação sem repetição nem perda). */
    private long pagedItems(String url, long seed, int step, List<String> log) throws Exception {
        long total = 0;
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int page = 1; page < 20; page++) {
            JsonNode slice = u.get(url + "&pageSize=7&page=" + page);
            for (JsonNode item : slice.get("items")) {
                if (!ids.add(item.get("id").asString())) {
                    failures.add("seed " + seed + " passo " + step + ": item repetido entre páginas " + item.get("id").asString());
                }
                total++;
            }
            if (!slice.get("hasNext").asBoolean()) {
                break;
            }
        }
        return total;
    }

    private void check(Map<String, BigDecimal> opening, long seed, int step, List<String> log) throws Exception {
        if (!failures.isEmpty()) {
            return; // o primeiro desvio já invalida a sequência; evita ruído em cascata
        }
        expect("Σ razão ativo", 0L, world.ledgerSum(u.id), seed, step, log);
        expect("grupos de origem desbalanceados", 0L, world.unbalancedActiveGroups(u.id), seed, step, log);
        // estornadas não têm lançamento ativo; ativas têm
        for (M t : txs.values()) {
            if ("UNEXPECTED".equals(t.type)) {
                continue;
            }
            long activePostings = jdbc.queryForObject("select count(*) from ledger_postings where transaction_id = ?::uuid and active", Long.class, t.id);
            if (t.active == (activePostings == 0)) {
                failures.add("seed " + seed + " passo " + step + ": tx " + t.type + " active=" + t.active + " com " + activePostings + " lançamentos ativos");
            }
        }
        // saldo das contas
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal investments = BigDecimal.ZERO;
        JsonNode accounts = u.get("/v1/accounts?pageSize=50");
        for (JsonNode a : accounts.get("items")) {
            String id = a.get("id").asString();
            BigDecimal expected = balanceOf(id, opening);
            expect("saldo da conta " + id, expected.setScale(2), amount(a.get("currentBalance")).setScale(2), seed, step, log);
            if (accountType.get(id).equals("CASH")) {
                cash = cash.add(expected);
            } else {
                investments = investments.add(expected);
            }
        }
        JsonNode summary = u.get("/v1/dashboard/summary?asOf=" + TODAY);
        expect("dashboard.inAccounts", cash.setScale(2), amount(summary.get("balances").get("inAccounts")).setScale(2), seed, step, log);
        expect("dashboard.investments", investments.setScale(2), amount(summary.get("balances").get("investments")).setScale(2), seed, step, log);
        expect("dashboard.netWorth.accounts", cash.add(investments).setScale(2), amount(summary.get("netWorth").get("accounts")).setScale(2), seed, step, log);

        for (String month : List.of("2026-10", "2026-11")) {
            BigDecimal consumption = modelConsumption(month, null).setScale(2);
            BigDecimal income = modelIncome(month).setScale(2);
            JsonNode entries = u.get("/v1/entries?competence=" + month + "&lens=CONSUMPTION&pageSize=50");
            expect("entries consumo " + month, consumption, amount(entries.get("totals").get("total")).setScale(2), seed, step, log);
            BigDecimal byCat = BigDecimal.ZERO;
            for (JsonNode c : entries.get("totals").get("byCategory")) {
                BigDecimal v = amount(c.get("amount"));
                byCat = byCat.add(v);
                if (v.signum() < 0) {
                    failures.add("seed " + seed + " passo " + step + ": consumo negativo na categoria " + c.get("category").get("name").asString() + " " + v);
                }
                expect("consumo da categoria " + c.get("category").get("id").asString(), modelConsumption(month, c.get("category").get("id").asString()).setScale(2),
                        v.setScale(2), seed, step, log);
            }
            expect("Σ byCategory = total " + month, consumption, byCat.setScale(2), seed, step, log);
            JsonNode incomeEntries = u.get("/v1/entries?competence=" + month + "&lens=INCOME&pageSize=50");
            expect("entries receita " + month, income, amount(incomeEntries.get("totals").get("total")).setScale(2), seed, step, log);

            // dashboard do mês = entries; cashFlow do dashboard = totals.cashFlow da lista
            String last = month.equals("2026-10") ? "2026-10-31" : "2026-11-30";
            JsonNode dash = u.get("/v1/dashboard/summary?asOf=" + last).get("month");
            expect("dashboard consumo " + month, consumption, amount(dash.get("consumption")).setScale(2), seed, step, log);
            expect("dashboard receita " + month, income, amount(dash.get("income")).setScale(2), seed, step, log);
            JsonNode list = u.get("/v1/transactions?from=" + month + "-01&to=" + last + "&pageSize=50");
            for (String k : List.of("in", "out", "net")) {
                expect("cashFlow." + k + " lista × dashboard " + month, amount(dash.get("cashFlow").get(k)).setScale(2),
                        amount(list.get("totals").get("cashFlow").get(k)).setScale(2), seed, step, log);
            }
            long expectedCount = txs.values().stream().filter(t -> t.active && t.date.startsWith(month)).count();
            long expectedAll = txs.values().stream().filter(t -> t.date.startsWith(month)).count();
            JsonNode activeList = u.get("/v1/transactions?from=" + month + "-01&to=" + last + "&status=ACTIVE&pageSize=50");
            expect("count ACTIVE " + month, expectedCount, activeList.get("totals").get("count").asLong(), seed, step, log);
            expect("itens VOIDED " + month, expectedAll - expectedCount, pagedItems("/v1/transactions?from=" + month + "-01&to=" + last + "&status=VOIDED", seed, step, log), seed, step, log);
            expect("itens ACTIVE " + month, expectedCount, pagedItems("/v1/transactions?from=" + month + "-01&to=" + last + "&status=ACTIVE", seed, step, log), seed, step, log);
            expect("itens sem status (padrão ACTIVE) " + month, expectedCount, pagedItems("/v1/transactions?from=" + month + "-01&to=" + last, seed, step, log), seed, step, log);
        }
        // sem dupla contagem: Σ postings EXPENSE ativos = Σ consumo do modelo (todos os meses)
        BigDecimal expenseBook = jdbc.queryForObject("select coalesce(sum(amount_cents),0) from ledger_postings where user_id = ? and active and book_type = 'EXPENSE'",
                BigDecimal.class, u.id).movePointLeft(2).setScale(2);
        expect("Σ livro EXPENSE", modelConsumption("2026-", null).setScale(2), expenseBook, seed, step, log);
    }
}
