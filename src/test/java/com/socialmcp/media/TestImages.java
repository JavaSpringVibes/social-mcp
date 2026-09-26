package com.socialmcp.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

/**
 * Image fixtures for tests. Real JPEG, PNG and GIF files come from {@link ImageIO}; metadata segments and WebP files
 * are built byte by byte, since the loader only reads headers.
 */
public final class TestImages {

	/** Marker text placed in GPS and XMP metadata, so tests can check it was removed. */
	public static final String SECRET = "SECRET-LOCATION";

	private TestImages() {
	}

	public static byte[] jpeg(int width, int height) {
		return write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
	}

	public static byte[] png(int width, int height) {
		return write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png");
	}

	public static byte[] gif(int width, int height) {
		return write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED), "gif");
	}

	private static byte[] write(BufferedImage image, String format) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, format, out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A structurally valid JPEG header (SOF0 with the given size, then SOS) padded with zeros to exactly
	 * {@code totalBytes}. It doesn't decode, which is fine: the loader never decodes pixels.
	 */
	public static byte[] fakeJpeg(int width, int height, int totalBytes) {
		ByteBuffer b = ByteBuffer.allocate(totalBytes);
		b.put(new byte[] { (byte) 0xFF, (byte) 0xD8 });
		b.put(new byte[] { (byte) 0xFF, (byte) 0xC0, 0, 17, 8 });
		b.putShort((short) height).putShort((short) width);
		b.put(new byte[] { 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1 });
		b.put(new byte[] { (byte) 0xFF, (byte) 0xDA, 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 0x3F, 0 });
		b.position(totalBytes - 2);
		b.put(new byte[] { (byte) 0xFF, (byte) 0xD9 });
		return b.array();
	}

	/**
	 * Inserts segments right after the JPEG's SOI: an EXIF APP1 with the orientation and a GPS IFD holding
	 * {@link #SECRET}, an XMP APP1 holding it too, and an ICC APP2 profile.
	 */
	public static byte[] withMetadata(byte[] jpeg, int orientation) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		out.writeBytes(segment(0xE1, exif(orientation)));
		out.writeBytes(segment(0xE1, concat("http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1),
				("<x:xmpmeta>" + SECRET + "</x:xmpmeta>").getBytes(StandardCharsets.ISO_8859_1))));
		out.writeBytes(segment(0xE2, concat("ICC_PROFILE\0".getBytes(StandardCharsets.ISO_8859_1), new byte[] { 1, 1, 9, 9 })));
		out.write(jpeg, 2, jpeg.length - 2);
		return out.toByteArray();
	}

	/** A little-endian EXIF block: IFD0 with Orientation and a GPS IFD pointer; the GPS IFD holds {@link #SECRET}. */
	private static byte[] exif(int orientation) {
		byte[] secret = (SECRET + "\0").getBytes(StandardCharsets.ISO_8859_1);
		ByteBuffer b = ByteBuffer.allocate(6 + 8 + 2 + 24 + 4 + 2 + 12 + 4 + secret.length).order(ByteOrder.LITTLE_ENDIAN);
		b.put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
		b.put("II".getBytes(StandardCharsets.ISO_8859_1)).putShort((short) 42).putInt(8);
		b.putShort((short) 2);
		b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
		int gpsIfd = 8 + 2 + 24 + 4;
		b.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsIfd);
		b.putInt(0);
		b.putShort((short) 1);
		int valueOffset = gpsIfd + 2 + 12 + 4;
		b.putShort((short) 0x0012).putShort((short) 2).putInt(secret.length).putInt(valueOffset);
		b.putInt(0);
		b.put(secret);
		return b.array();
	}

	private static byte[] segment(int marker, byte[] payload) {
		ByteBuffer b = ByteBuffer.allocate(4 + payload.length);
		b.put((byte) 0xFF).put((byte) marker).putShort((short) (payload.length + 2)).put(payload);
		return b.array();
	}

	/** Inserts {@code tEXt} (holding {@link #SECRET}), {@code eXIf} and {@code tIME} chunks after IHDR. */
	public static byte[] withPngMetadata(byte[] png) {
		int afterIhdr = 8 + 8 + 13 + 4;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(png, 0, afterIhdr);
		out.writeBytes(pngChunk("tEXt", ("Comment\0" + SECRET).getBytes(StandardCharsets.ISO_8859_1)));
		out.writeBytes(pngChunk("eXIf", exif(1)));
		out.writeBytes(pngChunk("tIME", new byte[] { 7, (byte) 0xEA, 9, 26, 12, 0, 0 }));
		out.write(png, afterIhdr, png.length - afterIhdr);
		return out.toByteArray();
	}

	private static byte[] pngChunk(String type, byte[] data) {
		java.util.zip.CRC32 crc = new java.util.zip.CRC32();
		byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
		crc.update(typeBytes);
		crc.update(data);
		ByteBuffer b = ByteBuffer.allocate(12 + data.length);
		b.putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue());
		return b.array();
	}

	/**
	 * An extended WebP: a VP8X chunk with the EXIF and XMP flags set, EXIF and XMP chunks holding {@link #SECRET},
	 * and a lossless VP8L header of the given size.
	 */
	public static byte[] webpWithMetadata(int width, int height) {
		ByteArrayOutputStream chunks = new ByteArrayOutputStream();
		ByteBuffer vp8x = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
		vp8x.put((byte) 0x0C).put(new byte[3]);
		putU24(vp8x, width - 1);
		putU24(vp8x, height - 1);
		chunks.writeBytes(riffChunk("VP8X", vp8x.array()));
		chunks.writeBytes(riffChunk("EXIF", SECRET.getBytes(StandardCharsets.ISO_8859_1)));
		chunks.writeBytes(riffChunk("XMP ", (SECRET + "!").getBytes(StandardCharsets.ISO_8859_1)));
		chunks.writeBytes(riffChunk("VP8L", vp8l(width, height)));
		return riff(chunks.toByteArray());
	}

	/** A simple (non-extended) lossless WebP of the given size. */
	public static byte[] webp(int width, int height) {
		return riff(riffChunk("VP8L", vp8l(width, height)));
	}

	private static byte[] vp8l(int width, int height) {
		ByteBuffer b = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 0x2F).putInt((width - 1) | ((height - 1) << 14));
		return b.array();
	}

	private static byte[] riff(byte[] chunks) {
		ByteBuffer b = ByteBuffer.allocate(12 + chunks.length).order(ByteOrder.LITTLE_ENDIAN);
		b.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(4 + chunks.length)
			.put("WEBP".getBytes(StandardCharsets.ISO_8859_1)).put(chunks);
		return b.array();
	}

	private static byte[] riffChunk(String fourcc, byte[] data) {
		int padded = data.length + (data.length & 1);
		ByteBuffer b = ByteBuffer.allocate(8 + padded).order(ByteOrder.LITTLE_ENDIAN);
		b.put(fourcc.getBytes(StandardCharsets.ISO_8859_1)).putInt(data.length).put(data);
		return b.array();
	}

	private static void putU24(ByteBuffer b, int value) {
		b.put((byte) value).put((byte) (value >> 8)).put((byte) (value >> 16));
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] result = new byte[a.length + b.length];
		System.arraycopy(a, 0, result, 0, a.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}

	/** Whether {@code data} contains {@code text} as ISO-8859-1 bytes. */
	public static boolean contains(byte[] data, String text) {
		return new String(data, StandardCharsets.ISO_8859_1).contains(text);
	}

}
