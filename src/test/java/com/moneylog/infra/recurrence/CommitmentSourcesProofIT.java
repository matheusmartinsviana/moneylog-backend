package com.moneylog.infra.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;

/**
 * T81: prova cruzada de que cada compromisso entra <b>uma vez</b> entre todas as fontes (faturas de cartão, dívidas e recorrências) em
 * {@code /v1/dashboard/upcoming} e no {@code summary}, e de que a lista, o dashboard e a tela da pessoa concordam. Cenário completo
 * (relógio 10/10/2026): fatura do Roxinho de 1.283,00 vence 05/11; Internet de 119,00 todo dia 10 com início 10/10; o João deve 150,00 com
 * previsão 05/11; eu devo 200,00 à Maria, sem data.
 */
@DisplayName("Compromissos sem dupla contagem: cartões, faturas, dívidas e recorrências (T81)")
class CommitmentSourcesProofIT extends E4ItBase {

    private static final String HORIZON_END = "2027-10-10";

    private String internetId;
    private String joaoDebtId;
    private String mariaDebtId;

    private void scenario() throws Exception {
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1200.00\",\"date\":\"2026-10-10\",\"description\":\"Notebook\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + food + "\",\"installments\":6}");
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1083.00\",\"date\":\"2026-10-10\",\"description\":\"Mercado\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + food + "\",\"installments\":1}");
        internetId = createdBody(post("/v1/recurrences", "{\"type\":\"EXPENSE\",\"amount\":\"119.00\",\"description\":\"Internet\",\"categoryId\":\"" + bills
                + "\",\"accountId\":\"" + nubank + "\",\"method\":\"PIX\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-10-10\"}")).get("id").asString();
        joaoDebtId = loan("150.00", "2026-10-10", joao, "2026-11-05").get("debt").get("id").asString();
        mariaDebtId = createdBody(post("/v1/debts", "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"200.00\","
                + "\"date\":\"2026-10-10\",\"description\":\"Almoço pago pela Maria\"}")).get("id").asString();
    }

    private String streaming() throws Exception {
        return createdBody(post("/v1/recurrences", "{\"type\":\"CARD_PURCHASE\",\"amount\":\"49.90\",\"description\":\"Streaming\",\"categoryId\":\""
                + subscriptions + "\",\"cardId\":\"" + card + "\",\"frequency\":\"MONTHLY\",\"startDate\":\"2026-10-15\"}")).get("id").asString();
    }

    private void realize(String recurrence, String date) throws Exception {
        mockMvc.perform(post("/v1/recurrences/" + recurrence + "/occurrences/" + date + "/realize", "{}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms());
    }

    private static BigDecimal amount(JsonNode money) {
        return new BigDecimal(money.get("amount").asString());
    }

    /** Σ (valor) dos itens de um {@code kind} em {@code /upcoming}. */
    private static BigDecimal sumOf(JsonNode items, String... kinds) {
        BigDecimal sum = BigDecimal.ZERO;
        Set<String> wanted = Set.of(kinds);
        for (JsonNode item : items) {
            if (wanted.contains(item.get("kind").asString())) {
                sum = sum.add(amount(item.get("amount")));
            }
        }
        return sum;
    }

    /** Σ (valor) dos itens de um {@code kind} e sentido em {@code /upcoming}. */
    private static BigDecimal sumDirected(JsonNode items, String kind, String direction) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode item : items) {
            if (item.get("kind").asString().equals(kind) && item.get("direction").asString().equals(direction)) {
                sum = sum.add(amount(item.get("amount")));
            }
        }
        return sum;
    }

    /**
     * Oráculo independente das movimentações futuras (S-258), direto do razão: o efeito líquido, por movimentação e data, nas contas de caixa
     * ({@code CHECKING}, {@code SAVINGS}, {@code WALLET}) dos lançamentos ativos com data depois de hoje e até o fim do horizonte, no sentido pedido.
     */
    private BigDecimal scheduledFromLedger(String today, boolean outgoing) {
        Long cents = jdbc.queryForObject("""
                SELECT coalesce(sum(abs(net)), 0) FROM (
                    SELECT sum(lp.amount_cents) AS net FROM ledger_postings lp JOIN accounts a ON a.id = lp.book_id
                    WHERE lp.user_id = ? AND lp.active AND lp.source_type = 'TRANSACTION' AND lp.book_type = 'ACCOUNT' AND a.type <> 'INVESTMENT'
                      AND lp.effective_date > ?::date AND lp.effective_date <= ?::date
                    GROUP BY lp.transaction_id, lp.effective_date) t
                WHERE (? AND net < 0) OR (NOT ? AND net > 0)""", Long.class, userId, today, HORIZON_END, outgoing, outgoing);
        return BigDecimal.valueOf(cents, 2);
    }

    /**
     * A decomposição: cada fonte soma o que ela mesma diz (faturas = Σ outstanding das faturas não pagas com vencimento até o fim do
     * horizonte; recorrências = Σ ocorrências pendentes de saída; dívidas = Σ saldo das PAYABLE datadas em aberto), o total de saídas é a
     * soma das três e nenhum compromisso aparece duas vezes.
     */
    private void assertNoDoubleCounting(String label) throws Exception {
        JsonNode upcoming = upcoming("from=2026-10-10&to=" + HORIZON_END);
        JsonNode items = upcoming.get("items");

        BigDecimal invoices = BigDecimal.ZERO;
        JsonNode invoiceList = okBody(get("/v1/cards/" + card + "/invoices?pageSize=50"));
        for (JsonNode invoice : invoiceList.get("items")) {
            String statusName = invoice.get("status").asString();
            if (!statusName.equals("PAID") && amount(invoice.get("outstanding")).signum() > 0 && invoice.get("dueDate").asString().compareTo(HORIZON_END) <= 0) {
                invoices = invoices.add(amount(invoice.get("outstanding")));
            }
        }
        BigDecimal pendingOut = BigDecimal.ZERO;
        JsonNode occurrences = okBody(get("/v1/recurrences/occurrences?status=PENDING&to=" + HORIZON_END + "&pageSize=50"));
        for (JsonNode occurrence : occurrences.get("items")) {
            if (occurrence.get("direction").asString().equals("OUT")) {
                pendingOut = pendingOut.add(amount(occurrence.get("amount")));
            }
        }
        BigDecimal payables = BigDecimal.ZERO;
        for (JsonNode debt : okBody(get("/v1/debts?direction=PAYABLE&pageSize=50")).get("items")) {
            String statusName = debt.get("status").asString();
            if ((statusName.equals("OPEN") || statusName.equals("PARTIALLY_SETTLED")) && !debt.get("expectedDate").isNull()
                    && debt.get("expectedDate").asString().compareTo(HORIZON_END) <= 0) {
                payables = payables.add(amount(debt.get("outstanding")));
            }
        }
        assertThat(sumOf(items, "INVOICE", "INVOICE_FORECAST")).as(label + ": faturas").isEqualByComparingTo(invoices);
        assertThat(sumOf(items, "RECURRENCE").subtract(sumOfIn(items))).as(label + ": recorrências").isEqualByComparingTo(pendingOut);
        assertThat(sumOf(items, "DEBT_PAYABLE")).as(label + ": dívidas a pagar").isEqualByComparingTo(payables);
        // S-258: movimentações ativas com data futura são compromissos próprios, calculados à parte direto do razão
        String today = summary().get("asOf").asString();
        BigDecimal scheduledOut = scheduledFromLedger(today, true);
        assertThat(sumDirected(items, "SCHEDULED_TRANSACTION", "OUT")).as(label + ": movimentações futuras (saída)").isEqualByComparingTo(scheduledOut);
        assertThat(sumDirected(items, "SCHEDULED_TRANSACTION", "IN")).as(label + ": movimentações futuras (entrada)").isEqualByComparingTo(scheduledFromLedger(today, false));
        assertThat(amount(upcoming.get("totals").get("out"))).as(label + ": Σ saídas = faturas + ocorrências + dívidas datadas + movimentações futuras")
                .isEqualByComparingTo(invoices.add(pendingOut).add(payables).add(scheduledOut));

        Set<String> keys = new HashSet<>();
        for (JsonNode item : items) {
            String key = item.get("kind").asString() + "|" + item.get("refId").asString() + "|" + item.get("dueDate").asString();
            assertThat(keys.add(key)).as(label + ": item duplicado " + key).isTrue();
        }
        // o aninhado nunca soma de novo: a fatura vale o seu outstanding, não o das parcelas somadas ao seu valor
        for (JsonNode item : items) {
            assertThat(item.get("kind").asString()).isNotEqualTo("INSTALLMENT");
        }
    }

    /** Receitas previstas dentro das recorrências (nenhuma neste cenário): zero. */
    private static BigDecimal sumOfIn(JsonNode items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode item : items) {
            if (item.get("kind").asString().equals("RECURRENCE") && item.get("direction").asString().equals("IN")) {
                sum = sum.add(amount(item.get("amount")));
            }
        }
        return sum;
    }

    @Test
    @DisplayName("CA1 e CA2: /upcoming de 10/10 a 10/11 traz Internet 10/10, fatura 05/11 (1.283,00 com parcelas aninhadas), cobrança do João 05/11 (IN 150) e Internet 10/11; a Maria fica em undated; committed.d30 = 1.402,00 e as entradas não abatem")
    void deveSomarCadaCompromissoUmaVez() throws Exception {
        scenario();
        JsonNode upcoming = upcoming("from=2026-10-10&to=2026-11-10");
        JsonNode items = upcoming.get("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0).get("kind").asString()).isEqualTo("RECURRENCE");
        assertThat(items.get(0).get("dueDate").asString()).isEqualTo("2026-10-10");
        assertThat(items.get(0).get("bucket").asString()).isEqualTo("TODAY");
        assertThat(items.get(1).get("kind").asString()).isEqualTo("INVOICE_FORECAST");
        assertThat(items.get(1).get("dueDate").asString()).isEqualTo("2026-11-05");
        assertThat(money(items.get(1), "amount")).isEqualTo("1283.00");
        assertThat(items.get(1).get("items")).hasSize(2);
        assertThat(items.get(1).get("items")).allSatisfy(child -> assertThat(child.get("informational").asBoolean()).isTrue());
        assertThat(items.get(2).get("kind").asString()).isEqualTo("DEBT_RECEIVABLE");
        assertThat(items.get(2).get("direction").asString()).isEqualTo("IN");
        assertThat(money(items.get(2), "amount")).isEqualTo("150.00");
        assertThat(items.get(3).get("kind").asString()).isEqualTo("RECURRENCE");
        assertThat(items.get(3).get("dueDate").asString()).isEqualTo("2026-11-10");
        // a dívida da Maria não tem data: undated e fora dos horizontes (S-145)
        assertThat(upcoming.get("undated")).hasSize(1);
        assertThat(upcoming.get("undated").get(0).get("refId").asString()).isEqualTo(mariaDebtId);
        assertThat(money(upcoming.get("totals"), "out")).isEqualTo("1521.00");
        assertThat(money(upcoming.get("totals"), "in")).isEqualTo("150.00");

        JsonNode summary = summary();
        JsonNode committed = summary.get("committed");
        // d30 vai até 09/11 (hoje + 30): Internet 10/10 + fatura de 05/11; a Internet de 10/11 é o dia 31 e a fatura de 05/12 também não entra
        assertThat(money(committed, "d30")).isEqualTo("1402.00");
        assertThat(money(committed, "d60")).isEqualTo("1721.00");
        // as entradas (João) não abatem o comprometido, mas a projeção as soma como entrada prevista
        assertThat(money(summary.get("projected"), "d30")).isEqualTo("18598.00");
        assertThat(money(summary.get("balances"), "available")).isEqualTo("18448.00");
        assertNoDoubleCounting("cenário");
    }

    @Test
    @DisplayName("CA3: realizar a Internet de 10/10 derruba committed.d30 em 119,00 e inAccounts em 119,00, o disponível não muda e month.consumption sobe 119,00 uma vez")
    void deveMoverDoCompromissoParaOConsumo() throws Exception {
        scenario();
        JsonNode before = summary();
        realize(internetId, "2026-10-10");
        JsonNode after = summary();
        assertThat(amount(before.get("committed").get("d30")).subtract(amount(after.get("committed").get("d30")))).isEqualByComparingTo("119.00");
        assertThat(amount(before.get("balances").get("inAccounts")).subtract(amount(after.get("balances").get("inAccounts")))).isEqualByComparingTo("119.00");
        assertThat(money(after.get("balances"), "available")).isEqualTo(money(before.get("balances"), "available"));
        assertThat(amount(after.get("month").get("consumption")).subtract(amount(before.get("month").get("consumption")))).isEqualByComparingTo("119.00");
        assertThat(upcoming("from=2026-10-10&to=2026-10-31").get("items")).extracting(i -> i.get("kind").asString()).doesNotContain("RECURRENCE");
        assertNoDoubleCounting("depois de realizar");
        // estornar a movimentação reabre a ocorrência: o compromisso volta e o consumo some (S-49)
        String tx = jdbc.queryForObject("SELECT id::text FROM transactions WHERE recurrence_id = ?::uuid AND status = 'ACTIVE'", String.class, internetId);
        mockMvc.perform(post("/v1/transactions/" + tx + "/void", voidBody("errado"))).andExpect(status().isOk());
        assertThat(money(summary().get("committed"), "d30")).isEqualTo(money(before.get("committed"), "d30"));
        assertThat(money(summary().get("month"), "consumption")).isEqualTo(money(before.get("month"), "consumption"));
        assertNoDoubleCounting("depois de estornar");
    }

    @Test
    @DisplayName("CA4: o João paga 150,00: a cobrança some, inAccounts +150, month.income não muda (não é renda) e month.received sobe 150")
    void aCobrancaQuitadaNaoEReceita() throws Exception {
        scenario();
        JsonNode before = summary();
        created(received("150.00", "2026-10-10", joaoDebtId));
        JsonNode after = summary();
        assertThat(upcoming("from=2026-10-10&to=2026-11-10").get("items")).extracting(i -> i.get("kind").asString()).doesNotContain("DEBT_RECEIVABLE");
        assertThat(amount(after.get("balances").get("inAccounts")).subtract(amount(before.get("balances").get("inAccounts")))).isEqualByComparingTo("150.00");
        assertThat(money(after.get("month"), "income")).isEqualTo(money(before.get("month"), "income"));
        assertThat(amount(after.get("month").get("received")).subtract(amount(before.get("month").get("received")))).isEqualByComparingTo("150.00");
        assertThat(money(after.get("month"), "consumption")).isEqualTo(money(before.get("month"), "consumption"));
        JsonNode joaoView = person(joao, true);
        assertThat(money(joaoView, "net")).isEqualTo("0.00");
        assertNoDoubleCounting("depois de receber");
    }

    @Test
    @DisplayName("CA5: matriz de 13 estados (realizar, pular, cartão, quitar, perdoar, estornar, comprar, desativar, encerrar): Σ upcoming OUT = faturas + ocorrências pendentes + dívidas a pagar datadas, sem item duplicado")
    void deveFecharAMatrizDeCombinacoes() throws Exception {
        scenario();
        String streaming = streaming();
        assertNoDoubleCounting("0 base");
        realize(internetId, "2026-10-10");
        assertNoDoubleCounting("1 Internet 10/10 realizada");
        mockMvc.perform(post("/v1/recurrences/" + internetId + "/occurrences/2026-11-10/skip", "")).andExpect(status().isOk());
        assertNoDoubleCounting("2 Internet 10/11 pulada");
        realize(streaming, "2026-10-15");
        // a ocorrência de cartão saiu das previsões e a fatura passou a carregar os 49,90 (sem dupla contagem)
        assertThat(upcoming("from=2026-10-10&to=2026-11-10").get("items")).extracting(i -> i.get("kind").asString() + i.get("dueDate").asString())
                .doesNotContain("RECURRENCE2026-10-15");
        assertNoDoubleCounting("3 assinatura no cartão realizada");
        String pedro = person("Pedro");
        String pedroDebt = createdBody(post("/v1/debts", "{\"personId\":\"" + pedro + "\",\"direction\":\"PAYABLE\",\"principal\":\"300.00\","
                + "\"date\":\"2026-10-10\",\"expectedDate\":\"2026-11-20\",\"description\":\"Empréstimo do Pedro\"}")).get("id").asString();
        assertNoDoubleCounting("4 dívida a pagar datada");
        created(payment("100.00", "2026-10-10", pedroDebt));
        assertNoDoubleCounting("5 dívida paga em parte");
        created(received("50.00", "2026-10-10", joaoDebtId));
        assertNoDoubleCounting("6 João pagou parte");
        created(received("100.00", "2026-10-10", joaoDebtId));
        assertNoDoubleCounting("7 João quitou");
        mockMvc.perform(post("/v1/debts/" + pedroDebt + "/cancel", voidBody("perdoou"))).andExpect(status().isOk());
        assertNoDoubleCounting("8 dívida perdoada");
        String cardTx = jdbc.queryForObject("SELECT id::text FROM transactions WHERE recurrence_id = ?::uuid AND status = 'ACTIVE'", String.class, streaming);
        mockMvc.perform(post("/v1/transactions/" + cardTx + "/void", voidBody("lancei errado"))).andExpect(status().isOk());
        assertNoDoubleCounting("9 compra recorrente estornada (a ocorrência volta)");
        created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"500.00\",\"date\":\"2026-10-10\",\"description\":\"Sapato\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + food + "\",\"installments\":1}");
        assertNoDoubleCounting("10 nova compra no cartão");
        mockMvc.perform(post("/v1/recurrences/" + internetId + "/deactivate", "")).andExpect(status().isOk());
        assertNoDoubleCounting("11 Internet desativada");
        realize(streaming, "2026-10-15");
        assertNoDoubleCounting("12 assinatura realizada de novo");
        mockMvc.perform(post("/v1/recurrences/" + streaming + "/end", "{\"endDate\":\"2026-11-14\"}")).andExpect(status().isOk());
        assertNoDoubleCounting("13 assinatura encerrada");
        assertThat(ledgerTotal()).isZero();
    }

    @Test
    @DisplayName("T81 + S-258: matriz com movimentações futuras (despesa, receita, pagamento de fatura e de dívida, ocorrência realizada adiantada, estorno): Σ saídas do /upcoming = faturas + ocorrências + dívidas datadas + movimentações futuras, e 'em contas + entradas − saídas' não muda quando o relógio avança e as movimentações viram caixa")
    void deveFecharAMatrizComMovimentacoesFuturas() throws Exception {
        scenario();
        String pedro = person("Pedro");
        String pedroDebt = createdBody(post("/v1/debts", "{\"personId\":\"" + pedro + "\",\"direction\":\"PAYABLE\",\"principal\":\"300.00\","
                + "\"date\":\"2026-10-10\",\"expectedDate\":\"2026-11-20\",\"description\":\"Empréstimo do Pedro\"}")).get("id").asString();
        assertNoDoubleCounting("0 base");
        JsonNode future = created("{\"type\":\"EXPENSE\",\"amount\":\"1000.00\",\"date\":\"2026-10-15\",\"description\":\"Aluguel\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + bills + "\",\"method\":\"PIX\"}");
        assertNoDoubleCounting("1 despesa futura");
        // o resumo mensal segue a competência: a despesa de 15/10 é consumo de outubro (no resumo e no dashboard, o mesmo número), mesmo antes de ser caixa
        JsonNode october = okBody(get("/v1/summaries/2026-10"));
        assertThat(money(october, "expenses")).isEqualTo(money(summary().get("month"), "consumption"));
        assertThat(amount(october.get("expenses")).subtract(amount(okBody(get("/v1/summaries/2026-09")).get("expenses")))).isGreaterThanOrEqualTo(new BigDecimal("1000.00"));
        created("{\"type\":\"INCOME\",\"amount\":\"2500.00\",\"date\":\"2026-10-20\",\"description\":\"Salário\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + salary + "\",\"method\":\"PIX\"}");
        assertNoDoubleCounting("2 receita futura");
        created(payment("100.00", "2026-10-25", pedroDebt));
        assertNoDoubleCounting("3 pagamento de dívida futuro (a dívida já cai 100,00)");
        String invoiceId = com.moneylog.core.card.domain.InvoiceIds.of(UUID.fromString(card), java.time.LocalDate.of(2026, 10, 25)).toString();
        mockMvc.perform(post("/v1/invoices/" + invoiceId + "/payments", "{\"accountId\":\"" + nubank + "\",\"amount\":\"400.00\",\"date\":\"2026-11-03\"}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(ContractValidation.conforms());
        assertNoDoubleCounting("4 pagamento parcial de fatura futuro");
        realize(internetId, "2026-10-10");
        mockMvc.perform(post("/v1/recurrences/" + internetId + "/occurrences/2026-11-10/realize", "{}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms());
        assertNoDoubleCounting("5 ocorrência de 10/11 realizada adiantada (a pendente sai e a movimentação entra)");
        assertThat(upcoming("from=2026-10-01&to=" + HORIZON_END).get("items")).filteredOn(i -> i.get("kind").asString().equals("RECURRENCE"))
                .extracting(i -> i.get("dueDate").asString()).doesNotContain("2026-11-10");
        mockMvc.perform(post("/v1/transactions/" + future.get("id").asString() + "/void", voidBody("engano"))).andExpect(status().isOk());
        assertNoDoubleCounting("6 despesa futura estornada");

        // o relógio avança: as movimentações viram caixa e o total (em contas + entradas − saídas) não muda
        BigDecimal reference = totalOf(summary());
        for (String day : new String[] {"2026-10-16T15:00:00Z", "2026-10-21T15:00:00Z", "2026-10-26T15:00:00Z", "2026-11-04T15:00:00Z", "2026-11-11T15:00:00Z"}) {
            advanceTo(java.time.Instant.parse(day));
            assertThat(totalOf(summary())).as("hoje " + day + ": nada conta duas vezes nem some").isEqualByComparingTo(reference);
            assertNoDoubleCounting("clock " + day);
        }
        assertThat(ledgerTotal()).isZero();
    }

    /** Em contas + Σ entradas − Σ saídas de TODOS os compromissos datados (o horizonte de 1 ano cobre o cenário). */
    private BigDecimal totalOf(JsonNode summary) throws Exception {
        JsonNode upcoming = upcoming("from=2026-10-01&to=" + HORIZON_END);
        return amount(summary.get("balances").get("inAccounts")).add(amount(upcoming.get("totals").get("in"))).subtract(amount(upcoming.get("totals").get("out")));
    }

    @Test
    @DisplayName("CA6: com divisão de despesa e quitação no meio, totals.cashFlow da lista = month.cashFlow, totals das lançamentos por competência = month.consumption e byCategory soma o total")
    void deveFecharAListaComODashboard() throws Exception {
        scenario();
        created("{\"type\":\"EXPENSE\",\"amount\":\"200.00\",\"date\":\"2026-10-10\",\"description\":\"Jantar\",\"accountId\":\"" + nubank + "\",\"categoryId\":\""
                + food + "\",\"method\":\"PIX\",\"shares\":[{\"amount\":\"100.00\",\"newDebt\":{\"personId\":\"" + maria + "\"}}]}");
        created(received("40.00", "2026-10-10", joaoDebtId));
        realize(internetId, "2026-10-10");
        createdBody(post("/v1/debts", "{\"personId\":\"" + joao + "\",\"direction\":\"PAYABLE\",\"principal\":\"30.00\",\"date\":\"2026-10-10\","
                + "\"description\":\"Café pago pelo João\",\"categoryId\":\"" + food + "\"}"));

        JsonNode month = summary().get("month");
        JsonNode list = okBody(get("/v1/transactions?from=2026-10-01&to=2026-10-31&pageSize=50"));
        JsonNode cashFlow = list.get("totals").get("cashFlow");
        assertThat(money(cashFlow, "in")).isEqualTo(money(month.get("cashFlow"), "in"));
        assertThat(money(cashFlow, "out")).isEqualTo(money(month.get("cashFlow"), "out"));
        assertThat(money(cashFlow, "net")).isEqualTo(money(month.get("cashFlow"), "net"));

        JsonNode entries = okBody(get("/v1/entries?competence=2026-10&pageSize=50"));
        assertThat(money(entries.get("totals"), "total")).isEqualTo(money(month, "consumption"));
        BigDecimal byCategory = BigDecimal.ZERO;
        for (JsonNode category : entries.get("totals").get("byCategory")) {
            byCategory = byCategory.add(amount(category.get("amount")));
        }
        assertThat(byCategory).isEqualByComparingTo(amount(month.get("consumption")));
        // as linhas: as da lista (por movimentação) + as de dívida de consumo somam o total
        BigDecimal lines = BigDecimal.ZERO;
        for (JsonNode item : entries.get("items")) {
            lines = lines.add(amount(item.get("amount")));
        }
        for (JsonNode item : entries.get("debtItems")) {
            lines = lines.add(amount(item.get("amount")));
        }
        assertThat(lines).isEqualByComparingTo(amount(month.get("consumption")));
        assertThat(entries.get("debtItems")).hasSize(1);
        assertThat(money(month, "debtConsumption")).isEqualTo("30.00");
        // consumo = pago + cartão − reembolso + dívida
        BigDecimal identity = amount(month.get("expensesPaid").get("total")).add(amount(month.get("credit"))).subtract(amount(month.get("refunds")))
                .add(amount(month.get("debtConsumption")));
        assertThat(identity).isEqualByComparingTo(amount(month.get("consumption")));
        // a tela da pessoa concorda com as dívidas: João deve 30 (passivo) e tem 110 a receber depois de pagar 40
        JsonNode joaoView = person(joao, true);
        assertThat(money(joaoView, "net")).isEqualTo("80.00");
        assertThat(ledgerTotal()).isZero();
    }
}
