package com.moneylog.core.goal.photo;

import static com.moneylog.util.SyntheticImages.GPS_SENTINEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoDimensionsExceededException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoInvalidImageException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoTooLargeException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoUnsupportedTypeException;
import com.moneylog.util.SyntheticImages;

@DisplayName("Pipeline de imagem das fotos das metas (T256, ADR-018): magic bytes, anti bomba, EXIF, polyglot")
class PhotoImagePipelineTest {

    private final PhotoImagePipeline pipeline = new PhotoImagePipeline(PhotoLimits.defaults());

    private static BufferedImage read(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    private static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    @Test
    @DisplayName("JPEG grande vira exibição de até 1280 px e miniatura de até 320 px, em JPEG, com a proporção preservada")
    void deveReduzirJpeg() throws IOException {
        ProcessedPhoto out = pipeline.process(SyntheticImages.jpeg(3000, 2000, "Carro de exemplo"));

        BufferedImage display = read(out.display());
        BufferedImage thumb = read(out.thumbnail());
        assertThat(isJpeg(out.display())).isTrue();
        assertThat(isJpeg(out.thumbnail())).isTrue();
        assertThat(display.getWidth()).isEqualTo(1280);
        assertThat(display.getHeight()).isEqualTo(853); // 2000 * 1280 / 3000
        assertThat(thumb.getWidth()).isEqualTo(320);
        assertThat(thumb.getHeight()).isEqualTo(213);
        assertThat(out.width()).isEqualTo(1280);
        assertThat(out.height()).isEqualTo(853);
        assertThat(out.sizeBytes()).isEqualTo(out.display().length + out.thumbnail().length);
    }

    @Test
    @DisplayName("imagem pequena não é ampliada; PNG vira JPEG; PNG com transparência é achatado sobre fundo branco")
    void naoDeveAmpliarEAchatarPng() throws IOException {
        ProcessedPhoto small = pipeline.process(SyntheticImages.png(200, 100, "Pequena"));
        assertThat(small.width()).isEqualTo(200);
        assertThat(small.height()).isEqualTo(100);
        assertThat(isJpeg(small.display())).isTrue();

        ProcessedPhoto alpha = pipeline.process(SyntheticImages.pngWithAlpha(100, 100));
        BufferedImage image = read(alpha.display());
        int transparentSide = image.getRGB(90, 50) & 0xFFFFFF;
        assertThat(transparentSide).isGreaterThan(0xF0F0F0); // branco, não preto
    }

    @Test
    @DisplayName("JPEG com EXIF (GPS e orientação 6): a saída não tem EXIF nem GPS e a foto sai girada 90 graus horários")
    void deveGirarERemoverExif() throws IOException {
        byte[] source = SyntheticImages.withExif(SyntheticImages.quadrantsJpeg(400, 200), 6);
        assertThat(contains(source, GPS_SENTINEL)).isTrue();

        ProcessedPhoto out = pipeline.process(source);

        assertThat(contains(out.display(), GPS_SENTINEL)).isFalse();
        assertThat(contains(out.thumbnail(), GPS_SENTINEL)).isFalse();
        assertThat(contains(out.display(), "Exif")).isFalse();
        BufferedImage display = read(out.display());
        assertThat(display.getWidth()).isEqualTo(200);
        assertThat(display.getHeight()).isEqualTo(400);
        // girar 90 graus horário: o canto inferior esquerdo da origem (azul) vai para o topo esquerdo; o superior esquerdo (vermelho) para o topo direito
        assertThat(dominant(display.getRGB(20, 20))).isEqualTo("blue");
        assertThat(dominant(display.getRGB(180, 20))).isEqualTo("red");
        assertThat(dominant(display.getRGB(20, 380))).isEqualTo("yellow");
        assertThat(dominant(display.getRGB(180, 380))).isEqualTo("green");
    }

    @Test
    @DisplayName("orientação 3 gira 180 graus e 1 não gira")
    void deveAplicarOutrasOrientacoes() throws IOException {
        BufferedImage rotated = read(pipeline.process(SyntheticImages.withExif(SyntheticImages.quadrantsJpeg(200, 100), 3)).display());
        assertThat(rotated.getWidth()).isEqualTo(200);
        assertThat(dominant(rotated.getRGB(10, 10))).isEqualTo("yellow"); // o inferior direito foi para o topo esquerdo

        BufferedImage same = read(pipeline.process(SyntheticImages.withExif(SyntheticImages.quadrantsJpeg(200, 100), 1)).display());
        assertThat(dominant(same.getRGB(10, 10))).isEqualTo("red");
    }

    @Test
    @DisplayName("arquivo .jpg com texto, HTML, SVG ou ZIP (magic bytes errados): GOAL_PHOTO_UNSUPPORTED_TYPE")
    void deveRecusarConteudoQueNaoEImagem() {
        byte[][] fakes = {
                SyntheticImages.text("isto não é uma foto"),
                SyntheticImages.text("<html><script>alert(1)</script></html>"),
                SyntheticImages.text("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"),
                {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00},
                {(byte) 0xFF, (byte) 0xD8}, // só dois bytes do JPEG
                {(byte) 0x89, 'P', 'N', 'G'}, // assinatura incompleta
        };
        for (byte[] fake : fakes) {
            assertThatThrownBy(() -> pipeline.process(fake)).isInstanceOf(GoalPhotoUnsupportedTypeException.class);
        }
    }

    @Test
    @DisplayName("PNG de poucos KB que declara dimensões acima de 24 MP: recusa pelo cabeçalho, sem decodificar nem alocar")
    void deveRecusarDecompressionBomb() {
        byte[] bomb = SyntheticImages.pngBombHeader(60_000, 60_000);
        assertThat(bomb.length).isLessThan(100);
        Runtime runtime = Runtime.getRuntime();
        long before = runtime.totalMemory() - runtime.freeMemory();

        assertThatThrownBy(() -> pipeline.process(bomb)).isInstanceOf(GoalPhotoDimensionsExceededException.class);
        // sem alocar o bitmap de 3,6 bilhões de pixels (nem os 24 MP): o heap quase não se mexe
        assertThat(runtime.totalMemory() - runtime.freeMemory() - before).isLessThan(64L * 1024 * 1024);
    }

    @Test
    @DisplayName("lado acima de 8000 px ou área acima de 24 MP, mesmo com cabeçalho válido: GOAL_PHOTO_DIMENSIONS_EXCEEDED")
    void deveRecusarDimensoesAcimaDoTeto() {
        assertThatThrownBy(() -> pipeline.process(SyntheticImages.pngBombHeader(9000, 10))).isInstanceOf(GoalPhotoDimensionsExceededException.class);
        assertThatThrownBy(() -> pipeline.process(SyntheticImages.pngBombHeader(7000, 4000))).isInstanceOf(GoalPhotoDimensionsExceededException.class);
    }

    @Test
    @DisplayName("JPEG válido com ZIP/script anexado depois do fim (polyglot): a saída não carrega o payload")
    void deveDescartarPayloadAnexado() throws IOException {
        byte[] payload = "PK\u0003\u0004<script>PAYLOAD-HOSTIL</script>".getBytes(StandardCharsets.ISO_8859_1);
        byte[] polyglot = SyntheticImages.withTrailing(SyntheticImages.jpeg(300, 200, "Polyglot"), payload);

        ProcessedPhoto out = pipeline.process(polyglot);

        assertThat(contains(out.display(), "PAYLOAD-HOSTIL")).isFalse();
        assertThat(contains(out.display(), "<script>")).isFalse();
        assertThat(contains(out.thumbnail(), "PAYLOAD-HOSTIL")).isFalse();
        assertThat(read(out.display())).isNotNull();
    }

    @Test
    @DisplayName("imagem truncada ou corrompida: GOAL_PHOTO_INVALID_IMAGE (sem detalhe interno)")
    void deveRecusarImagemQuebrada() {
        byte[] jpeg = SyntheticImages.jpeg(800, 600, "Truncada");
        byte[] png = SyntheticImages.png(800, 600, "Truncada");

        assertThatThrownBy(() -> pipeline.process(SyntheticImages.truncated(jpeg, jpeg.length / 2)))
                .isInstanceOf(GoalPhotoInvalidImageException.class).hasMessageNotContaining("Exception");
        assertThatThrownBy(() -> pipeline.process(SyntheticImages.truncated(png, png.length / 2))).isInstanceOf(GoalPhotoInvalidImageException.class);
        assertThatThrownBy(() -> pipeline.process(SyntheticImages.truncated(jpeg, 20))).isInstanceOf(GoalPhotoInvalidImageException.class);
        assertThatThrownBy(() -> pipeline.process(new byte[0])).isInstanceOf(GoalPhotoInvalidImageException.class);
        assertThatThrownBy(() -> pipeline.process(null)).isInstanceOf(GoalPhotoInvalidImageException.class);
    }

    @Test
    @DisplayName("arquivo acima do teto de upload: GOAL_PHOTO_TOO_LARGE, antes de qualquer leitura")
    void deveRecusarArquivoGrande() {
        PhotoImagePipeline small = new PhotoImagePipeline(new PhotoLimits(8, 40, 40L << 20, 1000, PhotoLimits.MAX_PIXELS, PhotoLimits.MAX_SIDE));
        assertThatThrownBy(() -> small.process(SyntheticImages.jpeg(800, 600, "Grande"))).isInstanceOf(GoalPhotoTooLargeException.class);
    }

    @Test
    @DisplayName("o nome do arquivo não existe para o pipeline: só os bytes entram")
    void soBytesEntram() {
        // a assinatura aceita apenas byte[]; qualquer nome, extensão ou MIME do cliente fica de fora por construção
        assertThat(pipeline.process(SyntheticImages.jpeg(100, 100, "x")).display()).isNotEmpty();
    }

    private static String dominant(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (r > 180 && g > 180 && b < 100) {
            return "yellow";
        }
        if (r > 180 && g < 100 && b < 100) {
            return "red";
        }
        if (g > 180 && r < 100 && b < 100) {
            return "green";
        }
        if (b > 180 && r < 100 && g < 100) {
            return "blue";
        }
        return "other(" + Integer.toHexString(rgb) + ")";
    }
}
