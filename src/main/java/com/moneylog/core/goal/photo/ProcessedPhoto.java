package com.moneylog.core.goal.photo;

import java.util.Arrays;
import java.util.Objects;

public record ProcessedPhoto(byte[] display, byte[] thumbnail, int width, int height) {

    public long sizeBytes() {
        return (long) display.length + thumbnail.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProcessedPhoto that && width == that.width && height == that.height
                && Arrays.equals(display, that.display) && Arrays.equals(thumbnail, that.thumbnail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(display), Arrays.hashCode(thumbnail), width, height);
    }

    @Override
    public String toString() {
        return "ProcessedPhoto[display=" + display.length + " bytes, thumbnail=" + thumbnail.length + " bytes, width=" + width + ", height=" + height + "]";
    }
}
