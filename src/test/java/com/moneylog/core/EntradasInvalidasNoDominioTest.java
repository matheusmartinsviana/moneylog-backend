package com.moneylog.core;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.text.PlainText;
import com.moneylog.core.sk.text.PlainText.TextInvalidCharactersException;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.sk.time.FinancialDates.DateOutOfRangeException;
import com.moneylog.core.transaction.domain.TransactionHeader;

/** B4: caracteres de controle, janela de datas (S-114) e página gigante nunca chegam ao banco. */
@DisplayName("Entradas inválidas barradas no domínio (B4)")
class EntradasInvalidasNoDominioTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000e0b04");

    @Test
    @DisplayName("PlainText: NUL e demais controles recusados; quebra de linha e tab só onde permitido")
    void deveReconhecerControles() {
        assertThat(PlainText.hasControlCharacters("a\u0000b", false)).isTrue();
        assertThat(PlainText.hasControlCharacters("a\u001Bb", true)).isTrue();
        assertThat(PlainText.hasControlCharacters("a\nb", false)).isTrue();
        assertThat(PlainText.hasControlCharacters("a\nb\tc\r", true)).isFalse();
        assertThat(PlainText.hasControlCharacters("ação 😀 çãõ", false)).isFalse();
        assertThat(PlainText.hasControlCharacters(null, false)).isFalse();
        assertThatThrownBy(() -> PlainText.requireClean("x\u0000", "name", false)).isInstanceOf(TextInvalidCharactersException.class)
                .hasFieldOrPropertyWithValue("code", "TEXT_INVALID_CHARACTERS");
    }

    @Test
    @DisplayName("descrição, notas e motivo com NUL: recusados pelo header (notas aceitam quebra de linha)")
    void deveRecusarNulNoHeader() {
        LocalDate day = LocalDate.of(2026, 10, 10);
        assertThatThrownBy(() -> TransactionHeader.of(ID, USER_ID, day, "a\u0000b")).isInstanceOf(TextInvalidCharactersException.class);
        assertThatThrownBy(() -> TransactionHeader.of(ID, USER_ID, day, "ok").withNotes("n\u0000"))
                .isInstanceOf(TextInvalidCharactersException.class);
        assertThat(TransactionHeader.of(ID, USER_ID, day, "ok").withNotes("linha 1\nlinha 2").notes()).isEqualTo("linha 1\nlinha 2");
        assertThatThrownBy(() -> TransactionHeader.of(ID, USER_ID, day, "a\nb")).isInstanceOf(TextInvalidCharactersException.class);
    }

    @Test
    @DisplayName("nome e instituição da conta e nome da categoria com NUL: recusados")
    void deveRecusarNulNosNomes() {
        LocalDate day = LocalDate.of(2026, 10, 1);
        assertThatThrownBy(() -> Account.open(ID, USER_ID, "a\u0000b", AccountType.CHECKING, null, Money.of("1.00"), day))
                .isInstanceOf(TextInvalidCharactersException.class);
        assertThatThrownBy(() -> Account.open(ID, USER_ID, "ok", AccountType.CHECKING, "i\u0000", Money.of("1.00"), day))
                .isInstanceOf(TextInvalidCharactersException.class);
        assertThatThrownBy(() -> Category.create(ID, USER_ID, "a\u0000b", CategoryKind.EXPENSE)).isInstanceOf(TextInvalidCharactersException.class);
    }

    @Test
    @DisplayName("janela de datas (S-114): 2000-01-01 e 2100-12-31 passam; um dia fora, ano 0 e +999999999 são recusados")
    void deveAplicarAJanelaDeDatas() {
        assertThat(FinancialDates.isInWindow(LocalDate.of(2000, 1, 1))).isTrue();
        assertThat(FinancialDates.isInWindow(LocalDate.of(2100, 12, 31))).isTrue();
        for (LocalDate fora : new LocalDate[] {LocalDate.of(1999, 12, 31), LocalDate.of(2101, 1, 1), LocalDate.of(0, 1, 1),
                LocalDate.of(999999999, 12, 31)}) {
            assertThat(FinancialDates.isInWindow(fora)).isFalse();
            assertThatThrownBy(() -> TransactionHeader.of(ID, USER_ID, fora, "x")).isInstanceOf(DateOutOfRangeException.class)
                    .hasFieldOrPropertyWithValue("code", "DATE_OUT_OF_RANGE");
            assertThatThrownBy(() -> Account.open(ID, USER_ID, "ok", AccountType.CHECKING, null, Money.of("1.00"), fora))
                    .isInstanceOf(DateOutOfRangeException.class);
        }
        Account conta = Account.open(ID, USER_ID, "ok", AccountType.CHECKING, null, Money.of("1.00"), LocalDate.of(2026, 10, 1));
        assertThatThrownBy(() -> conta.correctOpening(AccountType.CHECKING, Money.of("1.00"), LocalDate.of(0, 1, 1), false))
                .isInstanceOf(DateOutOfRangeException.class);
    }

    @Test
    @DisplayName("PageQuery: offset acima de Integer.MAX_VALUE é 'além dos dados'; page=2147483647 não estoura o long")
    void deveDetectarPaginaAlemDoDriver() {
        assertThat(new PageQuery(2147483647, 50).isBeyondAnyData()).isTrue();
        assertThat(new PageQuery(1, 50).isBeyondAnyData()).isFalse();
        assertThat(new PageQuery(42_949_672, 50).isBeyondAnyData()).isFalse();
    }
}
