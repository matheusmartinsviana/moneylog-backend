package com.moneylog.core.finance.app.transactions;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.AdjustmentKind;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Hash do comando canônico (S-87, T26b CA3)")
class CanonicalCommandHashTest {

    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000a0001");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000a0002");
    private static final UUID CATEGORY = UUID.fromString("00000000-0000-0000-0000-0000000c0001");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 10);

    private static Common common(String amount, String description, String notes) {
        return new Common(USER_ID, Money.of(amount), DATE, description, notes);
    }

    private static CreateTransactionCommand expense(String amount, String description, String notes) {
        return new CreateTransactionCommand.Expense(common(amount, description, notes), ACCOUNT, CATEGORY, PaymentMethod.PIX);
    }

    @Test
    @DisplayName("é hexadecimal de 64 caracteres (SHA-256) e determinístico")
    void deveSerSha256Determinístico() {
        String hash = CanonicalCommandHash.of(expense("100.00", "Restaurante", null));

        assertThat(hash).matches("[0-9a-f]{64}").isEqualTo(CanonicalCommandHash.of(expense("100.00", "Restaurante", null)));
    }

    @Test
    @DisplayName("amount 100.0, 100 e 100.00 são o mesmo comando; texto sem espaços nas pontas e em branco = ausente")
    void deveNormalizarValorETexto() {
        String reference = CanonicalCommandHash.of(expense("100.00", "Restaurante", null));

        assertThat(CanonicalCommandHash.of(expense("100.0", "Restaurante", null))).isEqualTo(reference);
        assertThat(CanonicalCommandHash.of(expense("100", "  Restaurante ", "   "))).isEqualTo(reference);
    }

    @Test
    @DisplayName("qualquer campo diferente muda o hash")
    void deveMudarComQualquerCampo() {
        String reference = CanonicalCommandHash.of(expense("100.00", "Restaurante", null));

        assertThat(CanonicalCommandHash.of(expense("100.01", "Restaurante", null))).isNotEqualTo(reference);
        assertThat(CanonicalCommandHash.of(expense("100.00", "Restaurante 2", null))).isNotEqualTo(reference);
        assertThat(CanonicalCommandHash.of(expense("100.00", "Restaurante", "nota"))).isNotEqualTo(reference);
        assertThat(CanonicalCommandHash.of(new CreateTransactionCommand.Expense(common("100.00", "Restaurante", null), OTHER_ACCOUNT,
                CATEGORY, PaymentMethod.PIX))).isNotEqualTo(reference);
        assertThat(CanonicalCommandHash.of(new CreateTransactionCommand.Expense(common("100.00", "Restaurante", null), ACCOUNT,
                CATEGORY, PaymentMethod.DEBIT))).isNotEqualTo(reference);
        assertThat(CanonicalCommandHash.of(new CreateTransactionCommand.Income(common("100.00", "Restaurante", null), ACCOUNT,
                CATEGORY, PaymentMethod.PIX))).isNotEqualTo(reference);
    }

    @Test
    @DisplayName("o limite entre campos não pode ser forjado (descrição que imita o campo seguinte)")
    void naoDeveColidirPorConcatenacao() {
        assertThat(CanonicalCommandHash.of(expense("100.00", "a", "b"))).isNotEqualTo(CanonicalCommandHash.of(expense("100.00", "a;notes=1:b", null)));
    }

    @Test
    @DisplayName("todos os tipos têm hash; o kind ausente do ajuste vale CORRECTION")
    void deveCobrirTodosOsTipos() {
        Common common = common("10.00", "x", null);
        assertThat(CanonicalCommandHash.of(new CreateTransactionCommand.Transfer(common, ACCOUNT, OTHER_ACCOUNT))).hasSize(64);
        assertThat(CanonicalCommandHash.of(new CreateTransactionCommand.Investment(common, ACCOUNT, OTHER_ACCOUNT, InvestmentDirection.CONTRIBUTION))).hasSize(64);
        assertThat(CanonicalCommandHash.of(new CreateTransactionCommand.Refund(common, ACCOUNT, CATEGORY, null, null))).hasSize(64);
        assertThat(CanonicalCommandHash.of(new CreateTransactionCommand.Unavailable(USER_ID, TransactionType.CARD_PURCHASE))).hasSize(64);
        assertThat(CanonicalCommandHash.of(new CreateTransactionCommand.Adjustment(common, ACCOUNT, AdjustmentDirection.INCREASE, null, "motivo")))
                .isEqualTo(CanonicalCommandHash.of(new CreateTransactionCommand.Adjustment(common, ACCOUNT, AdjustmentDirection.INCREASE,
                        AdjustmentKind.CORRECTION, " motivo ")));
    }
}
