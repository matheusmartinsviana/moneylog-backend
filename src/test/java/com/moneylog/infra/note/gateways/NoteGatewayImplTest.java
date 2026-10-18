package com.moneylog.infra.note.gateways;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NoteGatewayImplTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" vira \"{1}\"")
    @CsvSource(value = {"/a/b/|a/b", "///a///|a", "/|''", "''|''", "a/b|a/b", "a//b/|a//b"}, delimiter = '|')
    void deveTirarAsBarrasDasPontas(String folder, String expected) {
        assertThat(NoteGatewayImpl.trimSlashes(folder)).isEqualTo(expected);
    }
}
