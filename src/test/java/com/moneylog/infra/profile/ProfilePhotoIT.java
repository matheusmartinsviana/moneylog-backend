package com.moneylog.infra.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;
import com.moneylog.util.SyntheticImages;

@DisplayName("Foto de perfil: envio, substituição, leitura, remoção e isolamento entre usuários")
class ProfilePhotoIT extends E4ItBase {

    private final byte[] jpeg = SyntheticImages.jpeg(1600, 1000, "Perfil de exemplo");

    private MvcResult put(String bearerToken, byte[] content) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.multipart(org.springframework.http.HttpMethod.PUT, "/v1/profile/photo")
                .file(new MockMultipartFile("file", "perfil.jpg", "image/jpeg", content)).header(HttpHeaders.AUTHORIZATION, bearerToken)).andReturn();
    }

    private MvcResult content(String bearerToken, String query) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/v1/profile/photo/content" + query).header(HttpHeaders.AUTHORIZATION, bearerToken)).andReturn();
    }

    private JsonNode settings(String bearerToken) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/v1/settings/notifications").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk()).andExpect(ContractValidation.responseOnly()).andReturn();
        return body(result);
    }

    @Test
    @DisplayName("sem foto: conteúdo é 404 PROFILE_PHOTO_NOT_FOUND e as configurações trazem hasPhoto=false, photoVersion=0")
    void semFoto() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/profile/photo/content").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFILE_PHOTO_NOT_FOUND")).andExpect(ContractValidation.responseOnly());
        JsonNode settings = settings(bearer);
        assertThat(settings.get("hasPhoto").asBoolean()).isFalse();
        assertThat(settings.get("photoVersion").asLong()).isZero();
    }

    @Test
    @DisplayName("envio válido: 200, bytes JPEG (display e thumb), cabeçalhos seguros e configurações com hasPhoto/photoVersion")
    void enviaELe() throws Exception {
        MvcResult sent = put(bearer, jpeg);
        assertThat(sent.getResponse().getStatus()).isEqualTo(200);
        JsonNode info = body(sent);
        assertThat(info.get("hasPhoto").asBoolean()).isTrue();
        assertThat(info.get("photoVersion").asLong()).isPositive();

        MvcResult display = content(bearer, "");
        assertThat(display.getResponse().getStatus()).isEqualTo(200);
        assertThat(display.getResponse().getContentType()).isEqualTo("image/jpeg");
        assertThat(display.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(display.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        var thumb = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(content(bearer, "?variant=thumb").getResponse().getContentAsByteArray()));
        assertThat(thumb.getWidth()).isEqualTo(320);

        JsonNode settings = settings(bearer);
        assertThat(settings.get("hasPhoto").asBoolean()).isTrue();
        assertThat(settings.get("photoVersion").asLong()).isEqualTo(info.get("photoVersion").asLong());
    }

    @Test
    @DisplayName("variante inválida é 400 e sem Bearer é 401")
    void variantesEAutenticacao() throws Exception {
        put(bearer, jpeg);
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/profile/photo/content?variant=original").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/profile/photo/content")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("tipo inválido, vazio e acima de 8 MB: 422 com o código da foto, sem gravar nada")
    void rejeitaInvalidos() throws Exception {
        assertThat(put(bearer, SyntheticImages.text("<html>não sou imagem</html>")).getResponse().getContentAsString()).contains("GOAL_PHOTO_UNSUPPORTED_TYPE");
        assertThat(put(bearer, new byte[(int) (8L * 1024 * 1024) + 1]).getResponse().getContentAsString()).contains("GOAL_PHOTO_TOO_LARGE");
        MvcResult empty = put(bearer, new byte[0]);
        assertThat(empty.getResponse().getStatus()).isEqualTo(422);
        assertThat(empty.getResponse().getContentAsString()).contains("GOAL_PHOTO_INVALID_IMAGE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profile_photos WHERE user_id = ?", Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("substituição: continua uma única foto, os bytes mudam e a versão avança")
    void substitui() throws Exception {
        long first = body(put(bearer, jpeg)).get("photoVersion").asLong();
        byte[] before = content(bearer, "").getResponse().getContentAsByteArray();

        long second = body(put(bearer, SyntheticImages.png(300, 300, "Nova"))).get("photoVersion").asLong();

        assertThat(second).isGreaterThan(first);
        assertThat(content(bearer, "").getResponse().getContentAsByteArray()).isNotEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_profile_photos WHERE user_id = ?", Long.class, userId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("remoção: 204, depois 404 no conteúdo e hasPhoto=false; repetir continua 204")
    void remove() throws Exception {
        put(bearer, jpeg);
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(MockMvcRequestBuilders.delete("/v1/profile/photo").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isNoContent());
        }
        assertThat(content(bearer, "").getResponse().getStatus()).isEqualTo(404);
        assertThat(settings(bearer).get("hasPhoto").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("isolamento: a foto de um usuário não aparece nem é removida por outro")
    void isolaUsuarios() throws Exception {
        put(bearer, jpeg);
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@example.com"));
        String otherBearer = auth.bearer(other);

        assertThat(content(otherBearer, "").getResponse().getStatus()).isEqualTo(404);
        assertThat(settings(otherBearer).get("hasPhoto").asBoolean()).isFalse();
        mockMvc.perform(MockMvcRequestBuilders.delete("/v1/profile/photo").header(HttpHeaders.AUTHORIZATION, otherBearer)).andExpect(status().isNoContent());

        assertThat(content(bearer, "").getResponse().getStatus()).isEqualTo(200);
        assertThat(settings(bearer).get("hasPhoto").asBoolean()).isTrue();
    }
}
