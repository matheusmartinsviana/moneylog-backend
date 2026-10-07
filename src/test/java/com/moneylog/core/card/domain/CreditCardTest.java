package com.moneylog.core.card.domain;

import static com.moneylog.core.card.util.factory.CreditCardTestFactory.CREATED_ON;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.ROXINHO_ID;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.umCartao;
import static com.moneylog.core.card.util.factory.CreditCardTestFactory.umCartaoArquivado;
import static com.moneylog.core.util.Ids.OTHER_USER_ID;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.card.exception.CardExceptions.CardAlreadyArchivedException;
import com.moneylog.core.card.exception.CardExceptions.CardArchivedException;
import com.moneylog.core.card.exception.CardExceptions.CardFieldRequiredException;
import com.moneylog.core.card.exception.CardExceptions.CardNameRequiredException;
import com.moneylog.core.card.exception.CardExceptions.CardNameTooLongException;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.card.exception.CardExceptions.InvalidBillingDayException;
import com.moneylog.core.card.exception.CardExceptions.InvalidCardLimitException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

@DisplayName("CreditCard (T5 CA7, CA8)")
class CreditCardTest {

    @ParameterizedTest(name = "dia {0}")
    @ValueSource(ints = {0, 32, -1})
    @DisplayName("CA8: closingDay/dueDay fora de 1..31 é rejeitado")
    void deveRejeitarDiasInvalidos(int day) {
        assertThatThrownBy(() -> umCartao(day, 5)).isInstanceOf(InvalidBillingDayException.class);
        assertThatThrownBy(() -> umCartao(25, day)).isInstanceOf(InvalidBillingDayException.class);
        assertThatThrownBy(() -> umCartao().changeBillingDays(day, 5, CREATED_ON)).isInstanceOf(InvalidBillingDayException.class);
    }

    @ParameterizedTest(name = "limite {0}")
    @ValueSource(strings = {"0.00", "-1.00"})
    @DisplayName("CA8: limite ≤ 0 é rejeitado")
    void deveRejeitarLimiteNaoPositivo(String limit) {
        assertThatThrownBy(() -> CreditCard.create(ROXINHO_ID, USER_ID, "R", null, Money.of(limit), 25, 5, null, CREATED_ON))
                .isInstanceOf(InvalidCardLimitException.class);
        assertThatThrownBy(() -> CreditCard.create(ROXINHO_ID, USER_ID, "R", null, Money.of("1.00", CurrencyCode.of("USD")), 25, 5, null, CREATED_ON))
                .isInstanceOf(InvalidCardLimitException.class);
    }

    @Test
    @DisplayName("CA8: nome em branco ou longo demais e campos obrigatórios são rejeitados")
    void deveValidarCampos() {
        assertThatThrownBy(() -> CreditCard.create(ROXINHO_ID, USER_ID, " ", null, Money.of("1.00"), 25, 5, null, CREATED_ON))
                .isInstanceOf(CardNameRequiredException.class);
        assertThatThrownBy(() -> CreditCard.create(ROXINHO_ID, USER_ID, "x".repeat(101), null, Money.of("1.00"), 25, 5, null, CREATED_ON))
                .isInstanceOf(CardNameTooLongException.class);
        assertThatThrownBy(() -> CreditCard.create(null, USER_ID, "R", null, Money.of("1.00"), 25, 5, null, CREATED_ON))
                .isInstanceOf(CardFieldRequiredException.class);
        assertThatThrownBy(() -> CreditCard.create(ROXINHO_ID, null, "R", null, Money.of("1.00"), 25, 5, null, CREATED_ON))
                .isInstanceOf(CardFieldRequiredException.class);
        assertThatThrownBy(() -> CreditCard.restore(ROXINHO_ID, USER_ID, "R", null, Money.of("1.00"), null, List.of(), CardStatus.ACTIVE, 1))
                .isInstanceOf(CardFieldRequiredException.class);
        assertThatThrownBy(() -> new InvoiceCalendar(List.of())).isInstanceOf(CardFieldRequiredException.class);
    }

    @Test
    @DisplayName("CA8: o cartão não tem campo de número completo nem CVV")
    void naoDeveGuardarNumeroNemCvv() {
        assertThat(Arrays.stream(CreditCard.class.getDeclaredFields()).map(Field::getName))
                .noneMatch(n -> n.toLowerCase().contains("number") || n.toLowerCase().contains("cvv") || n.toLowerCase().contains("pan"));
    }

    @Test
    @DisplayName("CA7: cartão arquivado não recebe compra; arquivar duas vezes é rejeitado")
    void deveTratarArquivamento() {
        CreditCard card = umCartaoArquivado();
        assertThatThrownBy(card::ensureCanReceiveTransactions).isInstanceOf(CardArchivedException.class);
        assertThatThrownBy(card::archive).isInstanceOf(CardAlreadyArchivedException.class);
        assertThat(card.status()).isEqualTo(CardStatus.ARCHIVED);
    }

    @Test
    @DisplayName("dados editáveis, dono, livro e sem setters públicos")
    void deveSerEntidadeRica() {
        CreditCard card = umCartao();
        card.updateDetails(" Roxinho Black ", " ", Money.of("15000.00"), null);
        assertThat(card.name()).isEqualTo("Roxinho Black");
        assertThat(card.institution()).isNull();
        assertThat(card.limit()).isEqualTo(Money.of("15000.00"));
        assertThat(card.defaultPaymentAccountId()).isNull();
        assertThat(card.book()).isEqualTo(BookRef.card(ROXINHO_ID));
        assertThat(card.userId()).isEqualTo(USER_ID);
        assertThat(card.version()).isZero();
        assertThat(card.toString()).contains("fecha 25");
        assertThat(card).isEqualTo(umCartao()).hasSameHashCodeAs(umCartao());
        assertThatThrownBy(() -> card.ensureBelongsTo(OTHER_USER_ID)).isInstanceOf(CardNotFoundException.class);
        assertThat(Arrays.stream(CreditCard.class.getDeclaredMethods()).filter(m -> Modifier.isPublic(m.getModifiers())).map(Method::getName))
                .noneMatch(n -> n.startsWith("set"));
        CreditCard restored = CreditCard.restore(ROXINHO_ID, USER_ID, "R", "I", Money.of("1.00"), null, card.billingRules(), CardStatus.ACTIVE, 4);
        assertThat(restored.version()).isEqualTo(4);
        assertThat(restored.institution()).isEqualTo("I");
    }
}
