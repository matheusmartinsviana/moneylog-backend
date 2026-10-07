package com.moneylog.infra.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.account.exception.AccountExceptions.AccountInstitutionTooLongException;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoicePeriod;
import com.moneylog.core.card.exception.CardExceptions.CardInstitutionTooLongException;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.DebtDescriptionTooLongException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.util.IntegrationTest;

/**
 * T12 CA5 (QA): o que o domínio aceita o schema também precisa aceitar. Cada teste cria o valor pelo domínio e grava
 * no Postgres real. Os testes de BUG-QA-01 e BUG-QA-05 nasceram {@code @Disabled} documentando as divergências e foram
 * reativados com a correção (S-77 e limites de texto no domínio).
 */
@IntegrationTest
@DisplayName("Schema × domínio: limites e datas (T12, QA)")
class SchemaDominioLimitesQaIT {

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CARD = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("faturas de fechamento 31 em fevereiro bissexto e não bissexto e na virada de ano gravam sem violar constraint")
    void deveGravarFaturasDeBorda() {
        inRollback(db -> {
            seed(31, 10);
            CreditCard card = CreditCard.create(CARD, USER, "Roxinho", null, Money.of("1000.00"), 31, 10, null, DAY);
            for (String month : new String[] {"2027-01", "2027-02", "2027-03", "2028-02", "2026-12"}) {
                InvoicePeriod p = card.calendar().periodClosingIn(YearMonth.parse(month));
                assertThatCode(() -> insertInvoice(p)).as(month).doesNotThrowAnyException();
            }
        });
    }

    /**
     * BUG-QA-01 (corrigido, S-77): fecha 30 / vence 31 → em fevereiro (e em meses de 30 dias) o clamp igualava as duas
     * datas. Agora o vencimento vai para o dia 31 do mês seguinte (com clamp) e o CHECK {@code due_date > closing_date}
     * continua valendo.
     */
    @Test
    @DisplayName("BUG-QA-01: fatura com fecha 30 / vence 31 em fevereiro e em abril vence depois do fechamento e grava")
    void deveGravarFaturaComClampDoVencimento() {
        inRollback(db -> {
            seed(30, 31);
            CreditCard card = CreditCard.create(CARD, USER, "Roxinho", null, Money.of("1000.00"), 30, 31, null, DAY);
            for (YearMonth month : new YearMonth[] {YearMonth.of(2027, 2), YearMonth.of(2027, 4), YearMonth.of(2028, 2)}) {
                InvoicePeriod p = card.calendar().periodClosingIn(month);
                assertThat(p.dueDate()).as(month.toString()).isAfter(p.closingDate());
                assertThatCode(() -> insertInvoice(p)).as(month.toString()).doesNotThrowAnyException();
            }
        });
    }

    /**
     * BUG-QA-05 (corrigido): o domínio limita {@code Debt.description} (200) e {@code institution} de conta e cartão
     * (100) com exceção de domínio específica. Texto acima do limite nunca chega ao banco; texto no limite grava.
     */
    @Test
    @DisplayName("BUG-QA-05: textos acima das colunas são recusados pelo domínio e os textos no limite gravam")
    void deveCaberOsTextosAceitosPeloDominio() {
        assertThatThrownBy(() -> Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-0000000db0ab"), USER,
                Person.create(PERSON, USER, "João", null), DebtDirection.PAYABLE, Money.of("200.00"), DAY, null, "x".repeat(201)))
                .isInstanceOf(DebtDescriptionTooLongException.class);
        assertThatThrownBy(() -> Account.open(UUID.fromString("00000000-0000-0000-0000-00000000a0ab"), USER, "Conta", AccountType.CHECKING,
                "i".repeat(101), Money.of("0.00"), DAY)).isInstanceOf(AccountInstitutionTooLongException.class);
        assertThatThrownBy(() -> CreditCard.create(CARD, USER, "Roxinho", "i".repeat(101), Money.of("1000.00"), 25, 5, null, DAY))
                .isInstanceOf(CardInstitutionTooLongException.class);

