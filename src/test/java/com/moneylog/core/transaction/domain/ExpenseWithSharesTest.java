package com.moneylog.core.transaction.domain;

import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.DebtOperationRefusedException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.domain.NetWorth;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharePersonArchivedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharesExceedAmountException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;

/**
 * Divisão de despesa (D1 §2.6.1, S-40, S-137, T78): paguei 200,00 por Pix e o João me deve 100,00. O caixa é −200 (a linha do extrato),
 * o consumo é só a parte do usuário (100) e o patrimônio cai 100 (a parte do João é um recebível). A quitação do João não é receita.
 */
@DisplayName("Divisão de despesa: EXPENSE com shares (D1 §2.6.1)")
class ExpenseWithSharesTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    private static final UUID EXPENSE_ID = UUID.fromString("00000000-0000-0000-0000-0000000e5001");
    private static final UUID DEBT_1 = UUID.fromString("00000000-0000-0000-0000-0000000d5001");
    private static final UUID DEBT_2 = UUID.fromString("00000000-0000-0000-0000-0000000d5002");

    private final Account nubank = AccountTestFactory.umaContaCorrente();
    private final Category food = CategoryTestFactory.umaCategoriaDeDespesa();
    private final Person joao = PersonTestFactory.umJoao();
    private final Person pedro = PersonTestFactory.umPedro();

    private TransactionHeader header(UUID id) {
        return TransactionHeader.of(id, USER_ID, DATE, "Jantar");
    }

    private ExpenseShare share(UUID debtId, Person person, String amount) {
        return new ExpenseShare(debtId, person, Money.of(amount), LocalDate.of(2026, 11, 5), null);
    }

    private ExpenseOperation pixOf200With(List<ExpenseShare> shares) {
        return AccountTransactions.expenseWithShares(header(EXPENSE_ID), Money.of("200.00"), nubank, food, PaymentMethod.PIX, shares);
    }

    private FinancialRecords records(Transaction tx, List<Debt> debts, Transaction... more) {
        java.util.List<Transaction> all = new java.util.ArrayList<>(List.of(tx));
        all.addAll(List.of(more));
        return new FinancialRecords(List.of(nubank), List.of(), all, debts);
    }

    @Test
    @DisplayName("Pix de 200 com 100 do João: EXPENSE +100 / RECEIVABLE +100 / ACCOUNT −200; caixa −200, consumo 100, patrimônio −100; a despesa é a origem da dívida")
    void deveDividirADespesa() {
        ExpenseOperation op = pixOf200With(List.of(share(DEBT_1, joao, "100.00")));
        Transaction tx = op.transaction();

        assertThat(tx.postings()).hasSize(3);
        assertThat(tx.postings().stream().filter(p -> p.bookType() == BookType.EXPENSE).findFirst().orElseThrow().amount()).isEqualTo(Money.of("100.00"));
        assertThat(tx.postings().stream().filter(p -> p.bookType() == BookType.RECEIVABLE).findFirst().orElseThrow().amount())
                .isEqualTo(Money.of("100.00"));
        assertThat(tx.postings().stream().filter(p -> p.bookType() == BookType.ACCOUNT).findFirst().orElseThrow().amount())
                .isEqualTo(Money.of("-200.00"));
        assertThat(tx.type()).isEqualTo(TransactionType.EXPENSE);
        assertThat(tx.debtId()).isNull();
        assertThat(tx.amount()).isEqualTo(Money.of("200.00"));

        Debt debt = op.debts().get(0);
        assertThat(debt.originTransactionId()).isEqualTo(EXPENSE_ID);
        assertThat(debt.direction()).isEqualTo(DebtDirection.RECEIVABLE);
        assertThat(debt.principal()).isEqualTo(Money.of("100.00"));
        assertThat(debt.description()).isEqualTo("Jantar");
        assertThat(debt.expectedDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(debt.status()).isEqualTo(DebtStatus.OPEN);

        FinancialRecords records = records(tx, op.debts());
        Ledger ledger = records.ledger();
        Competence october = Competence.from(DATE);
        assertThat(ConsumptionReport.of(records, ledger, october).consumption()).isEqualTo(Money.of("100.00"));
        assertThat(CashFlowReport.forCompetence(records, ledger, october).out()).isEqualTo(Money.of("200.00"));
        assertThat(NetWorth.at(ledger, DATE).total()).isEqualTo(Money.of("19900.00"));
        assertThat(NetWorth.at(ledger, DATE).receivables()).isEqualTo(Money.of("100.00"));
        assertThat(ledger.sum(e -> true)).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("o João paga 100: caixa +100, consumo inalterado e nenhuma receita (a quitação nunca é renda)")
    void aQuitacaoNaoEReceita() {
        ExpenseOperation op = pixOf200With(List.of(share(DEBT_1, joao, "100.00")));
        Debt debt = op.debts().get(0);
        DebtOperation payment = DebtTransactions.moneyReceived(TransactionHeader.of(UUID.fromString("00000000-0000-0000-0000-0000000e5002"),
                USER_ID, LocalDate.of(2026, 10, 12), "Pix do João"), Money.of("100.00"), nubank, debt);

        FinancialRecords records = records(op.transaction(), List.of(debt), payment.transaction());
        Ledger ledger = records.ledger();
        Competence october = Competence.from(DATE);
        ConsumptionReport month = ConsumptionReport.of(records, ledger, october);
        assertThat(month.consumption()).isEqualTo(Money.of("100.00"));
        assertThat(month.income()).isEqualTo(Money.of("0.00"));
        assertThat(month.received()).isEqualTo(Money.of("100.00"));
        assertThat(CashFlowReport.forCompetence(records, ledger, october).in()).isEqualTo(Money.of("100.00"));
        // o patrimônio continua 100 abaixo do inicial: o consumo (a parte do usuário) não volta; só o recebível virou caixa
        assertThat(NetWorth.at(ledger, LocalDate.of(2026, 10, 12)).total()).isEqualTo(Money.of("19900.00"));
        assertThat(NetWorth.at(ledger, LocalDate.of(2026, 10, 12)).receivables()).isEqualTo(Money.of("0.00"));
        assertThat(debt.status()).isEqualTo(DebtStatus.SETTLED);
        assertThat(ledger.balance(BookType.RECEIVABLE, LocalDate.of(2026, 10, 12))).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("Σ das partes ≥ valor, parte ≤ 0 ou lista vazia: SHARES_EXCEED_AMOUNT (pagar tudo pelo João é empréstimo)")
    void deveRecusarPartesInvalidas() {
        assertThatThrownBy(() -> pixOf200With(List.of(share(DEBT_1, joao, "200.00")))).isInstanceOf(SharesExceedAmountException.class)
                .extracting("code").isEqualTo("SHARES_EXCEED_AMOUNT");
        assertThatThrownBy(() -> pixOf200With(List.of(share(DEBT_1, joao, "120.00"), share(DEBT_2, pedro, "80.00"))))
                .isInstanceOf(SharesExceedAmountException.class);
        assertThatThrownBy(() -> pixOf200With(List.of(share(DEBT_1, joao, "0.00")))).isInstanceOf(SharesExceedAmountException.class);
        assertThatThrownBy(() -> pixOf200With(List.of(share(DEBT_1, joao, "-5.00")))).isInstanceOf(SharesExceedAmountException.class);
        assertThatThrownBy(() -> pixOf200With(List.of())).isInstanceOf(SharesExceedAmountException.class);
        assertThatThrownBy(() -> pixOf200With(java.util.Collections.singletonList(null))).isInstanceOf(SharesExceedAmountException.class);
        assertThatThrownBy(() -> pixOf200With(List.of(new ExpenseShare(DEBT_1, joao, null, null, null))))
                .isInstanceOf(SharesExceedAmountException.class);
    }

    @Test
    @DisplayName("pessoa arquivada: SHARE_PERSON_ARCHIVED; pessoa de outro usuário: PERSON_NOT_FOUND; duas partes à mesma pessoa viram duas dívidas")
    void deveValidarAsPessoas() {
        Person archived = Person.restore(joao.id(), USER_ID, "João", null, true);
        assertThatThrownBy(() -> pixOf200With(List.of(share(DEBT_1, archived, "50.00")))).isInstanceOf(SharePersonArchivedException.class)
                .extracting("code").isEqualTo("SHARE_PERSON_ARCHIVED");
        Person stranger = Person.create(UUID.randomUUID(), OTHER_USER_ID, "Alheio", null);
        assertThatThrownBy(() -> pixOf200With(List.of(share(DEBT_1, stranger, "50.00")))).isInstanceOf(PersonNotFoundException.class);

        ExpenseOperation op = pixOf200With(List.of(share(DEBT_1, joao, "30.00"), share(DEBT_2, joao, "50.00")));
        assertThat(op.debts()).hasSize(2);
        assertThat(op.debts()).extracting(Debt::personId).containsOnly(joao.id());
        assertThat(op.transaction().postings()).hasSize(4);
        assertThat(FinancialRecordsOf(op).ledger().sum(e -> e.posting().bookType() == BookType.EXPENSE)).isEqualTo(Money.of("120.00"));
    }

    private FinancialRecords FinancialRecordsOf(ExpenseOperation op) {
        return records(op.transaction(), op.debts());
    }

    @Test
    @DisplayName("estorno da despesa dividida sem quitação estorna despesa e dívidas juntas; com quitação ativa é DEBT_HAS_SETTLEMENTS e nada muda; perdoada é DEBT_CLOSED")
    void deveEstornarComAsGuardasDasDividas() {
        ExpenseOperation clean = pixOf200With(List.of(share(DEBT_1, joao, "100.00"), share(DEBT_2, pedro, "40.00")));
        TransactionVoiding.voidTransaction(clean.transaction(), VoidContext.of("engano", NOW, DATE).withAccounts(List.of(nubank))
                .withActiveRefunds(Money.of("0.00")).withShareDebts(clean.debts()));
        assertThat(clean.transaction().status()).isEqualTo(TransactionStatus.VOIDED);
        assertThat(clean.transaction().postings()).isEmpty();
        assertThat(clean.debts()).allSatisfy(d -> assertThat(d.status()).isEqualTo(DebtStatus.VOIDED));
        assertThat(clean.debts()).allSatisfy(d -> assertThat(d.closureReason()).isEqualTo("engano"));

        ExpenseOperation settled = pixOf200With(List.of(share(DEBT_1, joao, "100.00"), share(DEBT_2, pedro, "40.00")));
        DebtTransactions.moneyReceived(TransactionHeader.of(UUID.randomUUID(), USER_ID, DATE, "Pix"), Money.of("10.00"), nubank,
                settled.debts().get(0));
        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(settled.transaction(), VoidContext.of("x", NOW, DATE)
                .withAccounts(List.of(nubank)).withActiveRefunds(Money.of("0.00")).withShareDebts(settled.debts())))
                .isInstanceOf(DebtOperationRefusedException.class).extracting("code").isEqualTo("DEBT_HAS_SETTLEMENTS");
        // nada mudou: a despesa e as duas dívidas continuam como estavam
        assertThat(settled.transaction().status()).isEqualTo(TransactionStatus.ACTIVE);
        assertThat(settled.debts().get(1).status()).isEqualTo(DebtStatus.OPEN);
        assertThat(settled.debts().get(1).closure()).isNull();

        ExpenseOperation forgiven = pixOf200With(List.of(share(DEBT_1, joao, "100.00")));
        forgiven.debts().get(0).cancel("perdoei", DATE);
        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(forgiven.transaction(), VoidContext.of("x", NOW, DATE)
                .withAccounts(List.of(nubank)).withActiveRefunds(Money.of("0.00")).withShareDebts(forgiven.debts())))
                .isInstanceOf(DebtOperationRefusedException.class).extracting("code").isEqualTo("DEBT_CLOSED");
    }

    @Test
    @DisplayName("a guarda da despesa vale antes das das dívidas: despesa com reembolso ativo ou conta arquivada recusa sem tocar nas dívidas")
    void deveRecusarPelaGuardaDaDespesa() {
        ExpenseOperation op = pixOf200With(List.of(share(DEBT_1, joao, "100.00")));
        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(op.transaction(), VoidContext.of("x", NOW, DATE)
                .withAccounts(List.of(nubank)).withActiveRefunds(Money.of("10.00")).withShareDebts(op.debts())))
                .isInstanceOf(TransactionVoidRefusedException.class).extracting("code").isEqualTo("TRANSACTION_HAS_ACTIVE_REFUNDS");
        assertThat(op.debts().get(0).closure()).isNull();
        assertThat(op.transaction().status()).isEqualTo(TransactionStatus.ACTIVE);
        assertThatThrownBy(() -> TransactionVoiding.voidTransaction(op.transaction(), VoidContext.of("x", NOW, DATE)
                .withActiveRefunds(Money.of("0.00")).withShareDebts(op.debts())))
                .isInstanceOf(com.moneylog.core.transaction.exception.TransactionExceptions.VoidContextMissingException.class);
    }

    @Test
    @DisplayName("a edição da despesa dividida regrava os lançamentos na categoria nova com as mesmas dívidas; partes que não fecham são recusadas")
    void deveReconstruirParaAEdicao() {
        ExpenseOperation op = pixOf200With(List.of(share(DEBT_1, joao, "100.00")));
        Category transport = Category.create(UUID.randomUUID(), USER_ID, "Transporte", com.moneylog.core.category.domain.CategoryKind.EXPENSE);
        Transaction edited = AccountTransactions.expenseKeepingShares(header(EXPENSE_ID), Money.of("200.00"), nubank, transport,
                PaymentMethod.PIX, op.debts());
        assertThat(edited.postings()).hasSize(3);
        assertThat(edited.categoryId()).isEqualTo(transport.id());
        assertThatThrownBy(() -> AccountTransactions.expenseKeepingShares(header(EXPENSE_ID), Money.of("100.00"), nubank, food,
                PaymentMethod.PIX, op.debts())).isInstanceOf(SharesExceedAmountException.class);
        assertThatThrownBy(() -> AccountTransactions.expenseKeepingShares(header(EXPENSE_ID), Money.of("200.00"), nubank, food,
                PaymentMethod.PIX, List.of())).isInstanceOf(SharesExceedAmountException.class);
        // dívida de outra origem não pode ser reaproveitada
        assertThatThrownBy(() -> AccountTransactions.expenseKeepingShares(header(UUID.randomUUID()), Money.of("200.00"), nubank, food,
                PaymentMethod.PIX, op.debts())).isInstanceOf(SharesExceedAmountException.class);
    }

    @Test
    @DisplayName("propriedade (200 cenários): o razão fecha em zero, o consumo é a parte do usuário, o caixa é o total e o patrimônio cai pela parte do usuário")
    void devePreservarAsInvariantesEmCenariosAleatorios() {
        Random random = new Random(40);
        for (int scenario = 0; scenario < 200; scenario++) {
            long total = 100 + random.nextInt(2_000_000);
            int parts = 1 + random.nextInt(4);
            long remaining = total - 1;
            long[] shares = new long[parts];
            long sum = 0;
            for (int i = 0; i < parts; i++) {
                long share = 1 + (long) (random.nextDouble() * Math.max(1, (remaining - sum) / (parts - i)));
                shares[i] = Math.max(1, Math.min(share, remaining - sum - (parts - i - 1)));
                sum += shares[i];
            }
            if (sum >= total) {
                continue;
            }
            List<ExpenseShare> list = new java.util.ArrayList<>();
            for (int i = 0; i < parts; i++) {
                list.add(new ExpenseShare(UUID.randomUUID(), i % 2 == 0 ? joao : pedro,
                        Money.ofCents(shares[i], com.moneylog.core.sk.money.CurrencyCode.BRL), null, null));
            }
            Money amount = Money.ofCents(total, com.moneylog.core.sk.money.CurrencyCode.BRL);
            ExpenseOperation op = AccountTransactions.expenseWithShares(header(UUID.randomUUID()), amount, nubank, food, PaymentMethod.PIX, list);
            FinancialRecords records = new FinancialRecords(List.of(nubank), List.of(), List.of(op.transaction()), op.debts());
            Ledger ledger = records.ledger();
            assertThat(ledger.sum(e -> true).cents()).as("razão do cenário %d", scenario).isZero();
            assertThat(ConsumptionReport.of(records, ledger, Competence.from(DATE)).consumption().cents()).isEqualTo(total - sum);
            assertThat(CashFlowReport.forCompetence(records, ledger, Competence.from(DATE)).out().cents()).isEqualTo(total);
            assertThat(NetWorth.at(ledger, DATE).total().cents()).isEqualTo(2_000_000L - (total - sum));
            List<LedgerEntry> entries = records.entries();
            assertThat(entries).allMatch(LedgerEntry::active);
        }
    }
}
