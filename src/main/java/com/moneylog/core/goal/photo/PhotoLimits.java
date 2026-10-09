package com.moneylog.core.goal.photo;

/**
 * Limites das fotos das metas (configuráveis em {@code moneylog.goals.photos}; ADR-018).
 *
 * @param maxPerGoal       fotos por meta
 * @param maxPerUser       fotos por usuário
 * @param maxBytesPerUser  bytes guardados por usuário (exibição + miniatura)
 * @param maxUploadBytes   tamanho do arquivo enviado
 * @param maxPixels        megapixels aceitos (largura x altura), conferidos no cabeçalho antes de decodificar
 * @param maxSide          maior lado aceito, em pixels
 */
public record PhotoLimits(int maxPerGoal, int maxPerUser, long maxBytesPerUser, long maxUploadBytes, long maxPixels, int maxSide) {

    public static final int MAX_PER_GOAL = 8;
    public static final int MAX_PER_USER = 40;
    public static final long MAX_BYTES_PER_USER = 40L * 1024 * 1024;
    public static final long MAX_UPLOAD_BYTES = 8L * 1024 * 1024;
    public static final long MAX_PIXELS = 24_000_000L;
    public static final int MAX_SIDE = 8000;

    public PhotoLimits {
        if (maxPerGoal <= 0 || maxPerUser <= 0 || maxBytesPerUser <= 0 || maxUploadBytes <= 0 || maxPixels <= 0 || maxSide <= 0) {
            throw new IllegalArgumentException("os limites das fotos precisam ser positivos");
        }
    }

    public static PhotoLimits defaults() {
        return new PhotoLimits(MAX_PER_GOAL, MAX_PER_USER, MAX_BYTES_PER_USER, MAX_UPLOAD_BYTES, MAX_PIXELS, MAX_SIDE);
    }
}
