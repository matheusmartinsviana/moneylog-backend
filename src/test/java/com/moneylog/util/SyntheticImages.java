package com.moneylog.util;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

public final class SyntheticImages {

    public static final String GPS_SENTINEL = "SENTINEL-GPS-LAT-00.0000";

    private SyntheticImages() {
    }

    public static byte[] jpeg(int width, int height, String label) {
        return encode(gradient(width, height, label, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    public static byte[] png(int width, int height, String label) {
        return encode(gradient(width, height, label, BufferedImage.TYPE_INT_RGB), "png");
    }

    public static byte[] pngWithAlpha(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, x < width / 2 ? 0xFF0000FF : 0x00000000);
            }
        }
        return encode(image, "png");
    }

    public static byte[] quadrantsJpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean left = x < width / 2;
                boolean top = y < height / 2;
                image.setRGB(x, y, top ? (left ? 0xFF0000 : 0x00FF00) : (left ? 0x0000FF : 0xFFFF00));
            }
        }
        return encode(image, "jpg");
    }

    public static byte[] withExif(byte[] jpeg, int orientation) {
        byte[] sentinel = (GPS_SENTINEL + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 2 * 12 + 4 + sentinel.length);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        tiff.putShort((short) 2);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        int textOffset = 8 + 2 + 2 * 12 + 4;
        tiff.putShort((short) 0x010E).putShort((short) 2).putInt(sentinel.length).putInt(textOffset);
        tiff.putInt(0);
        tiff.put(sentinel);
        byte[] exifPayload = concat("Exif\0\0".getBytes(StandardCharsets.US_ASCII), tiff.array());
        int length = exifPayload.length + 2;
        byte[] app1 = concat(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length}, exifPayload);
        return concat(new byte[] {(byte) 0xFF, (byte) 0xD8}, app1, slice(jpeg, 2));
    }

    public static byte[] pngBombHeader(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", new byte[] {0x78, (byte) 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01});
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    public static byte[] withTrailing(byte[] image, byte[] payload) {
        return concat(image, payload);
    }

    public static byte[] truncated(byte[] image, int keep) {
        return slice(image, 0, Math.min(keep, image.length));
    }

    public static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static BufferedImage gradient(int width, int height, String label, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        try {
            g.setPaint(new GradientPaint(0, 0, new Color(30, 90, 160), width, height, new Color(240, 180, 60)));
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, height / 8)));
            g.drawString(label, width / 10, height / 2);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("sem gravador de " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] slice(byte[] b, int from) {
        return slice(b, from, b.length);
    }

    private static byte[] slice(byte[] b, int from, int to) {
        return java.util.Arrays.copyOfRange(b, from, to);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
