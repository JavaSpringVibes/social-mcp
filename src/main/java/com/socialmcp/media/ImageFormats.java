package com.socialmcp.media;

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Header-level handling of JPEG, PNG, GIF and WebP (SPEC §6.14, steps 7–9): sniffing the type from the first bytes,
 * reading dimensions and EXIF orientation, and stripping metadata losslessly. Pixel data is never decoded.
 */
public final class ImageFormats {

    public static final String JPEG = "image/jpeg";

    public static final String PNG = "image/png";

    public static final String GIF = "image/gif";

    public static final String WEBP = "image/webp";

    /**
     * The types this server can sniff, in the order they are reported.
     */
    public static final List<String> SNIFFABLE = List.of(JPEG, PNG, GIF, WEBP);

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static final Set<String> PNG_METADATA_CHUNKS = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");

    private static final int EXIF_ORIENTATION_TAG = 0x0112;

    private ImageFormats() {
    }

    /**
     * The MIME type from the first bytes, or null when the data is not one of the four supported formats.
     */
    public static @Nullable String sniff(byte[] data) {
        if (data.length >= 3 && u8(data, 0) == 0xFF && u8(data, 1) == 0xD8 && u8(data, 2) == 0xFF) {
            return JPEG;
        }
        if (data.length >= 8 && Arrays.equals(data, 0, 8, PNG_SIGNATURE, 0, 8)) {
            return PNG;
        }
        if (data.length >= 6 && (ascii(data, 0, 6).equals("GIF87a") || ascii(data, 0, 6).equals("GIF89a"))) {
            return GIF;
        }
        if (data.length >= 12 && ascii(data, 0, 4).equals("RIFF") && ascii(data, 8, 4).equals("WEBP")) {
            return WEBP;
        }
        return null;
    }

    /**
     * Reads the dimensions (and, for JPEG, the EXIF orientation) of sniffed data.
     */
    public static Header header(byte[] data, String mimeType) throws DamagedImageException {
        try {
            return switch (mimeType) {
                case JPEG -> jpegHeader(data);
                case PNG -> pngHeader(data);
                case GIF -> new Header(u16le(data, 6), u16le(data, 8), 1);
                case WEBP -> webpHeader(data);
                default -> throw new DamagedImageException("unsupported type " + mimeType);
            };
        } catch (IndexOutOfBoundsException ex) {
            throw new DamagedImageException("truncated header");
        }
    }

    /**
     * Removes metadata that would otherwise be published (SPEC §6.14, step 9). JPEG keeps a minimal orientation-only
     * EXIF segment when the orientation isn't 1; GIF is returned unchanged.
     */
    public static byte[] stripMetadata(byte[] data, String mimeType, int orientation) throws DamagedImageException {
        try {
            return switch (mimeType) {
                case JPEG -> stripJpeg(data, orientation);
                case PNG -> stripPng(data);
                case WEBP -> stripWebp(data);
                default -> data;
            };
        } catch (IndexOutOfBoundsException ex) {
            throw new DamagedImageException("truncated file");
        }
    }

    private static Header jpegHeader(byte[] data) throws DamagedImageException {
        int orientation = 1;
        int pos = 2;
        while (pos + 4 <= data.length) {
            if (u8(data, pos) != 0xFF) {
                throw new DamagedImageException("bad JPEG marker");
            }
            int marker = u8(data, pos + 1);
            if (marker == 0xFF) {
                pos++; // fill byte
                continue;
            }
            if (isStandalone(marker)) {
                pos += 2;
                continue;
            }
            int length = u16be(data, pos + 2);
            if (length < 2) {
                throw new DamagedImageException("bad JPEG segment length");
            }
            if (marker == 0xE1 && isExif(data, pos + 4, length - 2)) {
                orientation = exifOrientation(data, pos + 10, pos + 2 + length);
            }
            if (isStartOfFrame(marker)) {
                int height = u16be(data, pos + 5);
                int width = u16be(data, pos + 7);
                if (width == 0 || height == 0) {
                    throw new DamagedImageException("zero JPEG dimensions");
                }
                return new Header(width, height, orientation);
            }
            if (marker == 0xDA) {
                break; // start of scan without a frame header
            }
            pos += 2 + length;
        }
        throw new DamagedImageException("no JPEG frame header");
    }

