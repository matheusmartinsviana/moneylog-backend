package com.moneylog.core.sk.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Hierarquia de exceções de domínio")
class DomainExceptionTest {

    static class ContaNaoEncontrada extends NotFoundException {
        ContaNaoEncontrada() {
            super("ACCOUNT_NOT_FOUND", "Conta não encontrada.");
        }
    }

    static class RegraQuebrada extends BusinessRuleException {
        RegraQuebrada(String code) {
            super(code, "Regra quebrada.");
        }
    }

    static class VersaoDesatualizada extends ConflictException {
        VersaoDesatualizada() {
            super("VERSION_CONFLICT", "Versão desatualizada.");
        }
    }

    @Test
    @DisplayName("NotFound, BusinessRule e Conflict estendem DomainException e carregam code estável")
    void deveTerHierarquiaComCode() {
        assertThat(new ContaNaoEncontrada()).isInstanceOf(DomainException.class).isInstanceOf(RuntimeException.class)
                .extracting(DomainException::code).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(new RegraQuebrada("INVOICE_NOT_CLOSED")).isInstanceOf(DomainException.class)
                .extracting(DomainException::code).isEqualTo("INVOICE_NOT_CLOSED");
        assertThat(new VersaoDesatualizada()).isInstanceOf(DomainException.class)
                .hasMessage("Versão desatualizada.");
    }

    @Test
    @DisplayName("code precisa ser UPPER_SNAKE_CASE")
    void deveValidarOCode() {
        assertThatThrownBy(() -> new RegraQuebrada("invoice-not-closed")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegraQuebrada(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("a hierarquia não depende de Spring")
    void naoDependeDeSpring() {
        assertThat(DomainException.class.getSuperclass()).isEqualTo(RuntimeException.class);
    }
}
