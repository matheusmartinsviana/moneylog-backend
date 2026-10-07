package com.moneylog.core.sk.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.money.MoneyExceptions.CurrencyMismatchException;
import com.moneylog.core.sk.money.MoneyExceptions.InvalidMoneyAllocationException;
import com.moneylog.core.sk.money.MoneyExceptions.InvalidMoneyAmountException;
import com.moneylog.core.sk.money.MoneyExceptions.MoneyOverflowException;

@DisplayName("Money")
class MoneyTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @Nested
    @DisplayName("criação")
    class Criacao {

        @Test
        @DisplayName("guarda o valor em centavos exatos e BRL por padrão")
        void deveGuardarCentavos() {
            Money money = Money.of("1200.00");

            assertThat(money.cents()).isEqualTo(120_000L);
            assertThat(money.currency()).isEqualTo(CurrencyCode.BRL);
            assertThat(money.toPlainString()).isEqualTo("1200.00");
        }

        @ParameterizedTest(name = "\"{0}\" = {1} centavos")
        @CsvSource({"0,0", "0.1,10", "0.10,10", "10.5,1050", "-0.50,-50", "-20000.00,-2000000", "92233720368547758.07,9223372036854775807"})
        @DisplayName("aceita até 2 casas decimais, com sinal")
        void deveAceitarAteDuasCasas(String texto, long centavos) {
            assertThat(Money.of(texto).cents()).isEqualTo(centavos);
        }

        @ParameterizedTest(name = "rejeita \"{0}\"")
        @ValueSource(strings = {"10.005", "10.000", "1e3", "1,00", " 1.00", "+1.00", "abc", "", ".5", "1."})
        @NullSource
        @DisplayName("rejeita mais de 2 casas ou formato inválido, sem arredondar")
        void deveRejeitarFormatoInvalido(String texto) {
            assertThatThrownBy(() -> Money.of(texto))
                    .isInstanceOf(InvalidMoneyAmountException.class)
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("code").isEqualTo("MONEY_INVALID_AMOUNT");
        }

        @Test
        @DisplayName("rejeita valor que não cabe em long de centavos")
        void deveRejeitarValorGrandeDemais() {
            assertThatThrownBy(() -> Money.of("92233720368547758.08")).isInstanceOf(MoneyOverflowException.class);
        }

        @Test
        @DisplayName("formata zero e negativos com 2 casas")
        void deveFormatar() {
            assertThat(Money.zero(CurrencyCode.BRL).toPlainString()).isEqualTo("0.00");
            assertThat(Money.ofCents(-5, CurrencyCode.BRL).toPlainString()).isEqualTo("-0.05");
            assertThat(Money.of("1.5")).hasToString("BRL 1.50");
        }
    }

    @Nested
    @DisplayName("aritmética")
    class Aritmetica {

        @Test
        @DisplayName("0.10 + 0.20 é exatamente 0.30")
        void deveSomarSemErroDePontoFlutuante() {
            assertThat(Money.of("0.10").add(Money.of("0.20"))).isEqualTo(Money.of("0.30"));
        }

        @Test
        @DisplayName("subtrai, nega, tira o módulo e multiplica")
        void deveFazerOperacoesBasicas() {
            Money cem = Money.of("100.00");

            assertThat(cem.subtract(Money.of("100.01"))).isEqualTo(Money.of("-0.01"));
            assertThat(cem.negate()).isEqualTo(Money.of("-100.00"));
            assertThat(Money.of("-3.50").abs()).isEqualTo(Money.of("3.50"));
            assertThat(cem.times(3)).isEqualTo(Money.of("300.00"));
        }

        @Test
        @DisplayName("BRL + USD lança CurrencyMismatchException (regra de negócio)")
        void deveRejeitarMoedasDiferentes() {
            Money brl = Money.of("1.00");
            Money usd = Money.of("1.00", USD);

            assertThatThrownBy(() -> brl.add(usd))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("code").isEqualTo("CURRENCY_MISMATCH");
            assertThatThrownBy(() -> brl.subtract(usd)).isInstanceOf(CurrencyMismatchException.class);
            assertThatThrownBy(() -> brl.compareTo(usd)).isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        @DisplayName("overflow em soma, subtração, negação e multiplicação lança exceção (Math.*Exact)")
        void deveDetectarOverflow() {
            Money max = Money.ofCents(Long.MAX_VALUE, CurrencyCode.BRL);
            Money min = Money.ofCents(Long.MIN_VALUE, CurrencyCode.BRL);
            Money umCentavo = Money.ofCents(1, CurrencyCode.BRL);

            assertThatThrownBy(() -> max.add(umCentavo)).isInstanceOf(MoneyOverflowException.class);
            assertThatThrownBy(() -> min.subtract(umCentavo)).isInstanceOf(MoneyOverflowException.class);
            assertThatThrownBy(min::negate).isInstanceOf(MoneyOverflowException.class);
            assertThatThrownBy(min::abs).isInstanceOf(MoneyOverflowException.class);
            assertThatThrownBy(() -> max.times(2)).isInstanceOf(MoneyOverflowException.class);
        }

        @Test
        @DisplayName("soma listas (vazia = zero)")
        void deveSomarListas() {
            assertThat(Money.sum(List.of(), CurrencyCode.BRL)).isEqualTo(Money.zero(CurrencyCode.BRL));
            assertThat(Money.sum(List.of(Money.of("0.10"), Money.of("0.20"), Money.of("-0.05")), CurrencyCode.BRL))
                    .isEqualTo(Money.of("0.25"));
        }

        @Test
        @DisplayName("compara valores da mesma moeda")
        void deveComparar() {
            assertThat(Money.of("2.00").isGreaterThan(Money.of("1.99"))).isTrue();
            assertThat(Money.of("1.99").isLessThan(Money.of("2.00"))).isTrue();
            assertThat(Money.of("0.00").isZero()).isTrue();
            assertThat(Money.of("0.01").isPositive()).isTrue();
            assertThat(Money.of("-0.01").isNegative()).isTrue();
            assertThat(Money.of("1.00")).isNotEqualTo(Money.of("1.00", USD)).isNotEqualTo("1.00");
            assertThat(Money.of("1.00")).hasSameHashCodeAs(Money.of("1.0"));
        }
    }

    @Nested
    @DisplayName("allocate (divisão em parcelas)")
    class Allocate {

        @Test
        @DisplayName("1000.00 em 3 = [333.34, 333.33, 333.33] e soma exatamente 1000.00 (resto na 1ª, S-3)")
        void deveColocarORestoNaPrimeiraParte() {
            List<Money> partes = Money.of("1000.00").allocate(3);

            assertThat(partes).containsExactly(Money.of("333.34"), Money.of("333.33"), Money.of("333.33"));
            assertThat(Money.sum(partes, CurrencyCode.BRL)).isEqualTo(Money.of("1000.00"));
        }

        @ParameterizedTest(name = "{0} em {1}")
        @CsvSource({"1200.00,6", "0.01,1", "100.05,6", "0.06,6", "999999.99,36"})
        @DisplayName("a soma das partes é sempre o total e a diferença entre partes vai só para a primeira")
        void deveSomarOTotal(String total, int partes) {
            List<Money> resultado = Money.of(total).allocate(partes);

            assertThat(resultado).hasSize(partes);
            assertThat(Money.sum(resultado, CurrencyCode.BRL)).isEqualTo(Money.of(total));
            assertThat(resultado.subList(1, partes)).allMatch(p -> p.equals(resultado.get(partes - 1)));
            assertThat(resultado).allMatch(Money::isPositive);
        }

        @Test
        @DisplayName("0.05 em 6 é rejeitado (parcela menor que 1 centavo)")
        void deveRejeitarParcelaMenorQueUmCentavo() {
            assertThatThrownBy(() -> Money.of("0.05").allocate(6))
                    .isInstanceOf(InvalidMoneyAllocationException.class)
                    .extracting("code").isEqualTo("MONEY_INVALID_ALLOCATION");
        }

        @Test
        @DisplayName("rejeita zero partes e valores não positivos")
        void deveRejeitarEntradasInvalidas() {
            assertThatThrownBy(() -> Money.of("10.00").allocate(0)).isInstanceOf(InvalidMoneyAllocationException.class);
            assertThatThrownBy(() -> Money.of("0.00").allocate(1)).isInstanceOf(InvalidMoneyAllocationException.class);
            assertThatThrownBy(() -> Money.of("-10.00").allocate(2)).isInstanceOf(InvalidMoneyAllocationException.class);
        }

        @Test
        @DisplayName("a lista devolvida é imutável")
        void deveDevolverListaImutavel() {
            List<Money> partes = Money.of("10.00").allocate(2);

            assertThatThrownBy(() -> partes.add(Money.of("1.00"))).isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
