package com.moneylog.core.goal.photo;

/**
 * Lê só a tag de orientação (0x0112) do EXIF de um JPEG, com todos os acessos limitados ao tamanho do arquivo. O resultado é usado para girar
 * a imagem antes de o EXIF ser descartado na recodificação. Qualquer coisa fora do esperado devolve 1 (sem rotação): nunca lança.
 */
final class ExifOrientation {

    private static final int MAX_ENTRIES = 256;

    private ExifOrientation() {
    }

    /** Valor de 1 a 8 (padrão EXIF); 1 se ausente ou ilegível. */
    static int read(byte[] jpeg) {
        int length = jpeg.length;
        int i = 2; // depois do SOI (FFD8)
        while (i + 4 <= length) {
            if ((jpeg[i] & 0xFF) != 0xFF) {
                return 1;
            }
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xFF) {
                i++;
                continue;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                i += 2;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return 1; // início dos dados da imagem: o EXIF vem antes
            }
            int segmentLength = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            int start = i + 4;
            int end = i + 2 + segmentLength;
            if (segmentLength < 2 || end > length) {
                return 1;
            }
            if (marker == 0xE1 && end - start >= 14 && isExifHeader(jpeg, start)) {
                return tiffOrientation(jpeg, start + 6, end);
            }
            i = end;
        }
        return 1;
    }

    private static boolean isExifHeader(byte[] b, int at) {
        return b[at] == 'E' && b[at + 1] == 'x' && b[at + 2] == 'i' && b[at + 3] == 'f' && b[at + 4] == 0 && b[at + 5] == 0;
    }

    private static int tiffOrientation(byte[] b, int tiff, int end) {
        if (end - tiff < 8) {
            return 1;
        }
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            little = true;
        } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            little = false;
        } else {
            return 1;
        }
        if (u16(b, tiff + 2, little) != 42) {
            return 1;
        }
        long ifd = u32(b, tiff + 4, little);
        long position = tiff + ifd;
        if (ifd < 8 || position + 2 > end) {
            return 1;
        }
        int entries = Math.min(u16(b, (int) position, little), MAX_ENTRIES);
        for (int k = 0; k < entries; k++) {
            long entry = position + 2L + 12L * k;
            if (entry + 12 > end) {
                return 1;
            }
            if (u16(b, (int) entry, little) == 0x0112) {
                int type = u16(b, (int) entry + 2, little);
                int value = u16(b, (int) entry + 8, little);
                return type == 3 && value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] b, int at, boolean little) {
        int x = b[at] & 0xFF;
        int y = b[at + 1] & 0xFF;
        return little ? (y << 8) | x : (x << 8) | y;
    }

    private static long u32(byte[] b, int at, boolean little) {
        long a = u16(b, at, little);
        long c = u16(b, at + 2, little);
        return little ? (c << 16) | a : (a << 16) | c;
    }
}
