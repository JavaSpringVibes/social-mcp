package com.socialmcp.media;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.model.Dimensions;
import com.socialmcp.model.ImageCheck;
import com.socialmcp.model.ImageInput;
import com.socialmcp.model.ImageRules;

import static com.socialmcp.media.TestImages.SECRET;
import static com.socialmcp.media.TestImages.contains;
import static org.assertj.core.api.Assertions.assertThat;

/** Reading and checking images (SPEC §6.14), with an injected resolver and downloader instead of the network. */
class ImageLoaderTest {

	private static final ImageRules MASTODON = new ImageRules(4, 16_777_216, 33_177_600L, 1500, ImageFormats.SNIFFABLE,
			false, false, null, "instance");

	private static final ImageRules BLUESKY = new ImageRules(4, 2_000_000, null, 2000, ImageFormats.SNIFFABLE, true,
			false, 4000, "lexicon+server");

	@TempDir
	Path dir;

	/** Canned responses by URL; a missing URL answers 404. */
	private final Map<String, Downloader.Response> responses = new HashMap<>();

	private final List<URI> requested = new ArrayList<>();

	/** Host → address; a missing host is unknown. */
	private final Map<String, String> dns = new HashMap<>();

	private ImageLoader loader;

	@BeforeEach
	void setUp() {
		dns.put("images.example", "93.184.216.34");
		loader = loader(settings(List.of(), true, 20_971_520), false);
	}

	private static SocialProperties.Media settings(List<String> allowedDirs, boolean allowUrls, long maxReadBytes) {
		return new SocialProperties.Media(allowedDirs, allowUrls, maxReadBytes, Duration.ofSeconds(30),
				Duration.ofSeconds(30));
	}

	private ImageLoader loader(SocialProperties.Media settings, boolean windows) {
		ImageLoader.HostResolver resolver = host -> {
			String address = dns.get(host);
			if (address == null) {
				throw new UnknownHostException(host);
			}
			return new InetAddress[] { InetAddress.getByName(address) };
		};
		Downloader downloader = (uri, timeout, maxBytes) -> {
			requested.add(uri);
			Downloader.Response response = responses.getOrDefault(uri.toString(),
					new Downloader.Response(404, null, new byte[0]));
			byte[] body = response.body();
			return new Downloader.Response(response.status(), response.location(),
					body.length > maxBytes ? java.util.Arrays.copyOf(body, (int) maxBytes) : body);
		};
		return new ImageLoader(settings, resolver, downloader, windows);
	}

	private Path file(String name, byte[] data) throws IOException {
		return Files.write(dir.resolve(name), data);
	}

	private ImageLoader.Inspection inspect(String source, ImageRules rules, boolean strip) {
		return loader.inspect(1, new ImageInput(source, "A photo"), rules, rules == MASTODON ? "mastodon" : "bluesky",
				strip);
	}

	// --- Sniffing and dimensions ---

	@Test
	void theTypeComesFromTheBytesNotTheName() throws IOException {
		Path misnamed = file("photo.png", TestImages.jpeg(40, 30));
		ImageLoader.Inspection result = inspect(misnamed.toString(), MASTODON, false);
		assertThat(result.check().ok()).isTrue();
		assertThat(result.check().mimeType()).isEqualTo("image/jpeg");
		assertThat(result.image()).isNotNull();
		assertThat(result.image().mimeType()).isEqualTo("image/jpeg");
		assertThat(result.image().altText()).isEqualTo("A photo");
	}

	@Test
	void aTextFileIsNotAnImage() throws IOException {
		Path text = file("photo.jpg", "-----BEGIN PRIVATE KEY-----".getBytes(StandardCharsets.UTF_8));
		ImageLoader.Inspection result = inspect(text.toString(), MASTODON, false);
		assertThat(result.error()).isEqualTo("Image 1 is not a JPEG, PNG, GIF or WebP image. Convert it to JPEG.");
		assertThat(result.check().mimeType()).isNull();
		assertThat(result.check().fitWithin()).isNull();
		assertThat(result.image()).isNull();
	}

