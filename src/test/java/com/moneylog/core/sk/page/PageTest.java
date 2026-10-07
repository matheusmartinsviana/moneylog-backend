package com.moneylog.core.sk.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.moneylog.core.sk.page.PageQuery.InvalidPageQueryException;

@DisplayName("Slice e PageQuery")
class PageTest {

    @Test
    @DisplayName("padrão: página 1 com 10 itens; offset e fetchSize calculados")
    void deveTerPadrao() {
        PageQuery primeira = PageQuery.first();

        assertThat(primeira.page()).isEqualTo(1);
        assertThat(primeira.pageSize()).isEqualTo(10);
        assertThat(new PageQuery(3, 20).offset()).isEqualTo(40);
        assertThat(primeira.fetchSize()).isEqualTo(11);
    }

    @ParameterizedTest(name = "page={0}, pageSize={1}")
    @CsvSource({"0,10", "1,0", "1,51", "-1,10"})
    @DisplayName("rejeita página ou tamanho fora dos limites")
    void deveRejeitarLimites(int page, int pageSize) {
        assertThatThrownBy(() -> new PageQuery(page, pageSize)).isInstanceOf(InvalidPageQueryException.class);
    }

    @Test
    @DisplayName("buscar pageSize + 1 indica se há próxima página, sem COUNT")
    void deveMontarSliceSemCount() {
        PageQuery query = new PageQuery(1, 2);

        assertThat(Slice.fromOverfetch(List.of("a", "b", "c"), query)).isEqualTo(new Slice<>(List.of("a", "b"), true));
        assertThat(Slice.fromOverfetch(List.of("a", "b"), query)).isEqualTo(new Slice<>(List.of("a", "b"), false));
        assertThat(Slice.<String>empty().items()).isEmpty();
    }
}
