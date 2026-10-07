package com.moneylog.infra.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.debt.domain.DebtClosure;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.domain.DebtOrigin;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.AdjustmentKind;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.util.IntegrationTest;

/**
 * Schema (T11) e domínio (T2–T9) alinhados: cada CHECK de enum tem exatamente os valores do enum do core.
 */
@IntegrationTest
@DisplayName("Schema × domínio: enums alinhados")
class SchemaDomainAlignmentIT {

    private static final Pattern LITERAL = Pattern.compile("'([A-Z_]+)'::");

    @Autowired
    private JdbcTemplate jdbc;

    static Stream<Arguments> enums() {
        return Stream.of(
                Arguments.of("ck_transactions_type", TransactionType.values()),
                Arguments.of("ck_transactions_status", TransactionStatus.values()),
                Arguments.of("ck_transactions_source", TransactionSource.values()),
                Arguments.of("ck_transactions_payment_method", PaymentMethod.values()),
                Arguments.of("ck_transactions_direction", Stream.concat(Arrays.stream(InvestmentDirection.values()),
                        Arrays.stream(AdjustmentDirection.values())).toArray(Enum[]::new)),
                Arguments.of("ck_transactions_adjustment_kind", AdjustmentKind.values()),
                Arguments.of("ck_ledger_postings_book_type", BookType.values()),
                Arguments.of("ck_ledger_postings_source_type", PostingSourceType.values()),
                Arguments.of("ck_debts_direction", DebtDirection.values()),
                Arguments.of("ck_debts_origin", DebtOrigin.values()),
                Arguments.of("ck_debts_closure", DebtClosure.values()),
                Arguments.of("ck_accounts_type", AccountType.values()),
                Arguments.of("ck_accounts_status", AccountStatus.values()),
                Arguments.of("ck_credit_cards_status", CardStatus.values()),
                Arguments.of("ck_categories_kind", CategoryKind.values()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("enums")
    @DisplayName("CHECK do banco = valores do enum do core")
    void deveTerOsMesmosValores(String constraint, Enum<?>[] values) {
        String def = jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraint);
        TreeSet<String> noBanco = new TreeSet<>();
        Matcher m = LITERAL.matcher(def);
        while (m.find()) {
            noBanco.add(m.group(1));
        }
        assertThat(noBanco).containsExactlyElementsOf(new TreeSet<>(Arrays.stream(values).map(Enum::name).toList()));
    }
}