    private static boolean isStandalone(int marker) {
        return marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8);
    }

    // --- JPEG ---

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static boolean isExif(byte[] data, int offset, int length) {
        return length >= 6 && ascii(data, offset, 4).equals("Exif") && data[offset + 4] == 0 && data[offset + 5] == 0;
    }

    /**
     * Reads tag 0x0112 from IFD0 of a TIFF structure starting at {@code tiff}; 1 when absent or unreadable.
     */
    static int exifOrientation(byte[] data, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean little = ascii(data, tiff, 2).equals("II");
        if (!little && !ascii(data, tiff, 2).equals("MM")) {
            return 1;
        }
        long ifd = tiff + u32(data, tiff + 4, little);
        if (ifd + 2 > end) {
            return 1;
        }
        int entries = u16(data, (int) ifd, little);
        for (int i = 0; i < entries; i++) {
            int entry = (int) ifd + 2 + i * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (u16(data, entry, little) == EXIF_ORIENTATION_TAG) {
                int value = u16(data, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static byte[] stripJpeg(byte[] data, int orientation) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, 2); // SOI
        boolean orientationWritten = orientation == 1;
        boolean first = true;
        int pos = 2;
        while (pos + 4 <= data.length) {
            int marker = u8(data, pos + 1);
            if (u8(data, pos) != 0xFF || marker == 0xDA) {
                break; // start of scan (or unexpected data): copy the rest verbatim
            }
            if (marker == 0xFF) {
                pos++;
                continue;
            }
            if (isStandalone(marker)) {
                out.write(data, pos, 2);
                pos += 2;
                continue;
            }
            int segmentLength = 2 + u16be(data, pos + 2);
            boolean isApp0 = marker == 0xE0;
            if (!orientationWritten && !(first && isApp0)) {
                out.writeBytes(orientationSegment(orientation));
                orientationWritten = true;
            }
            if (marker != 0xE1 && marker != 0xED && marker != 0xFE) {
                out.write(data, pos, segmentLength);
            }
            first = false;
            pos += segmentLength;
        }
        if (!orientationWritten) {
            out.writeBytes(orientationSegment(orientation));
        }
        out.write(data, pos, data.length - pos);
        return out.toByteArray();
    }

    /**
     * A minimal big-endian EXIF APP1 segment holding only IFD0 tag 0x0112 (Orientation).
     */
    static byte[] orientationSegment(int orientation) {
        return new byte[]{(byte) 0xFF, (byte) 0xE1, 0, 34, // marker and length
                'E', 'x', 'i', 'f', 0, 0, // EXIF identifier
                'M', 'M', 0, 42, 0, 0, 0, 8, // TIFF header, IFD0 at offset 8
                0, 1, // one entry
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, // Orientation, SHORT, count 1
                0, 0, 0, 0}; // no next IFD
    }

    private static Header pngHeader(byte[] data) throws DamagedImageException {
        if (!ascii(data, 12, 4).equals("IHDR")) {
            throw new DamagedImageException("no PNG IHDR chunk");
        }
        long width = u32(data, 16, false);
        long height = u32(data, 20, false);
        if (width == 0 || height == 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
            throw new DamagedImageException("bad PNG dimensions");
        }
        return new Header((int) width, (int) height, 1);
    }

    private static byte[] stripPng(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, 8);
        int pos = 8;
        while (pos + 12 <= data.length) {
            long length = u32(data, pos, false);
            int total = (int) (12 + length);
            if (pos + total > data.length) {
                break;
            }
            if (!PNG_METADATA_CHUNKS.contains(ascii(data, pos + 4, 4))) {
                out.write(data, pos, total);
            }
            pos += total;
        }
        out.write(data, pos, data.length - pos);
        return out.toByteArray();
    }

    // --- PNG ---

    private static Header webpHeader(byte[] data) throws DamagedImageException {
        String chunk = ascii(data, 12, 4);
        int payload = 20;
        return switch (chunk) {
            case "VP8 " -> {
                if (u8(data, payload + 3) != 0x9D || u8(data, payload + 4) != 0x01 || u8(data, payload + 5) != 0x2A) {
                    throw new DamagedImageException("bad VP8 start code");
                }
                yield new Header(u16le(data, payload + 6) & 0x3FFF, u16le(data, payload + 8) & 0x3FFF, 1);
            }
            case "VP8L" -> {
                if (u8(data, payload) != 0x2F) {
                    throw new DamagedImageException("bad VP8L signature");
                }
                long bits = u32(data, payload + 1, true);
                yield new Header((int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1, 1);
            }
            case "VP8X" -> new Header(u24le(data, payload + 4) + 1, u24le(data, payload + 7) + 1, 1);
            default -> throw new DamagedImageException("unknown WebP chunk " + chunk);
        };
    }

    private static byte[] stripWebp(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, 12);
        int pos = 12;
        while (pos + 8 <= data.length) {
            String fourcc = ascii(data, pos, 4);
            long size = u32(data, pos + 4, true);
            int total = (int) (8 + size + (size & 1));
            if (pos + total > data.length) {
                total = data.length - pos; // unpadded final chunk
            }
            if (!fourcc.equals("EXIF") && !fourcc.equals("XMP ")) {
                int start = out.size();
                out.write(data, pos, total);
                if (fourcc.equals("VP8X")) {
                    byte[] written = out.toByteArray();
                    written[start + 8] &= (byte) ~0x0C; // clear the EXIF (0x08) and XMP (0x04) flags
                    out.reset();
                    out.writeBytes(written);
                }
            }
            pos += total;
        }
        byte[] result = out.toByteArray();
        long riffSize = result.length - 8L;
        result[4] = (byte) riffSize;
        result[5] = (byte) (riffSize >> 8);
        result[6] = (byte) (riffSize >> 16);
        result[7] = (byte) (riffSize >> 24);
        return result;
    }

    // --- WebP ---

    private static int u8(byte[] data, int offset) {
        return data[offset] & 0xFF;
    }

    private static int u16be(byte[] data, int offset) {
        return (u8(data, offset) << 8) | u8(data, offset + 1);
    }

    // --- Byte helpers ---

    private static int u16le(byte[] data, int offset) {
        return u8(data, offset) | (u8(data, offset + 1) << 8);
    }

    private static int u24le(byte[] data, int offset) {
        return u8(data, offset) | (u8(data, offset + 1) << 8) | (u8(data, offset + 2) << 16);
    }

    private static int u16(byte[] data, int offset, boolean little) {
        return little ? u16le(data, offset) : u16be(data, offset);
    }

    private static long u32(byte[] data, int offset, boolean little) {
        long b0 = u8(data, offset);
        long b1 = u8(data, offset + 1);
        long b2 = u8(data, offset + 2);
        long b3 = u8(data, offset + 3);
        return little ? b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static String ascii(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.ISO_8859_1);
    }

    /**
     * Width and height as stored, plus the EXIF orientation (1 when absent or not a JPEG).
     */
    public record Header(int width, int height, int orientation) {

        /**
         * The size as displayed: orientations 5–8 rotate by 90°, so width and height swap.
         */
        public int displayWidth() {
            return orientation >= 5 && orientation <= 8 ? height : width;
        }

        public int displayHeight() {
            return orientation >= 5 && orientation <= 8 ? width : height;
        }

    }

    /**
     * Thrown when a header can't be read; reported as "damaged or truncated".
     */
    public static final class DamagedImageException extends Exception {

        private static final long serialVersionUID = 1L;

        DamagedImageException(String message) {
            super(message);
        }

    }

}