        inRollback(db -> {
            seed(25, 5);
            Debt debt = Debt.withoutMovement(UUID.fromString("00000000-0000-0000-0000-0000000db0aa"), USER,
                    Person.create(PERSON, USER, "João", null), DebtDirection.PAYABLE, Money.of("200.00"), DAY, null, "x".repeat(200));
            assertThatCode(() -> jdbc.update("""
                    INSERT INTO debts (id, user_id, person_id, direction, origin, principal_cents, currency, description, debt_date,
                        created_at, updated_at) VALUES (?, ?, ?, 'PAYABLE', 'WITHOUT_MOVEMENT', 20000, 'BRL', ?, ?, ?, ?)""",
                    debt.id(), USER, PERSON, debt.description(), Date.valueOf(DAY), NOW, NOW)).doesNotThrowAnyException();
            Account account = Account.open(UUID.fromString("00000000-0000-0000-0000-00000000a0aa"), USER, "Conta", AccountType.CHECKING,
                    "i".repeat(100), Money.of("0.00"), DAY);
            assertThatCode(() -> jdbc.update("""
                    INSERT INTO accounts (id, user_id, name, type, institution, opening_balance_cents, currency, opening_date, status,
                        created_at, updated_at) VALUES (?, ?, 'Conta', 'CHECKING', ?, 0, 'BRL', ?, 'ACTIVE', ?, ?)""",
                    account.id(), USER, account.institution(), Date.valueOf(DAY), NOW, NOW)).doesNotThrowAnyException();
            CreditCard card = CreditCard.create(UUID.fromString("00000000-0000-0000-0000-00000000d0aa"), USER, "Outro",
                    "i".repeat(100), Money.of("1000.00"), 25, 5, null, DAY);
            assertThatCode(() -> jdbc.update("""
                    INSERT INTO credit_cards (id, user_id, name, institution, limit_cents, currency, status, created_at, updated_at)
                    VALUES (?, ?, 'Outro', ?, 100000, 'BRL', 'ACTIVE', ?, ?)""", card.id(), USER, card.institution(), NOW, NOW))
                    .doesNotThrowAnyException();
        });
    }

    private void seed(int closing, int due) {
        jdbc.update("INSERT INTO users (id, email, created_at, updated_at) VALUES (?, 'qa@moneylog.test', ?, ?)", USER, NOW, NOW);
        jdbc.update("""
                INSERT INTO credit_cards (id, user_id, name, limit_cents, currency, status, created_at, updated_at)
                VALUES (?, ?, 'Roxinho', 100000, 'BRL', 'ACTIVE', ?, ?)""", CARD, USER, NOW, NOW);
        jdbc.update("""
                INSERT INTO credit_card_billing_rules (id, user_id, card_id, effective_from, closing_day, due_day, created_at, updated_at)
                VALUES (?, ?, ?, '2026-10', ?, ?, ?, ?)""", UUID.nameUUIDFromBytes("rule".getBytes()), USER, CARD, closing, due, NOW, NOW);
        jdbc.update("INSERT INTO persons (id, user_id, name, created_at, updated_at) VALUES (?, ?, 'João', ?, ?)", PERSON, USER, NOW, NOW);
    }

    private void insertInvoice(InvoicePeriod p) {
        jdbc.update("""
                INSERT INTO credit_card_invoices (id, user_id, card_id, period_start, closing_date, due_date, reference_month,
                    created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                UUID.nameUUIDFromBytes(p.closingDate().toString().getBytes()), USER, CARD, Date.valueOf(p.periodStart()),
                Date.valueOf(p.closingDate()), Date.valueOf(p.dueDate()), p.referenceMonth().toString(), NOW, NOW);
    }

    private void inRollback(Consumer<JdbcTemplate> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.accept(jdbc);
        });
    }
}
