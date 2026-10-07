package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

@DisplayName("MoneyJson: Money na API")
class MoneyJsonTest {

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new MoneyJson()).build();

    @Test
    @DisplayName("serializa amount como string com 2 casas, inclusive negativo e zero")
    void deveSerializar() {
        assertThat(mapper.writeValueAsString(Money.of("1234.5"))).isEqualTo("{\"amount\":\"1234.50\",\"currency\":\"BRL\"}");
        assertThat(mapper.writeValueAsString(Money.of("-0.5"))).isEqualTo("{\"amount\":\"-0.50\",\"currency\":\"BRL\"}");
        assertThat(mapper.writeValueAsString(Money.zero(CurrencyCode.BRL))).isEqualTo("{\"amount\":\"0.00\",\"currency\":\"BRL\"}");
    }

    @Test
    @DisplayName("desserializa o que serializou (ida e volta)")
    void deveFazerIdaEVolta() {
        Money money = Money.of("999999999.99");
        assertThat(mapper.readValue(mapper.writeValueAsString(money), Money.class)).isEqualTo(money);
    }

    @Test
    @DisplayName("recusa número, moeda inválida, 3 casas, campo ausente")
    void deveRecusarEntradaInvalida() {
        for (String json : new String[] {
                "{\"amount\":10.5,\"currency\":\"BRL\"}",
                "{\"amount\":\"10.00\",\"currency\":\"BR\"}",
                "{\"amount\":\"10.005\",\"currency\":\"BRL\"}",
                "{\"currency\":\"BRL\"}",
                "\"10.00\""}) {
            assertThatThrownBy(() -> mapper.readValue(json, Money.class)).as(json).isInstanceOf(tools.jackson.core.JacksonException.class);
        }
    }
}
