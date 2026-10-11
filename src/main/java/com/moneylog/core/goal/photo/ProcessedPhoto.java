package com.moneylog.core.goal.photo;

public record ProcessedPhoto(byte[] display, byte[] thumbnail, int width, int height) {

    public long sizeBytes() {
        return (long) display.length + thumbnail.length;
    }
}
