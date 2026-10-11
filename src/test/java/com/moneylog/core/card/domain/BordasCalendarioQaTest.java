package com.moneylog.core.card.domain;

import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;

@DisplayName("Bordas de data do cartão (T12, QA)")
class BordasCalendarioQaTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Category ALIMENTACAO = CategoryTestFactory.umaCategoriaDeDespesa();

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static InvoiceCalendar calendario(int closing, int due) {
        return CreditCardTestFactory.umCartao(closing, due).calendar();
    }

    @ParameterizedTest(name = "fecha {0}, vence {1}: compra {2} → fecha {3}, vence {4}, ref {5}")
    @CsvSource({
            "31, 10, 2027-02-27, 2027-02-28, 2027-03-10, 2027-03",
            "31, 10, 2027-02-28, 2027-03-31, 2027-04-10, 2027-04",
            "31, 10, 2028-02-28, 2028-02-29, 2028-03-10, 2028-03",
            "31, 10, 2028-02-29, 2028-03-31, 2028-04-10, 2028-04",
            "31, 10, 2027-04-30, 2027-05-31, 2027-06-10, 2027-06",
            "31, 10, 2026-12-30, 2026-12-31, 2027-01-10, 2027-01",
            "31, 10, 2026-12-31, 2027-01-31, 2027-02-10, 2027-02",
            "29, 8, 2027-02-28, 2027-03-29, 2027-04-08, 2027-04",
            "29, 8, 2028-02-28, 2028-02-29, 2028-03-08, 2028-03",
            "30, 8, 2027-02-27, 2027-02-28, 2027-03-08, 2027-03",
            "30, 8, 2027-01-30, 2027-02-28, 2027-03-08, 2027-03",
            "25, 5, 2026-12-25, 2027-01-25, 2027-02-05, 2027-02",
            "25, 5, 2026-12-24, 2026-12-25, 2027-01-05, 2027-01",
            "5, 15, 2026-12-31, 2027-01-05, 2027-01-15, 2027-01",
            "20, 31, 2027-02-10, 2027-02-20, 2027-02-28, 2027-02",
            "10, 31, 2027-04-01, 2027-04-10, 2027-04-30, 2027-04",
    })
    @DisplayName("compra perto do fechamento cai na fatura certa")
    void deveAlocarNasBordas(int closing, int due, String purchase, String expectedClosing, String expectedDue, String ref) {
        InvoicePeriod period = calendario(closing, due).periodFor(d(purchase));
        assertThat(period.closingDate()).isEqualTo(d(expectedClosing));
        assertThat(period.dueDate()).isEqualTo(d(expectedDue));
        assertThat(period.referenceMonth()).isEqualTo(YearMonth.parse(ref));
        assertThat(period.contains(d(purchase))).isTrue();
    }

    @Test
    @DisplayName("parcelas de compra em 31/01 com fechamento 31: competência e data efetiva com clamp (28/02, 31/03...)")
    void deveFazerClampDasParcelas() {
        var card = CreditCardTestFactory.umCartao(31, 10);
        Transaction t = compraNoCalendario(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000aa001"), USER_ID,
                d("2027-01-31"), "TV"), Money.of("1000.00"), card, ALIMENTACAO, 4);
        List<TransactionInstallment> i = t.installments();
        assertThat(i).extracting(TransactionInstallment::effectiveDate)
                .containsExactly(d("2027-01-31"), d("2027-02-28"), d("2027-03-31"), d("2027-04-30"));
        assertThat(i).extracting(TransactionInstallment::invoiceClosingDate)
                .containsExactly(d("2027-02-28"), d("2027-03-31"), d("2027-04-30"), d("2027-05-31"));
        assertThat(i).extracting(TransactionInstallment::amount)
                .containsExactly(Money.of("250.00"), Money.of("250.00"), Money.of("250.00"), Money.of("250.00"));
    }

    @Test
    @DisplayName("0,01 em 1x é aceito e 0,03 em 3x vira 0,01 cada; resto vai para a 1ª parcela")
    void deveAceitarCentavo() {
        var card = CreditCardTestFactory.umCartao();
        Transaction um = compraNoCalendario(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000aa002"), USER_ID,
                d("2026-10-10"), "chiclete"), Money.of("0.01"), card, ALIMENTACAO, 1);
        assertThat(um.installments()).singleElement().extracting(TransactionInstallment::amount).isEqualTo(Money.of("0.01"));
        Transaction resto = compraNoCalendario(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000aa003"), USER_ID,
                d("2026-10-10"), "x"), Money.of("100.00"), card, ALIMENTACAO, 7);
        assertThat(resto.installments().getFirst().amount()).isEqualTo(Money.of("14.32"));
        assertThat(resto.installments().get(6).amount()).isEqualTo(Money.of("14.28"));
    }

    @Test
    @DisplayName("fuso America/Sao_Paulo: 23h30 de 24/10 em SP já é 25/10 em UTC, mas a compra fica na fatura que fecha em 25/10")
    void deveUsarADataLocalDeSaoPaulo() {
        Instant instante = ZonedDateTime.of(2026, 10, 24, 23, 30, 0, 0, SP).toInstant();
        assertThat(instante.atZone(ZoneId.of("UTC")).toLocalDate()).isEqualTo(d("2026-10-25"));
        LocalDate hojeSp = new ClockTimeProvider(java.time.Clock.fixed(instante, SP)).today();
        assertThat(hojeSp).isEqualTo(d("2026-10-24"));
        assertThat(calendario(25, 5).periodFor(hojeSp).closingDate()).isEqualTo(d("2026-10-25"));
    }

    @Test
    @DisplayName("propriedade: calendário contíguo e coerente para todos os dias 1..31 × 1..31, 2027–2029")
    void deveSerContiguoParaTodosOsDias() {
        Random random = new Random(20261004L);
        for (int closing = 1; closing <= 31; closing++) {
            for (int due = 1; due <= 31; due++) {
                InvoiceCalendar cal = calendario(closing, due);
                for (int k = 0; k < 40; k++) {
                    LocalDate date = d("2027-01-01").plusDays(random.nextInt(3 * 365));
                    InvoicePeriod p = cal.periodFor(date);
                    String ctx = closing + "/" + due + " " + date;
                    assertThat(p.contains(date)).as(ctx).isTrue();
                    assertThat(p.closingDate()).as(ctx).isAfter(date);
                    assertThat(YearMonth.from(p.closingDate())).as(ctx).isBetween(YearMonth.from(date), YearMonth.from(date).plusMonths(1));
                    assertThat(cal.next(p).periodStart()).as(ctx).isEqualTo(p.closingDate());
                    assertThat(p.referenceMonth()).as(ctx).isEqualTo(YearMonth.from(p.dueDate()));
                    assertThat(p.dueDate()).as(ctx).isAfter(p.closingDate());
                }
            }
        }
    }

    @ParameterizedTest(name = "fecha {0}, vence {1} em {2}")
    @CsvSource({"30, 31, 2027-02", "29, 30, 2027-02", "29, 31, 2028-02", "28, 29, 2027-02", "30, 31, 2027-04"})
    @DisplayName("BUG-QA-01: o vencimento deveria ser estritamente posterior ao fechamento")
    void deveVencerDepoisDoFechamento(int closing, int due, String month) {
        InvoicePeriod p = calendario(closing, due).periodClosingIn(YearMonth.parse(month));
        assertThat(p.dueDate()).isAfter(p.closingDate());
    }

    @Test
    @DisplayName("BUG-QA-02: ocorrência de cartão realizada depois de a fatura do ciclo ser paga deve ir para a fatura corrente")
    void naoDeveRealizarRecorrenciaEmFaturaPaga() {
        var card = CreditCardTestFactory.umCartao();
        Transaction compra = compraNoCalendario(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000aa010"), USER_ID,
                d("2026-10-05"), "mercado"), Money.of("100.00"), card, ALIMENTACAO, 1);
        LocalDate hoje = d("2026-11-06");
        InvoiceChain chain = CardInvoices.chainFor(card, List.of(compra));
        Transaction pagamento = CardTransactions.invoicePayment(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000aa011"),
                USER_ID, d("2026-11-05"), "fatura"), Money.of("100.00"),
                com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente(), card, d("2026-10-25"), chain, d("2026-11-05"));
        assertThat(CardInvoices.chainFor(card, List.of(compra, pagamento)).statusOf(d("2026-10-25"), hoje)).isEqualTo(InvoiceStatus.PAID);

        RecurringTransaction streaming = RecurringTransaction.ofCard(UUID.fromString("00000000-0000-0000-0000-0000000aa020"), USER_ID,
                Money.of("39.90"), "Streaming", ALIMENTACAO.id(), card.id(), Schedule.of(Frequency.MONTHLY, d("2026-10-10")));
        Transaction realizada = streaming.realizeOnCard(d("2026-10-10"), UUID.fromString("00000000-0000-0000-0000-0000000aa021"), null,
                card, CardInvoices.chainFor(card, List.of(compra, pagamento)), hoje, ALIMENTACAO);

        InvoiceChain depois = CardInvoices.chainFor(card, List.of(compra, pagamento, realizada));
        assertThat(realizada.installments().getFirst().invoiceClosingDate()).isEqualTo(d("2026-11-25"));
        assertThat(realizada.installments().getFirst().lateEntry()).isTrue();
        assertThat(depois.statusOf(d("2026-10-25"), hoje)).isEqualTo(InvoiceStatus.PAID);
    }
}