	@Test
	void aTypeThePlatformDoesNotAcceptIsReported() throws IOException {
		ImageRules noWebp = new ImageRules(4, 16_777_216, 33_177_600L, 1500, List.of("image/jpeg", "image/png"), false,
				false, null, "instance");
		ImageLoader.Inspection result = loader.inspect(1, new ImageInput(file("a.webp", TestImages.webp(10, 10)).toString(),
				"alt"), noWebp, "mastodon", false);
		assertThat(result.error()).isEqualTo("Image 1 is image/webp, which mastodon doesn't accept");
	}

	@Test
	void exifOrientationSwapsTheReportedSize() throws IOException {
		Path rotated = file("r.jpg", TestImages.withMetadata(TestImages.fakeJpeg(4000, 3000, 3000), 6));
		ImageCheck check = inspect(rotated.toString(), MASTODON, false).check();
		assertThat(check.width()).isEqualTo(3000);
		assertThat(check.height()).isEqualTo(4000);
	}

	// --- Metadata ---

	@Test
	void blueskyUploadsHaveGpsAndXmpRemovedWhileMastodonGetsTheOriginal() throws Exception {
		byte[] photo = TestImages.withMetadata(TestImages.jpeg(32, 32), 6);
		Path path = file("gps.jpg", photo);
		byte[] forBluesky = inspect(path.toString(), BLUESKY, true).image().bytes();
		assertThat(contains(forBluesky, SECRET)).isFalse();
		assertThat(ImageFormats.header(forBluesky, "image/jpeg").orientation()).isEqualTo(6);
		assertThat(inspect(path.toString(), MASTODON, false).image().bytes()).isEqualTo(photo);
	}

	@Test
	void theByteLimitAppliesAfterStripping() throws IOException {
		byte[] photo = TestImages.withMetadata(TestImages.fakeJpeg(100, 100, 1_999_900), 1);
		assertThat(photo.length).isGreaterThan(2_000_000);
		ImageLoader.Inspection result = inspect(file("big.jpg", photo).toString(), BLUESKY, true);
		assertThat(result.check().ok()).isTrue();
		assertThat(result.check().bytes()).isLessThanOrEqualTo(2_000_000L);
	}

	// --- Limits and fitWithin ---

	@Test
	void theSpecExampleOnBlueskyGetsTheByteEstimate() throws IOException {
		ImageLoader.Inspection result = inspect(file("IMG_0412.jpg", TestImages.fakeJpeg(4032, 3024, 4_718_592)).toString(),
				BLUESKY, true);
		ImageCheck check = result.check();
		assertThat(check.ok()).isFalse();
		assertThat(check.problems()).containsExactly(
				"is 4718592 bytes; the maximum on bluesky is 2000000 bytes. Resize or recompress it and try again.");
		assertThat(check.fitWithin()).isEqualTo(new Dimensions(2490, 1867));
		assertThat(result.error()).startsWith("Image 1 is 4718592 bytes");
	}

	@Test
	void tooManyPixelsOnMastodonGetsThePixelFit() throws IOException {
		ImageCheck check = inspect(file("48mp.jpg", TestImages.fakeJpeg(8064, 6048, 6_000_000)).toString(), MASTODON, false)
			.check();
		assertThat(check.problems())
			.containsExactly("is 8064×6048 (48771072 pixels); the maximum on mastodon is 33177600 pixels");
		assertThat(check.fitWithin()).isEqualTo(new Dimensions(6651, 4988));
	}

	@Test
	void bothProblemsAreListedAndTheSmallerScaleWins() throws IOException {
		ImageRules small = new ImageRules(4, 1_000_000, 10_000_000L, 1500, ImageFormats.SNIFFABLE, false, false, null,
				"instance");
		ImageCheck check = loader
			.inspect(1, new ImageInput(file("x.jpg", TestImages.fakeJpeg(4000, 4000, 4_000_000)).toString(), "alt"), small,
					"mastodon", false)
			.check();
		assertThat(check.problems()).hasSize(2);
		// pixels: sqrt(10M / 16M) = 0.79; bytes: sqrt(0.9 × 1M / 4M) = 0.47
		assertThat(check.fitWithin()).isEqualTo(new Dimensions(1897, 1897));
	}

