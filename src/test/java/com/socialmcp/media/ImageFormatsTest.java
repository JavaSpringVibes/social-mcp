package com.socialmcp.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static com.socialmcp.media.TestImages.SECRET;
import static com.socialmcp.media.TestImages.contains;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sniffing, header parsing and metadata stripping (SPEC §6.14, steps 7–9). */
class ImageFormatsTest {

	@Test
	void sniffsEachFormatFromItsFirstBytes() throws Exception {
		assertThat(ImageFormats.sniff(TestImages.jpeg(4, 3))).isEqualTo("image/jpeg");
		assertThat(ImageFormats.sniff(TestImages.png(4, 3))).isEqualTo("image/png");
		assertThat(ImageFormats.sniff(TestImages.gif(4, 3))).isEqualTo("image/gif");
		assertThat(ImageFormats.sniff(TestImages.webp(4, 3))).isEqualTo("image/webp");
		assertThat(ImageFormats.sniff("hello, not an image".getBytes(StandardCharsets.UTF_8))).isNull();
		assertThat(ImageFormats.sniff(new byte[0])).isNull();
	}

	@Test
	void readsDimensionsFromEachHeader() throws Exception {
		assertThat(ImageFormats.header(TestImages.jpeg(40, 30), "image/jpeg"))
			.isEqualTo(new ImageFormats.Header(40, 30, 1));
		assertThat(ImageFormats.header(TestImages.png(41, 31), "image/png")).isEqualTo(new ImageFormats.Header(41, 31, 1));
		assertThat(ImageFormats.header(TestImages.gif(42, 32), "image/gif")).isEqualTo(new ImageFormats.Header(42, 32, 1));
		assertThat(ImageFormats.header(TestImages.webp(4000, 3000), "image/webp"))
			.isEqualTo(new ImageFormats.Header(4000, 3000, 1));
		assertThat(ImageFormats.header(TestImages.webpWithMetadata(1234, 567), "image/webp"))
			.isEqualTo(new ImageFormats.Header(1234, 567, 1));
		assertThat(ImageFormats.header(TestImages.fakeJpeg(4032, 3024, 5000), "image/jpeg"))
			.isEqualTo(new ImageFormats.Header(4032, 3024, 1));
	}

	@Test
	void exifOrientationSixSwapsTheDisplayedSize() throws Exception {
		ImageFormats.Header header = ImageFormats.header(TestImages.withMetadata(TestImages.fakeJpeg(4000, 3000, 2000), 6),
				"image/jpeg");
		assertThat(header.orientation()).isEqualTo(6);
		assertThat(header.displayWidth()).isEqualTo(3000);
		assertThat(header.displayHeight()).isEqualTo(4000);
	}

	@Test
	void truncatedHeadersAreDamaged() {
		byte[] png = TestImages.png(4, 3);
		assertThatThrownBy(() -> ImageFormats.header(Arrays.copyOf(png, 14), "image/png"))
			.isInstanceOf(ImageFormats.DamagedImageException.class);
		byte[] jpeg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0 };
		assertThatThrownBy(() -> ImageFormats.header(jpeg, "image/jpeg"))
			.isInstanceOf(ImageFormats.DamagedImageException.class);
	}

	@Test
	void jpegStrippingKeepsPixelsIccAndOrientationOnly() throws Exception {
		byte[] original = TestImages.jpeg(64, 48);
		byte[] withMetadata = TestImages.withMetadata(original, 6);
		assertThat(contains(withMetadata, SECRET)).isTrue();

		byte[] stripped = ImageFormats.stripMetadata(withMetadata, "image/jpeg", 6);

		assertThat(contains(stripped, SECRET)).isFalse();
		assertThat(contains(stripped, "http://ns.adobe.com/xap")).isFalse();
		assertThat(contains(stripped, "ICC_PROFILE")).isTrue();
		assertThat(ImageFormats.header(stripped, "image/jpeg").orientation()).isEqualTo(6);
		int scan = indexOf(original, new byte[] { (byte) 0xFF, (byte) 0xDA });
		byte[] entropyCoded = Arrays.copyOfRange(original, scan, original.length);
		assertThat(Arrays.copyOfRange(stripped, stripped.length - entropyCoded.length, stripped.length))
			.isEqualTo(entropyCoded);
		assertThat(ImageIO.read(new ByteArrayInputStream(stripped)).getWidth()).isEqualTo(64);
	}

	@Test
	void jpegWithNormalOrientationGetsNoExifAtAll() throws Exception {
		byte[] stripped = ImageFormats.stripMetadata(TestImages.withMetadata(TestImages.jpeg(8, 8), 1), "image/jpeg", 1);
		assertThat(contains(stripped, "Exif")).isFalse();
		assertThat(ImageIO.read(new ByteArrayInputStream(stripped))).isNotNull();
	}

	@Test
	void pngStrippingRemovesTextExifAndTimeChunks() throws Exception {
		byte[] withMetadata = TestImages.withPngMetadata(TestImages.png(16, 9));
		byte[] stripped = ImageFormats.stripMetadata(withMetadata, "image/png", 1);
		assertThat(contains(stripped, SECRET)).isFalse();
		assertThat(contains(stripped, "tEXt")).isFalse();
		assertThat(contains(stripped, "eXIf")).isFalse();
		assertThat(contains(stripped, "tIME")).isFalse();
		assertThat(stripped).isEqualTo(TestImages.png(16, 9));
		assertThat(ImageIO.read(new ByteArrayInputStream(stripped)).getHeight()).isEqualTo(9);
	}

	@Test
	void webpStrippingRemovesChunksClearsFlagsAndFixesTheRiffSize() throws Exception {
		byte[] stripped = ImageFormats.stripMetadata(TestImages.webpWithMetadata(300, 200), "image/webp", 1);
		assertThat(contains(stripped, SECRET)).isFalse();
		assertThat(stripped[20] & 0x0C).isZero(); // VP8X flags: no EXIF, no XMP
		long riffSize = (stripped[4] & 0xFF) | (stripped[5] & 0xFF) << 8 | (stripped[6] & 0xFF) << 16
				| (long) (stripped[7] & 0xFF) << 24;
		assertThat(riffSize).isEqualTo(stripped.length - 8);
		assertThat(ImageFormats.header(stripped, "image/webp")).isEqualTo(new ImageFormats.Header(300, 200, 1));
	}

	@Test
	void gifIsSentUnchanged() throws IOException, ImageFormats.DamagedImageException {
		byte[] gif = TestImages.gif(5, 5);
		assertThat(ImageFormats.stripMetadata(gif, "image/gif", 1)).isSameAs(gif);
	}

	private static int indexOf(byte[] data, byte[] pattern) {
		outer: for (int i = 0; i <= data.length - pattern.length; i++) {
			for (int j = 0; j < pattern.length; j++) {
				if (data[i + j] != pattern[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

}
