package com.moneylog.infra.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;
import com.moneylog.util.SyntheticImages;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Fotos e destaque das metas de sonho: envio higienizado, limites, isolamento, idempotência e destaque único (T255, T257)")
class GoalPhotosIT extends E4ItBase {

    private static final String SONHO = "{\"name\":\"Carro de exemplo\",\"target\":\"10000.00\",\"priority\":\"MEDIUM\"}";

    private final byte[] jpeg = SyntheticImages.jpeg(1600, 1000, "Carro de exemplo");

    private JsonNode goal(String json) throws Exception {
        return createdBody(post("/v1/goals", json));
    }

    private String sonho() throws Exception {
        return goal(SONHO).get("id").asString();
    }

    private MockMultipartHttpServletRequestBuilder upload(String bearerToken, String goalId, String filename, byte[] content, UUID key) {
        MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart("/v1/goals/" + goalId + "/photos")
                .file(new MockMultipartFile("file", filename, "image/jpeg", content)).header(HttpHeaders.AUTHORIZATION, bearerToken);
        if (key != null) {
            request.header("Idempotency-Key", key.toString());
        }
        return request;
    }

    private JsonNode uploaded(String goalId, byte[] content) throws Exception {
        MvcResult result = mockMvc.perform(upload(bearer, goalId, "foto.jpg", content, null)).andExpect(status().isCreated())
                .andExpect(ContractValidation.responseOnly()).andReturn();
        JsonNode photo = body(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/goals/" + goalId + "/photos/" + photo.get("id").asString());
        return photo;
    }

    private void rejected(String goalId, String filename, byte[] content, int status, String code) throws Exception {
        mockMvc.perform(upload(bearer, goalId, filename, content, null)).andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
                .andExpect(ContractValidation.responseOnly());
    }

    private MvcResult content(String bearerToken, String goalId, String photoId, String query) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get("/v1/goals/" + goalId + "/photos/" + photoId + "/content" + query)
                .header(HttpHeaders.AUTHORIZATION, bearerToken)).andReturn();
    }

    private String otherUserBearer() {
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        return auth.bearer(other);
    }

    private long count(String table, String goalId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE goal_id = ?::uuid", Long.class, goalId);
    }

    @Test
    @DisplayName("o envio devolve 201 + Location e a foto (id, position, width, height); a meta passa a trazer photos e coverPhotoId; os bytes ficam em tabela separada")
    void deveEnviarELer() throws Exception {
        String id = sonho();
        JsonNode empty = okBody(get("/v1/goals/" + id));
        assertThat(empty.get("photos")).isEmpty();
        assertThat(empty.get("coverPhotoId").isNull()).isTrue();
        assertThat(empty.get("featured").asBoolean()).isFalse();

        JsonNode first = uploaded(id, jpeg);
        JsonNode second = uploaded(id, SyntheticImages.png(400, 300, "Segunda"));

        assertThat(first.get("position").asInt()).isZero();
        assertThat(first.get("width").asInt()).isEqualTo(1280);
        assertThat(first.get("height").asInt()).isEqualTo(800);
        assertThat(second.get("position").asInt()).isEqualTo(1);
        JsonNode goal = okBody(get("/v1/goals/" + id));
        assertThat(goal.get("coverPhotoId").asString()).isEqualTo(first.get("id").asString());
        assertThat(goal.get("photos")).hasSize(2);
        assertThat(goal.get("photos").get(1).get("id").asString()).isEqualTo(second.get("id").asString());
        assertThat(count("goal_photos", id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_photo_blobs b JOIN goal_photos p ON p.id = b.photo_id WHERE p.goal_id = ?::uuid",
                Long.class, id)).isEqualTo(2);
        assertThat(okBody(get("/v1/goals?featured=false")).get("items")).hasSize(1);
        assertThat(okBody(get("/v1/goals?featured=true")).get("items")).isEmpty();
    }

    @Test
    @DisplayName("conteúdo: image/jpeg, Cache-Control no-store, nosniff; thumb tem até 320 px; sem Bearer é 401; variante inválida é 400")
    void deveEntregarOConteudoAutenticado() throws Exception {
        String id = sonho();
        String photoId = uploaded(id, jpeg).get("id").asString();

        MvcResult display = content(bearer, id, photoId, "");
        assertThat(display.getResponse().getStatus()).isEqualTo(200);
        assertThat(display.getResponse().getContentType()).isEqualTo("image/jpeg");
        assertThat(display.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(display.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(display.getResponse().getContentAsByteArray()).startsWith((byte) 0xFF, (byte) 0xD8);
        MvcResult thumb = content(bearer, id, photoId, "?variant=thumb");
        assertThat(thumb.getResponse().getStatus()).isEqualTo(200);
        var thumbImage = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(thumb.getResponse().getContentAsByteArray()));
        assertThat(thumbImage.getWidth()).isEqualTo(320);
        assertThat(thumb.getResponse().getContentAsByteArray().length).isLessThan(display.getResponse().getContentAsByteArray().length);

        mockMvc.perform(MockMvcRequestBuilders.get("/v1/goals/" + id + "/photos/" + photoId + "/content?token=" + bearer.replace("Bearer ", "")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/goals/" + id + "/photos/" + photoId + "/content?variant=original")
                .header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/goals/" + id + "/photos/" + UUID.randomUUID() + "/content").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GOAL_PHOTO_NOT_FOUND"));
    }

    @Test
    @DisplayName("EXIF com GPS é removido: nem a exibição nem a miniatura servidas carregam o marcador, e a foto sai girada pela orientação")
    void deveRemoverExifGps() throws Exception {
        String id = sonho();
        byte[] withGps = SyntheticImages.withExif(SyntheticImages.quadrantsJpeg(600, 300), 6);
        JsonNode photo = uploaded(id, withGps);
        assertThat(photo.get("width").asInt()).isEqualTo(300);
        assertThat(photo.get("height").asInt()).isEqualTo(600);

        for (String variant : new String[] {"", "?variant=thumb"}) {
            byte[] served = content(bearer, id, photo.get("id").asString(), variant).getResponse().getContentAsByteArray();
            String text = new String(served, StandardCharsets.ISO_8859_1);
            assertThat(text).doesNotContain(SyntheticImages.GPS_SENTINEL).doesNotContain("Exif");
        }
    }

    @Test
    @DisplayName("arquivo falso renomeado, HTML/SVG/ZIP com extensão de imagem, imagem corrompida, pixel-bomb e imagem enorme: rejeitados sem 500 e sem gravar nada")
    void deveRejeitarArquivosHostis() throws Exception {
        String id = sonho();
        rejected(id, "foto.jpg", SyntheticImages.text("isto é só texto"), 422, "GOAL_PHOTO_UNSUPPORTED_TYPE");
        rejected(id, "foto.png", SyntheticImages.text("<html><script>alert(1)</script></html>"), 422, "GOAL_PHOTO_UNSUPPORTED_TYPE");
        rejected(id, "foto.jpeg", SyntheticImages.text("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>1</script></svg>"), 422,
                "GOAL_PHOTO_UNSUPPORTED_TYPE");
        rejected(id, "foto.jpg", new byte[] {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00}, 422, "GOAL_PHOTO_UNSUPPORTED_TYPE");
        rejected(id, "foto.jpg", SyntheticImages.truncated(jpeg, 40), 422, "GOAL_PHOTO_INVALID_IMAGE");
        rejected(id, "foto.png", SyntheticImages.pngBombHeader(60_000, 60_000), 422, "GOAL_PHOTO_DIMENSIONS_EXCEEDED");
        rejected(id, "foto.jpg", new byte[(int) (8L * 1024 * 1024) + 1], 422, "GOAL_PHOTO_TOO_LARGE");
        rejected(id, "foto.jpg", new byte[0], 422, "GOAL_PHOTO_INVALID_IMAGE");

        assertThat(count("goal_photos", id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_photo_blobs WHERE user_id = ?", Long.class, userId)).isZero();
        assertThat(okBody(get("/v1/goals/" + id)).get("photos")).isEmpty();
    }

    @Test
    @DisplayName("arquivo acima do teto do container (21 MB): 422 GOAL_PHOTO_TOO_LARGE, não VAULT_IMPORT_TOO_LARGE")
    void deveRecusarAcimaDoTetoDoContainer() throws Exception {
        String id = sonho();
        rejected(id, "foto.jpg", new byte[22 * 1024 * 1024], 422, "GOAL_PHOTO_TOO_LARGE");
    }

    @Test
    @DisplayName("polyglot: JPEG com script/ZIP anexado é aceito e o payload não é servido")
    void deveDescartarPolyglot() throws Exception {
        String id = sonho();
        byte[] payload = "PK\u0003\u0004<script>PAYLOAD-HOSTIL</script>".getBytes(StandardCharsets.ISO_8859_1);
        JsonNode photo = uploaded(id, SyntheticImages.withTrailing(jpeg, payload));

        byte[] served = content(bearer, id, photo.get("id").asString(), "").getResponse().getContentAsByteArray();
        assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain("PAYLOAD-HOSTIL").doesNotContain("<script>");
    }

    @Test
    @DisplayName("path traversal no nome do arquivo (../../x.jpg): a foto válida é aceita e o nome não vira caminho nem aparece no log")
    void deveIgnorarONomeDoArquivo(CapturedOutput output) throws Exception {
        String id = sonho();
        MvcResult result = mockMvc.perform(upload(bearer, id, "../../etc/segredo-do-nome.jpg", jpeg, null)).andExpect(status().isCreated())
                .andReturn();

        assertThat(body(result).get("id").asString()).isNotBlank();
        assertThat(output.getAll()).doesNotContain("segredo-do-nome").doesNotContain("../").doesNotContain(SyntheticImages.GPS_SENTINEL);
        assertThat(output.getAll()).contains("Foto de meta enviada");
    }

    @Test
    @DisplayName("a 9ª foto da meta é 422 GOAL_PHOTO_LIMIT_REACHED e nada é gravado")
    void deveLimitarOitoPorMeta() throws Exception {
        String id = sonho();
        byte[] small = SyntheticImages.jpeg(200, 150, "p");
        for (int i = 0; i < 8; i++) {
            uploaded(id, small);
        }

        rejected(id, "nona.jpg", small, 422, "GOAL_PHOTO_LIMIT_REACHED");

        assertThat(count("goal_photos", id)).isEqualTo(8);
    }

    @Test
    @DisplayName("cota do usuário: 40 fotos em qualquer meta, ou 40 MB, bloqueiam o envio com 422 GOAL_PHOTO_QUOTA_EXCEEDED")
    void deveLimitarPorUsuario() throws Exception {
        String id = sonho();
        for (int g = 0; g < 5; g++) {
            String other = g == 0 ? id : sonho();
            for (int p = 0; p < 8; p++) {
                insertPhotoRow(other, p, 1_000);
            }
        }
        byte[] small = SyntheticImages.jpeg(200, 150, "p");
        String free = sonho();
        rejected(free, "quarenta-e-um.jpg", small, 422, "GOAL_PHOTO_QUOTA_EXCEEDED");
        committed(() -> jdbc.update("DELETE FROM goal_photos WHERE user_id = ?", userId));

        insertPhotoRow(free, 0, 40L * 1024 * 1024 - 10);
        rejected(free, "mb.jpg", small, 422, "GOAL_PHOTO_QUOTA_EXCEEDED");
        committed(() -> jdbc.update("DELETE FROM goal_photos WHERE user_id = ?", userId));

        uploaded(free, small);
    }

    private void insertPhotoRow(String goalId, int position, long sizeBytes) {
        committed(() -> jdbc.update("INSERT INTO goal_photos (id, user_id, goal_id, position, width, height, size_bytes, created_at, updated_at)"
                + " VALUES (?, ?, ?::uuid, ?, 10, 10, ?, ?, ?)", UUID.randomUUID(), userId, goalId, position, sizeBytes,
                Timestamp.from(Instant.parse("2026-10-10T12:00:00Z")), Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"))));
    }

    @Test
    @DisplayName("meta que não é de guardar dinheiro (DEBT_PAYOFF): 422 GOAL_PHOTOS_KIND_NOT_SUPPORTED no envio e 422 GOAL_FEATURED_KIND_NOT_SUPPORTED no destaque; photos vem vazio")
    void soSavings() throws Exception {
        JsonNode payoff = goal("{\"kind\":\"DEBT_PAYOFF\"}");
        String id = payoff.get("id").asString();
        assertThat(payoff.get("photos")).isEmpty();

        rejected(id, "foto.jpg", jpeg, 422, "GOAL_PHOTOS_KIND_NOT_SUPPORTED");
        mockMvc.perform(put("/v1/goals/" + id + "/featured", "{\"featured\":true}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GOAL_FEATURED_KIND_NOT_SUPPORTED")).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("o usuário B recebe 404 ao ler, enviar, reordenar, definir capa, remover ou destacar a meta/foto de A, e nada muda")
    void deveIsolarUsuarios() throws Exception {
        String id = sonho();
        String photoId = uploaded(id, jpeg).get("id").asString();
        String other = otherUserBearer();

        assertThat(content(other, id, photoId, "").getResponse().getStatus()).isEqualTo(404);
        mockMvc.perform(upload(other, id, "x.jpg", jpeg, null)).andExpect(status().isNotFound());
        mockMvc.perform(putAs(other, "/v1/goals/" + id + "/photos/order", "{\"photoIds\":[\"" + photoId + "\"]}")).andExpect(status().isNotFound());
        mockMvc.perform(putAs(other, "/v1/goals/" + id + "/photos/" + photoId + "/cover", "")).andExpect(status().isNotFound());
        mockMvc.perform(MockMvcRequestBuilders.delete("/v1/goals/" + id + "/photos/" + photoId).header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
        mockMvc.perform(putAs(other, "/v1/goals/" + id + "/featured", "{\"featured\":true}")).andExpect(status().isNotFound());

        assertThat(count("goal_photos", id)).isEqualTo(1);
        assertThat(okBody(get("/v1/goals/" + id)).get("featured").asBoolean()).isFalse();
        String otherGoal = createdBody(postAs(other, "/v1/goals", SONHO)).get("id").asString();
        assertThat(content(other, otherGoal, photoId, "").getResponse().getStatus()).isEqualTo(404);
        assertThat(content(bearer, id, photoId, "").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Idempotency-Key: repetir a chave com o mesmo arquivo devolve a mesma foto e uma só linha; outro arquivo com a mesma chave é 409 IDEMPOTENCY_KEY_REUSED")
    void deveSerIdempotente() throws Exception {
        String id = sonho();
        UUID key = UUID.randomUUID();

        JsonNode first = body(mockMvc.perform(upload(bearer, id, "a.jpg", jpeg, key)).andExpect(status().isCreated()).andReturn());
        JsonNode again = body(mockMvc.perform(upload(bearer, id, "b.jpg", jpeg, key)).andExpect(status().isCreated()).andReturn());

        assertThat(again.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(count("goal_photos", id)).isEqualTo(1);
        mockMvc.perform(upload(bearer, id, "c.jpg", SyntheticImages.png(300, 300, "outra"), key)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("goal_photos", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("reordenar, capa e remover devolvem a meta atualizada; remover a capa promove a próxima e os bytes somem; ordem com id faltando ou duplicado é 422 GOAL_PHOTOS_ORDER_MISMATCH")
    void deveOrdenarDefinirCapaERemover() throws Exception {
        String id = sonho();
        byte[] small = SyntheticImages.jpeg(300, 200, "p");
        String a = uploaded(id, small).get("id").asString();
        String b = uploaded(id, small).get("id").asString();
        String c = uploaded(id, small).get("id").asString();

        JsonNode reordered = okBody(put("/v1/goals/" + id + "/photos/order", "{\"photoIds\":[\"" + c + "\",\"" + a + "\",\"" + b + "\"]}"));
        assertThat(ids(reordered)).containsExactly(c, a, b);
        assertThat(reordered.get("coverPhotoId").asString()).isEqualTo(c);
        assertThat(reordered.get("photos").get(2).get("position").asInt()).isEqualTo(2);

        mockMvc.perform(put("/v1/goals/" + id + "/photos/order", "{\"photoIds\":[\"" + c + "\",\"" + a + "\"]}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GOAL_PHOTOS_ORDER_MISMATCH")).andExpect(ContractValidation.conforms());
        mockMvc.perform(put("/v1/goals/" + id + "/photos/order", "{\"photoIds\":[\"" + c + "\",\"" + c + "\",\"" + a + "\"]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("GOAL_PHOTOS_ORDER_MISMATCH"));
        assertThat(ids(okBody(get("/v1/goals/" + id)))).containsExactly(c, a, b);

        JsonNode covered = okBody(put("/v1/goals/" + id + "/photos/" + b + "/cover", ""));
        assertThat(ids(covered)).containsExactly(b, c, a);
        assertThat(covered.get("coverPhotoId").asString()).isEqualTo(b);

        JsonNode removed = okBody(MockMvcRequestBuilders.delete("/v1/goals/" + id + "/photos/" + b).header(HttpHeaders.AUTHORIZATION, bearer));
        assertThat(ids(removed)).containsExactly(c, a);
        assertThat(removed.get("coverPhotoId").asString()).isEqualTo(c);
        assertThat(content(bearer, id, b, "").getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_photo_blobs WHERE photo_id = ?::uuid", Long.class, b)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_photo_blobs WHERE user_id = ?", Long.class, userId)).isEqualTo(2);
        mockMvc.perform(MockMvcRequestBuilders.delete("/v1/goals/" + id + "/photos/" + b).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GOAL_PHOTO_NOT_FOUND"));
    }

    private static java.util.List<String> ids(JsonNode goal) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        goal.get("photos").forEach(p -> ids.add(p.get("id").asString()));
        return ids;
    }

    @Test
    @DisplayName("uma só meta em destaque: marcar B desmarca A (resposta traz B); o filtro featured=true devolve só B; arquivar B limpa o destaque; meta arquivada é 422 GOAL_ARCHIVED")
    void deveTerUmaSoMetaEmDestaque() throws Exception {
        String a = sonho();
        String b = goal("{\"name\":\"Viagem de exemplo\",\"target\":\"5000.00\",\"priority\":\"LOW\"}").get("id").asString();

        assertThat(okBody(put("/v1/goals/" + a + "/featured", "{\"featured\":true}")).get("featured").asBoolean()).isTrue();
        JsonNode moved = okBody(put("/v1/goals/" + b + "/featured", "{\"featured\":true}"));

        assertThat(moved.get("id").asString()).isEqualTo(b);
        assertThat(moved.get("featured").asBoolean()).isTrue();
        assertThat(okBody(get("/v1/goals/" + a)).get("featured").asBoolean()).isFalse();
        JsonNode featured = okBody(get("/v1/goals?featured=true"));
        assertThat(featured.get("items")).hasSize(1);
        assertThat(featured.get("items").get(0).get("id").asString()).isEqualTo(b);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goals WHERE user_id = ? AND featured", Long.class, userId)).isEqualTo(1);

        okBody(MockMvcRequestBuilders.post("/v1/goals/" + b + "/archive").header(HttpHeaders.AUTHORIZATION, bearer));
        assertThat(okBody(get("/v1/goals/" + b)).get("featured").asBoolean()).isFalse();
        mockMvc.perform(put("/v1/goals/" + b + "/featured", "{\"featured\":true}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GOAL_ARCHIVED")).andExpect(ContractValidation.conforms());

        assertThat(okBody(put("/v1/goals/" + a + "/featured", "{\"featured\":true}")).get("featured").asBoolean()).isTrue();
        assertThat(okBody(put("/v1/goals/" + a + "/featured", "{\"featured\":false}")).get("featured").asBoolean()).isFalse();
        assertThat(okBody(get("/v1/goals?featured=true")).get("items")).isEmpty();
    }

    @Test
    @DisplayName("o destaque de um usuário não afeta o de outro (o índice único é por usuário)")
    void destaqueEPorUsuario() throws Exception {
        String a = sonho();
        String other = otherUserBearer();
        String otherGoal = createdBody(postAs(other, "/v1/goals", SONHO)).get("id").asString();

        okBody(put("/v1/goals/" + a + "/featured", "{\"featured\":true}"));
        mockMvc.perform(putAs(other, "/v1/goals/" + otherGoal + "/featured", "{\"featured\":true}")).andExpect(status().isOk());

        assertThat(okBody(get("/v1/goals/" + a)).get("featured").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goals WHERE featured", Long.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("sem Bearer, as rotas de foto e destaque respondem 401")
    void semTokenE401() throws Exception {
        String id = sonho();
        UUID photo = UUID.randomUUID();
        mockMvc.perform(MockMvcRequestBuilders.multipart("/v1/goals/" + id + "/photos").file(new MockMultipartFile("file", "a.jpg", "image/jpeg", jpeg)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/goals/" + id + "/photos/" + photo + "/content")).andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.put("/v1/goals/" + id + "/featured").contentType("application/json").content("{\"featured\":true}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(MockMvcRequestBuilders.delete("/v1/goals/" + id + "/photos/" + photo)).andExpect(status().isUnauthorized());
    }
}
