package com.moneylog.core.finance.app.export;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.BASE_DATE;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.NOW;
import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.app.export.ExportBackupUseCase.ExportBackupQuery;
import com.moneylog.core.finance.app.export.ExportTransactionsCsvUseCase.ExportTransactionsCsvQuery;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.query.TransactionFilter;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;

@DisplayName("Exportação CSV e backup (T97, S-153)")
class ExportUseCasesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final FinancialRecordsGateway records = mock(FinancialRecordsGateway.class);
    private final CategoryGateway categories = mock(CategoryGateway.class);
    private final PersonGateway people = mock(PersonGateway.class);
    private final RecurrenceGateway recurrences = mock(RecurrenceGateway.class);
    private final TimeProvider time = mock(TimeProvider.class);
    private final FinanceScenarioFactory s = umCenarioBase();
    private final ExportTransactionsCsvUseCaseImpl csv = new ExportTransactionsCsvUseCaseImpl(records, categories);

    @BeforeEach
    void setUp() {
        when(categories.list(USER_ID, null, true)).thenReturn(List.of(s.alimentacao, s.salario));
        when(time.now()).thenReturn(NOW);
    }

    private void load() {
        when(records.load(USER_ID)).thenReturn(s.records());
    }

    private static String content(CsvExport export) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        export.writeTo(out);
        byte[] bytes = out.toByteArray();
        assertThat(bytes).startsWith(BOM);
        return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    private CsvExport export(TransactionFilter filter, boolean includeVoided, Locale locale) {
        load();
        return csv.handle(new ExportTransactionsCsvQuery(USER_ID, filter, includeVoided, locale));
    }

    private static TransactionFilter october() {
        return new TransactionFilter(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null, null, null, null, null);
    }

    @Test
    @DisplayName("CA1: UTF-8 com BOM, ';', valor com vírgula, data ISO, cabeçalho em português e uma linha por movimentação (a compra 6x aparece uma vez com 1.200,00)")
    void deveGerarOCsvDeOutubro() throws IOException {
        s.caso1DespesaPix();
        s.caso2CompraParcelada();
        s.transactions.add(AccountTransactions.income(s.header(LocalDate.of(2026, 10, 5), "Salário"), Money.of("1234.50"), s.nubank, s.salario, PaymentMethod.PIX));
        s.caso4Transferencia();
        s.transactions.add(AccountTransactions.expense(s.header(LocalDate.of(2026, 9, 30), "Setembro"), Money.of("9.99"), s.nubank, s.alimentacao, PaymentMethod.PIX));

        CsvExport export = export(october(), false, null);
        String text = content(export);
        List<String> lines = List.of(text.split("\r\n"));

        assertThat(export.fileName()).isEqualTo("moneylog-transactions-2026-10-01_2026-10-31.csv");
        assertThat(lines.getFirst()).isEqualTo("Data;Tipo;Descrição;Categoria;Conta/Cartão;Método;Valor;Status;Direção");
        assertThat(lines).hasSize(5).as("cabeçalho + 4 movimentações de outubro (a de setembro fica fora)");
        assertThat(lines).contains("2026-10-10;Despesa;Restaurante;Alimentação;Nubank;Pix;100,00;Ativa;");
        assertThat(lines).contains("2026-10-10;Compra no cartão;Notebook;Alimentação;Roxinho;;1200,00;Ativa;");
        assertThat(lines).contains("2026-10-05;Receita;Salário;Salário;Nubank;Pix;1234,50;Ativa;");
        assertThat(lines).contains("2026-10-10;Transferência;Transferência;;Nubank > XP;;1000,00;Ativa;");
        assertThat(lines.stream().filter(l -> l.contains("Notebook"))).as("a compra parcelada aparece uma vez").hasSize(1);
        assertThat(lines.get(1)).as("ordem por data").startsWith("2026-10-05");
        assertThat(text).endsWith("\r\n");
    }

    @Test
    @DisplayName("sem período: nome simples; Accept-Language en traz o cabeçalho em inglês, outro idioma cai em português")
    void deveTraduzirOCabecalho() throws IOException {
        s.caso1DespesaPix();
        TransactionFilter all = new TransactionFilter(null, null, null, null, null, null, null, null);

        assertThat(export(all, false, Locale.ENGLISH).fileName()).isEqualTo("moneylog-transactions.csv");
        assertThat(content(export(all, false, Locale.forLanguageTag("en-US")))).startsWith("Date;Type;Description;Category;Account/Card;Method;Amount;Status;Direction\r\n");
        assertThat(content(export(all, false, Locale.forLanguageTag("pt-BR")))).startsWith("Data;Tipo;");
        assertThat(content(export(all, false, Locale.FRENCH))).startsWith("Data;Tipo;");
        assertThat(content(export(all, false, null))).startsWith("Data;Tipo;");
        assertThat(export(new TransactionFilter(LocalDate.of(2026, 10, 1), null, null, null, null, null, null, null), false, null).fileName())
                .isEqualTo("moneylog-transactions-2026-10-01_.csv");
        assertThat(export(new TransactionFilter(null, LocalDate.of(2026, 10, 31), null, null, null, null, null, null), false, null).fileName())
                .isEqualTo("moneylog-transactions-_2026-10-31.csv");
    }

    @Test
    @DisplayName("CA1: estornadas só com status=VOIDED ou includeVoided")
    void deveTratarAsEstornadas() throws IOException {
        Transaction keep = s.caso1DespesaPix();
        Transaction voided = AccountTransactions.expense(s.header(BASE_DATE, "Errada"), Money.of("30.00"), s.nubank, s.alimentacao, PaymentMethod.PIX);
        s.transactions.add(voided);
        s.estornar(voided, "lançada por engano", TODAY);
        assertThat(voided.status()).isEqualTo(TransactionStatus.VOIDED);

        String active = content(export(october(), false, null));
        String onlyVoided = content(export(new TransactionFilter(null, null, null, null, null, null, TransactionStatus.VOIDED, null), false, null));
        String both = content(export(october(), true, null));

        assertThat(active).contains("Restaurante").doesNotContain("Errada");
        assertThat(onlyVoided).contains("Errada;").contains(";Estornada;").doesNotContain("Restaurante");
        assertThat(both).contains("Restaurante").contains("Errada").contains(";Estornada;");
        assertThat(keep.status()).isEqualTo(TransactionStatus.ACTIVE);
    }

    @Test
    @DisplayName("CA2: descrição que começa com = + - @ leva ' na frente; com ; e aspas é escapada (a quebra de linha, no CsvCellsTest: só a observação aceita quebra)")
    void deveProtegerContraInjecaoEEscapar() throws IOException {
        for (String description : List.of("=SOMA(1;2)", "+55", "-1", "@cmd", "diz \"oi\"; tchau")) {
            s.transactions.add(AccountTransactions.expense(s.header(BASE_DATE, description), Money.of("1.00"), s.nubank, s.alimentacao, PaymentMethod.PIX));
        }

        String text = content(export(october(), false, null));

        assertThat(text).contains("\"'=SOMA(1;2)\"").contains(";'+55;").contains(";'-1;").contains(";'@cmd;");
        assertThat(text).contains("\"diz \"\"oi\"\"; tchau\"");
        assertThat(text).doesNotContain(";=SOMA").doesNotContain(";+55;").doesNotContain(";@cmd;");
    }

    @Test
    @DisplayName("os filtros da lista valem no CSV (tipo, conta, categoria, método, texto)")
    void deveAplicarOMesmoFiltroDaLista() throws IOException {
        s.caso1DespesaPix();
        s.transactions.add(AccountTransactions.expense(s.header(BASE_DATE, "Cinema"), Money.of("40.00"), s.nubank, s.alimentacao, PaymentMethod.DEBIT));
        s.caso4Transferencia();

        String debit = content(export(new TransactionFilter(null, null, null, null, null, PaymentMethod.DEBIT, null, null), false, null));
        String search = content(export(new TransactionFilter(null, null, null, null, null, null, null, "rest"), false, null));
        String account = content(export(new TransactionFilter(null, null, null, s.xp.id(), null, null, null, null), false, null));

        assertThat(debit).contains("Cinema").doesNotContain("Restaurante").doesNotContain("Transferência");
        assertThat(search).contains("Restaurante").doesNotContain("Cinema");
        assertThat(account).contains("Transferência").doesNotContain("Cinema");
    }

    @Test
    @DisplayName("sem movimentações o CSV tem só o cabeçalho; ler os registros é um único carregamento")
    void deveGerarSoOCabecalho() throws IOException {
        String text = content(export(october(), false, null));

        assertThat(text).isEqualTo("Data;Tipo;Descrição;Categoria;Conta/Cartão;Método;Valor;Status;Direção\r\n");
        verify(records).load(USER_ID);
    }

    @Test
    @DisplayName("M2 (S-261): aplicação/resgate no INVESTMENT e crédito/débito no ADJUSTMENT saem na coluna Direção (em inglês, Direction); demais tipos deixam a coluna vazia")
    void deveTrazerADirecao() throws IOException {
        s.transactions.add(AccountTransactions.investment(s.header(BASE_DATE, "Aplicou"), Money.of("500.00"),
                com.moneylog.core.transaction.domain.InvestmentDirection.CONTRIBUTION, s.nubank, s.xpInvest));
        s.transactions.add(AccountTransactions.investment(s.header(BASE_DATE, "Resgatou"), Money.of("200.00"),
                com.moneylog.core.transaction.domain.InvestmentDirection.REDEMPTION, s.nubank, s.xpInvest));
        s.transactions.add(AccountTransactions.adjustment(s.header(BASE_DATE, "Mais"), Money.of("10.00"), s.nubank,
                com.moneylog.core.transaction.domain.AdjustmentDirection.INCREASE, "extrato"));
        s.transactions.add(AccountTransactions.adjustment(s.header(BASE_DATE, "Menos"), Money.of("20.00"), s.nubank,
                com.moneylog.core.transaction.domain.AdjustmentDirection.DECREASE, "extrato"));
        s.caso1DespesaPix();
        TransactionFilter all = new TransactionFilter(null, null, null, null, null, null, null, null);

        String pt = content(export(all, false, null));
        String en = content(export(all, false, Locale.ENGLISH));

        assertThat(pt).contains(";500,00;Ativa;Aplicação\r\n").contains(";200,00;Ativa;Resgate\r\n").contains(";10,00;Ativa;Crédito\r\n")
                .contains(";20,00;Ativa;Débito\r\n").contains("Despesa;Restaurante;Alimentação;Nubank;Pix;100,00;Ativa;\r\n");
        assertThat(en).contains(";500,00;Active;Contribution\r\n").contains(";200,00;Active;Redemption\r\n").contains(";10,00;Active;Credit\r\n")
                .contains(";20,00;Active;Debit\r\n").contains("Expense;Restaurante;Alimentação;Nubank;Pix;100,00;Active;\r\n");
    }

    @Test
    @DisplayName("S-260: acima do teto de linhas o CSV é recusado antes de qualquer byte (EXPORT_TOO_LARGE); no teto passa; o teto vale para as linhas do filtro, não para todo o razão")
    void deveRecusarAcimaDoTeto() throws IOException {
        s.caso1DespesaPix();
        s.transactions.add(AccountTransactions.expense(s.header(BASE_DATE, "Cinema"), Money.of("40.00"), s.nubank, s.alimentacao, PaymentMethod.DEBIT));
        s.transactions.add(AccountTransactions.expense(s.header(LocalDate.of(2026, 9, 1), "Antiga"), Money.of("1.00"), s.nubank, s.alimentacao, PaymentMethod.DEBIT));
        load();
        ExportTransactionsCsvUseCaseImpl limited = new ExportTransactionsCsvUseCaseImpl(records, categories, 2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> limited.handle(new ExportTransactionsCsvQuery(USER_ID,
                new TransactionFilter(null, null, null, null, null, null, null, null), false, null)))
                .isInstanceOf(ExportTooLargeException.class).hasMessageContaining("3").hasMessageContaining("2")
                .extracting("code").isEqualTo("EXPORT_TOO_LARGE");
        assertThat(content(limited.handle(new ExportTransactionsCsvQuery(USER_ID, october(), false, null)))).contains("Cinema");
    }

    @Test
    @DisplayName("S-260: o backup acima do teto de movimentações é recusado antes de montar o arquivo; no teto passa")
    void deveRecusarOBackupAcimaDoTeto() {
        s.caso1DespesaPix();
        s.caso4Transferencia();
        load();
        when(people.list(USER_ID, null, null, new PageQuery(1, 50))).thenReturn(new Slice<>(List.of(), false));
        when(recurrences.findAll(USER_ID)).thenReturn(List.of());
        when(categories.list(USER_ID, null, true)).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ExportBackupUseCaseImpl(records, categories, people, recurrences, time, 1)
                .handle(new ExportBackupQuery(USER_ID))).isInstanceOf(ExportTooLargeException.class);
        assertThat(new ExportBackupUseCaseImpl(records, categories, people, recurrences, time, 2).handle(new ExportBackupQuery(USER_ID)).transactions())
                .hasSize(2);
    }

    @Test
    @DisplayName("CA3: o backup reúne os registros, as categorias, as pessoas (todas as páginas), as recorrências e os lançamentos do razão, com schemaVersion e generatedAt")
    void deveMontarOBackup() {
        s.caso1DespesaPix();
        s.caso2CompraParcelada();
        Goal goal = com.moneylog.core.goal.util.GoalTestFactory.umaMetaDeEmergencia();
        FinancialRecords base = s.records();
        FinancialRecords loaded = new FinancialRecords(base.accounts(), base.cards(), base.transactions(), base.debts(), List.of(goal));
        when(records.load(USER_ID)).thenReturn(loaded);
        Person joao = PersonTestFactory.umJoao();
        Person pedro = PersonTestFactory.umPedro();
        when(people.list(USER_ID, null, null, new PageQuery(1, 50))).thenReturn(new Slice<>(List.of(new Versioned<>(joao, 1, NOW, NOW)), true));
        when(people.list(USER_ID, null, null, new PageQuery(2, 50))).thenReturn(new Slice<>(List.of(new Versioned<>(pedro, 1, NOW, NOW)), false));
        when(recurrences.findAll(USER_ID)).thenReturn(List.of());

        BackupSnapshot snapshot = new ExportBackupUseCaseImpl(records, categories, people, recurrences, time).handle(new ExportBackupQuery(USER_ID));

        assertThat(snapshot.schemaVersion()).isEqualTo(BackupSnapshot.SCHEMA_VERSION).isEqualTo(1);
        assertThat(snapshot.generatedAt()).isEqualTo(NOW);
        assertThat(snapshot.accounts()).hasSize(3);
        assertThat(snapshot.categories()).hasSize(2);
        assertThat(snapshot.cards()).hasSize(1);
        assertThat(snapshot.transactions()).hasSize(2);
        assertThat(snapshot.people()).containsExactly(joao, pedro);
        assertThat(snapshot.goals()).containsExactly(goal);
        assertThat(snapshot.recurrences()).isEmpty();
        assertThat(snapshot.ledgerPostings()).isNotEmpty().hasSize(loaded.entries().size());
        verify(records).load(USER_ID);
    }
}
