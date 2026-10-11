package com.moneylog.core.goal.photo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoDimensionsExceededException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoInvalidImageException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoTooLargeException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoUnsupportedTypeException;

public final class PhotoImagePipeline {

    public static final int DISPLAY_MAX_SIDE = 1280;
    public static final int THUMBNAIL_MAX_SIDE = 320;

    private final PhotoLimits limits;
    private final Semaphore decoding = new Semaphore(1);

    public PhotoImagePipeline(PhotoLimits limits) {
        this.limits = limits;
    }

    private enum Format {
        JPEG("jpeg"), PNG("png");

        final String readerName;

        Format(String readerName) {
            this.readerName = readerName;
        }
    }

    public ProcessedPhoto process(byte[] raw) {
        if (raw == null || raw.length == 0) {
            throw new GoalPhotoInvalidImageException();
        }
        if (raw.length > limits.maxUploadBytes()) {
            throw new GoalPhotoTooLargeException(limits.maxUploadBytes());
        }
        Format format = detect(raw);
        if (format == null) {
            throw new GoalPhotoUnsupportedTypeException();
        }
        int[] size = readHeaderSize(raw, format);
        if (size[0] <= 0 || size[1] <= 0 || size[0] > limits.maxSide() || size[1] > limits.maxSide()
                || (long) size[0] * size[1] > limits.maxPixels()) {
            throw new GoalPhotoDimensionsExceededException();
        }
        int orientation = format == Format.JPEG ? ExifOrientation.read(raw) : 1;
        decoding.acquireUninterruptibly();
        try {
            BufferedImage decoded = decode(raw, format, size[0], size[1]);
            BufferedImage display = fit(decoded, orientation, DISPLAY_MAX_SIDE);
            BufferedImage thumbnail = fit(display, 1, THUMBNAIL_MAX_SIDE);
            return new ProcessedPhoto(encode(display), encode(thumbnail), display.getWidth(), display.getHeight());
        } finally {
            decoding.release();
        }
    }

    private static Format detect(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Format.JPEG;
        }
        int[] png = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (b.length >= png.length) {
            for (int i = 0; i < png.length; i++) {
                if ((b[i] & 0xFF) != png[i]) {
                    return null;
                }
            }
            return Format.PNG;
        }
        return null;
    }

    private static ImageReader reader(Format format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format.readerName);
        if (!readers.hasNext()) {
            throw new IllegalStateException("sem leitor de " + format.readerName + " na JDK");
        }
        return readers.next();
    }

    private static int[] readHeaderSize(byte[] raw, Format format) {
        ImageReader reader = reader(format);
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(raw))) {
            reader.setInput(in, true, true);
            return new int[] {reader.getWidth(0), reader.getHeight(0)};
        } catch (IOException | RuntimeException e) {
            throw new GoalPhotoInvalidImageException();
        } finally {
            reader.dispose();
        }
    }

    private static BufferedImage decode(byte[] raw, Format format, int width, int height) {
        ImageReader reader = reader(format);
        boolean[] warned = {false};
        reader.addIIOReadWarningListener((source, warning) -> warned[0] = true);
        BufferedImage image;
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(raw))) {
            reader.setInput(in, true, true);
            ImageReadParam param = reader.getDefaultReadParam();
            int step = Math.max(1, Math.max(width, height) / (2 * DISPLAY_MAX_SIDE));
            if (step > 1) {
                param.setSourceSubsampling(step, step, 0, 0);
            }
            image = reader.read(0, param);
        } catch (IOException | RuntimeException e) {
            throw new GoalPhotoInvalidImageException();
        } finally {
            reader.dispose();
        }
        if (warned[0] || image == null) {
            throw new GoalPhotoInvalidImageException();
        }
        return image;
    }

    private static BufferedImage fit(BufferedImage source, int orientation, int maxSide) {
        boolean swap = orientation >= 5;
        int orientedWidth = swap ? source.getHeight() : source.getWidth();
        int orientedHeight = swap ? source.getWidth() : source.getHeight();
        int longSide = Math.max(orientedWidth, orientedHeight);
        long targetWidth = orientedWidth;
        long targetHeight = orientedHeight;
        if (longSide > maxSide) {
            targetWidth = Math.max(1L, (long) orientedWidth * maxSide / longSide);
            targetHeight = Math.max(1L, (long) orientedHeight * maxSide / longSide);
        }
        BufferedImage current = source;
        while (Math.max(current.getWidth(), current.getHeight()) >= 2 * Math.max(targetWidth, targetHeight)
                && current.getWidth() >= 2 && current.getHeight() >= 2) {
            current = draw(current, 1, current.getWidth() / 2, current.getHeight() / 2);
        }
        int drawWidth = swap ? (int) targetHeight : (int) targetWidth;
        int drawHeight = swap ? (int) targetWidth : (int) targetHeight;
        return draw(current, orientation, drawWidth, drawHeight);
    }

    private static BufferedImage draw(BufferedImage source, int orientation, int scaledWidth, int scaledHeight) {
        boolean swap = orientation >= 5;
        BufferedImage out = new BufferedImage(swap ? scaledHeight : scaledWidth, swap ? scaledWidth : scaledHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, out.getWidth(), out.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.transform(orientationTransform(orientation, scaledWidth, scaledHeight));
            g.drawImage(source, 0, 0, scaledWidth, scaledHeight, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    static AffineTransform orientationTransform(int orientation, int w, int h) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
    }

    private static byte[] encode(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("sem gravador de jpeg na JDK");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.82f);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException | RuntimeException e) {
            throw new GoalPhotoInvalidImageException();
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
