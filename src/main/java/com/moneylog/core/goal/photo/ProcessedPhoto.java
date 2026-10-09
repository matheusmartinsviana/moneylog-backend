package com.moneylog.core.goal.photo;

/** Resultado do pipeline: só as versões processadas (o original nunca é guardado). {@code width}/{@code height} são os da exibição. */
public record ProcessedPhoto(byte[] display, byte[] thumbnail, int width, int height) {

    public long sizeBytes() {
        return (long) display.length + thumbnail.length;
    }
}