	@Test
	void anImageThatFitsIsOkEvenAboveTheRecommendedDimension() throws IOException {
		ImageCheck check = inspect(file("wide.png", TestImages.png(4100, 10)).toString(), BLUESKY, true).check();
		assertThat(check.ok()).isTrue();
		assertThat(check.fitWithin()).isNull();
	}

	@Test
	void altTextIsRequiredAndMeasured() throws IOException {
		String path = file("a.png", TestImages.png(4, 4)).toString();
		ImageLoader.Inspection missing = loader.inspect(1, new ImageInput(path, " "), MASTODON, "mastodon", false);
		assertThat(missing.error()).isEqualTo("Image 1 needs alt text describing what it shows");
		assertThat(missing.check().width()).isEqualTo(4); // the rest is still checked
		ImageLoader.Inspection tooLong = loader.inspect(2, new ImageInput(path, "x".repeat(1501)), MASTODON, "mastodon",
				false);
		assertThat(tooLong.error()).isEqualTo("Image 2 alt text is 1501/1500 graphemes (1 over)");
	}

	// --- Sources ---

	@Test
	void relativePathsAndOtherSchemesAreRejected() {
		assertThat(inspect("photos/a.jpg", MASTODON, false).error())
			.isEqualTo("Image 1: 'photos/a.jpg' must be an absolute file path or an https:// URL");
		assertThat(inspect("http://images.example/a.jpg", MASTODON, false).error())
			.isEqualTo("Image 1: only https:// URLs are allowed");
		assertThat(inspect("ftp://images.example/a.jpg", MASTODON, false).error())
			.isEqualTo("Image 1: only https:// URLs are allowed");
		assertThat(inspect("", MASTODON, false).error()).isEqualTo("Image 1 needs a source");
	}

	@Test
	void fileUrisAreLocalFiles() throws IOException {
		Path path = file("u.png", TestImages.png(3, 3));
		assertThat(inspect(path.toUri().toString(), MASTODON, false).check().ok()).isTrue();
	}

	@Test
	void missingFilesAndDirectoriesAreReported() {
		String missing = dir.resolve("nope.jpg").toString();
		assertThat(inspect(missing, MASTODON, false).error()).isEqualTo("Image 1: file '" + missing + "' not found");
		assertThat(inspect(dir.toString(), MASTODON, false).error())
			.isEqualTo("Image 1: '" + dir + "' is not a readable file");
	}

	@Test
	void filesOutsideTheAllowedDirectoriesAreRefused() throws IOException {
		Path allowed = Files.createDirectory(dir.resolve("allowed"));
		Path inside = Files.write(allowed.resolve("in.png"), TestImages.png(2, 2));
		Path outside = file("out.png", TestImages.png(2, 2));
		loader = loader(settings(List.of(allowed.toString()), true, 20_971_520), false);
		assertThat(inspect(inside.toString(), MASTODON, false).check().ok()).isTrue();
		assertThat(inspect(outside.toString(), MASTODON, false).error())
			.isEqualTo("Image 1: '" + outside + "' is outside the directories this server may read images from");
	}

	@Test
	void aSymlinkPointingOutsideTheAllowedDirectoriesIsRefused() throws IOException {
		Path allowed = Files.createDirectory(dir.resolve("allowed"));
		Path outside = file("secret.png", TestImages.png(2, 2));
		Path link;
		try {
			link = Files.createSymbolicLink(allowed.resolve("link.png"), outside);
		}
		catch (IOException | UnsupportedOperationException ex) {
			return; // creating symlinks needs extra rights on Windows
		}
		loader = loader(settings(List.of(allowed.toString()), true, 20_971_520), false);
		assertThat(inspect(link.toString(), MASTODON, false).error()).contains("is outside the directories");
	}

	@Test
	void filesOverTheReadCapAreRejected() throws IOException {
		loader = loader(settings(List.of(), true, 1000), false);
		assertThat(inspect(file("big.png", new byte[1001]).toString(), MASTODON, false).error())
			.isEqualTo("Image 1 is larger than 1000 bytes");
	}

	// --- Sandbox paths (SPEC §6.14, step 5) ---

	@Test
	void chatUploadPathsGetTheSandboxMessage() {
		String upload = "/mnt/user-data/uploads/beach.jpg";
		assertThat(loader.looksLikeSandbox(upload)).isTrue();
		assertThat(loader.looksLikeSandbox("/sessions/abc/mnt/tour/a.jpg")).isTrue();
		assertThat(loader.looksLikeSandbox("/home/me/a.jpg")).isFalse();
		assertThat(loader(settings(List.of(), true, 1000), true).looksLikeSandbox("/home/me/a.jpg")).isTrue();
	}

	@Test
	void onWindowsAUnixPathIsASandboxPath() {
		loader = loader(settings(List.of(), true, 1000), true);
		assertThat(inspect("/home/me/a.jpg", MASTODON, false).error())
			.startsWith("Image 1: '/home/me/a.jpg' is inside the assistant's sandbox")
			.endsWith("or use Claude Code, which works with your files directly.");
	}

	// --- URLs (SPEC §6.14, step 6) ---

	@Test
	void anHttpsUrlIsDownloaded() {
		responses.put("https://images.example/a.png", new Downloader.Response(200, null, TestImages.png(5, 5)));
		ImageLoader.Inspection result = inspect("https://images.example/a.png", MASTODON, false);
		assertThat(result.check().ok()).isTrue();
		assertThat(result.check().width()).isEqualTo(5);
	}

	@Test
	void httpErrorsAreReported() {
		assertThat(inspect("https://images.example/missing.png", MASTODON, false).error())
			.isEqualTo("Image 1: download failed with HTTP 404");
	}

	@Test
	void redirectsAreFollowedAndCheckedButNotForever() {
		responses.put("https://images.example/a", new Downloader.Response(302, "/b", new byte[0]));
		responses.put("https://images.example/b", new Downloader.Response(200, null, TestImages.png(2, 2)));
		assertThat(inspect("https://images.example/a", MASTODON, false).check().ok()).isTrue();

		responses.put("https://images.example/insecure", new Downloader.Response(301, "http://images.example/b", new byte[0]));
		assertThat(inspect("https://images.example/insecure", MASTODON, false).error())
			.isEqualTo("Image 1: only https:// URLs are allowed");

		for (int i = 0; i < 5; i++) {
			responses.put("https://images.example/loop" + i,
					new Downloader.Response(302, "https://images.example/loop" + (i + 1), new byte[0]));
		}
		assertThat(inspect("https://images.example/loop0", MASTODON, false).error())
			.isEqualTo("Image 1: download failed: more than 3 redirects");
	}

	@Test
	void largeDownloadsAreCutOff() {
		loader = loader(settings(List.of(), true, 1000), false);
		responses.put("https://images.example/big", new Downloader.Response(200, null, new byte[5000]));
		assertThat(inspect("https://images.example/big", MASTODON, false).error())
			.isEqualTo("Image 1 is larger than 1000 bytes");
	}

	@Test
	void privateAndLocalAddressesAreRefused() {
		for (String address : List.of("127.0.0.1", "10.0.0.1", "192.168.1.5", "169.254.169.254", "::1", "fd00::1")) {
			dns.put("internal.example", address);
			assertThat(inspect("https://internal.example/a.png", MASTODON, false).error())
				.as(address)
				.isEqualTo("Image 1: 'internal.example' is a private or local address");
		}
		assertThat(requested).isEmpty();
	}

	@Test
	void urlDownloadsCanBeDisabled() {
		loader = loader(settings(List.of(), false, 1000), false);
		assertThat(inspect("https://images.example/a.png", MASTODON, false).error())
			.isEqualTo("Image 1: downloading images from URLs is disabled on this server");
		assertThat(requested).isEmpty();
	}

}
