package com.socialmcp.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.model.Dimensions;
import com.socialmcp.model.ImageCheck;
import com.socialmcp.model.ImageInput;
import com.socialmcp.model.ImageRules;
import com.socialmcp.model.PreparedImage;
import com.socialmcp.text.TextLength;

/**
 * Reads and checks the images of one post (SPEC §6.14). Collects every problem of an image into an
 * {@link ImageCheck}; {@code checkSocialPost} returns those, and the posting tools throw the first problem, so both
 * always agree. Makes no platform calls: its only network access is the URL download.
 */
@Component
public class ImageLoader {

	private static final Logger log = LoggerFactory.getLogger(ImageLoader.class);

	private static final int MAX_REDIRECTS = 3;

	/**
	 * Paths inside an assistant's sandbox rather than on the user's computer (SPEC §6.14, step 5). A best guess:
	 * clients don't document these, so the list is kept here to be extended.
	 */
	static final List<String> SANDBOX_PREFIXES = List.of("/mnt/user-data/", "/mnt/data/", "/sessions/",
			"/tmp/outputs/");

	/** A URI scheme of two or more characters, so a Windows drive such as {@code C:} isn't taken for one. */
	private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]+:");

	/** Resolves a host name to its addresses; injectable so tests can simulate public and private hosts. */
	@FunctionalInterface
	public interface HostResolver {

		InetAddress[] resolve(String host) throws UnknownHostException;

	}

	/**
	 * The outcome for one image: the check, the image ready to upload when there were no problems, and every problem as
	 * a full SPEC §6.14 message ({@code "Image 2 is …"}).
	 */
	public record Inspection(ImageCheck check, @Nullable PreparedImage image, List<String> errors) {

		/** The first problem, which a posting tool throws. */
		public @Nullable String error() {
			return errors.isEmpty() ? null : errors.get(0);
		}

	}

	private final SocialProperties.Media settings;

	private final HostResolver resolver;

	private final Downloader downloader;

	private final boolean windows;

	@Autowired
	public ImageLoader(SocialProperties properties) {
		this(properties.media(), InetAddress::getAllByName, Downloader.http(),
				System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
	}

	public ImageLoader(SocialProperties.Media settings, HostResolver resolver, Downloader downloader, boolean windows) {
		this.settings = settings;
		this.resolver = resolver;
		this.downloader = downloader;
		this.windows = windows;
		for (String dir : settings.allowedDirs()) {
			if (!Files.isDirectory(Path.of(dir))) {
				log.warn("social.media.allowed-dirs entry {} does not exist (yet)", dir);
			}
		}
	}

	/**
	 * Checks one image of a post in the SPEC §6.14 order.
	 * @param index the image's 1-based position
	 * @param stripMetadata whether the platform needs EXIF/XMP removed before upload (Bluesky)
	 */
	public Inspection inspect(int index, @Nullable ImageInput input, ImageRules rules, String platform,
			boolean stripMetadata) {
		String source = input == null || input.source() == null ? "" : input.source().trim();
		String altText = input == null || input.altText() == null ? "" : input.altText().trim();
		Problems problems = new Problems(index);
		if (source.isEmpty()) {
			problems.add(" needs a source");
		}
		if (altText.isEmpty()) {
			problems.add(" needs alt text describing what it shows");
		}
		else {
			int length = TextLength.graphemes(altText);
			if (length > rules.maxAltTextLength()) {
				problems.add(" alt text is " + TextLength.overReason(length, rules.maxAltTextLength(), "graphemes"));
			}
		}
		if (source.isEmpty()) {
			return problems.result(source, null, null, null, null, null);
		}
		byte[] data = read(source, problems);
		if (data == null) {
			return problems.result(source, null, null, null, null, null);
		}
		String mimeType = ImageFormats.sniff(data);
		if (mimeType == null) {
			problems.add(" is not a JPEG, PNG, GIF or WebP image. Convert it to JPEG.");
			return problems.result(source, null, (long) data.length, null, null, null);
		}
		if (!rules.mimeTypes().contains(mimeType)) {
			problems.add(" is " + mimeType + ", which " + platform + " doesn't accept");
		}
		ImageFormats.Header header;
		byte[] upload;
		try {
			header = ImageFormats.header(data, mimeType);
			upload = stripMetadata ? ImageFormats.stripMetadata(data, mimeType, header.orientation()) : data;
		}
		catch (ImageFormats.DamagedImageException ex) {
			problems.add(" is damaged or truncated");
			return problems.result(source, mimeType, (long) data.length, null, null, null);
		}
		int width = header.displayWidth();
		int height = header.displayHeight();
		long pixels = (long) width * height;
		boolean tooManyPixels = rules.maxPixels() != null && pixels > rules.maxPixels();
		if (tooManyPixels) {
			problems.add(" is " + width + "×" + height + " (" + pixels + " pixels); the maximum on " + platform + " is "
					+ rules.maxPixels() + " pixels");
		}
		boolean tooLarge = upload.length > rules.maxBytes();
		if (tooLarge) {
			problems.add(" is " + upload.length + " bytes; the maximum on " + platform + " is " + rules.maxBytes()
					+ " bytes. Resize or recompress it and try again.");
		}
		Dimensions fitWithin = tooManyPixels || tooLarge ? fitWithin(width, height, upload.length, rules, tooLarge)
				: null;
		log.info("Image {}: {} {} {} bytes {}x{}", index, source, mimeType, upload.length, width, height);
		PreparedImage image = problems.isEmpty()
				? new PreparedImage(index, upload, mimeType, width, height, altText) : null;
		return problems.result(source, mimeType, (long) upload.length, width, height, fitWithin, image);
	}

	/**
	 * The size to resize to, keeping the aspect ratio (SPEC §4, Tool 10): scaled by the smallest of the pixel limit,
	 * the recommended dimension and, when over {@code maxBytes}, an estimate that assumes size scales with pixel count.
	 */
	static Dimensions fitWithin(int width, int height, long bytes, ImageRules rules, boolean overBytes) {
		double scale = 1;
		if (rules.maxPixels() != null) {
			scale = Math.min(scale, Math.sqrt((double) rules.maxPixels() / ((double) width * height)));
		}
		if (rules.recommendedMaxDimension() != null) {
			scale = Math.min(scale, (double) rules.recommendedMaxDimension() / Math.max(width, height));
		}
		if (overBytes) {
			scale = Math.min(scale, Math.sqrt(0.9 * rules.maxBytes() / bytes));
		}
		return new Dimensions(Math.max(1, (int) Math.floor(width * scale)), Math.max(1, (int) Math.floor(height * scale)));
	}

	// --- Reading (SPEC §6.14, steps 4–6) ---

	private byte @Nullable [] read(String source, Problems problems) {
		String lower = source.toLowerCase(Locale.ROOT);
		if (lower.startsWith("https://")) {
			return download(source, problems);
		}
		Path path;
		if (lower.startsWith("file:")) {
			try {
				path = Path.of(new URI(source));
			}
			catch (URISyntaxException | IllegalArgumentException ex) {
				problems.add(": '" + source + "' must be an absolute file path or an https:// URL");
				return null;
			}
		}
		else if (SCHEME.matcher(source).find()) {
			problems.add(": only https:// URLs are allowed");
			return null;
		}
		else {
			try {
				path = Path.of(source);
			}
			catch (InvalidPathException ex) {
				problems.add(": '" + source + "' must be an absolute file path or an https:// URL");
				return null;
			}
			if (!path.isAbsolute()) {
				if (windows && source.startsWith("/")) {
					problems.add(sandboxMessage(source)); // a Unix path can't exist on Windows
				}
				else {
					problems.add(": '" + source + "' must be an absolute file path or an https:// URL");
				}
				return null;
			}
		}
		return readFile(path, source, problems);
	}

	private byte @Nullable [] readFile(Path path, String source, Problems problems) {
		Path real;
		try {
			real = path.toRealPath();
		}
		catch (NoSuchFileException ex) {
			problems.add(looksLikeSandbox(source) ? sandboxMessage(source) : ": file '" + source + "' not found");
			return null;
		}
		catch (IOException ex) {
			problems.add(": '" + source + "' is not a readable file");
			return null;
		}
		if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
			problems.add(": '" + source + "' is not a readable file");
			return null;
		}
		if (!insideAllowedDirs(real)) {
			problems.add(": '" + source + "' is outside the directories this server may read images from");
			return null;
		}
		try (InputStream in = Files.newInputStream(real)) {
			byte[] data = in.readNBytes((int) Math.min(settings.maxReadBytes() + 1, Integer.MAX_VALUE - 8));
			if (data.length > settings.maxReadBytes()) {
				problems.add(" is larger than " + settings.maxReadBytes() + " bytes");
				return null;
			}
			return data;
		}
		catch (IOException ex) {
			problems.add(": '" + source + "' is not a readable file");
			return null;
		}
	}

	private boolean insideAllowedDirs(Path real) {
		if (settings.allowedDirs().isEmpty()) {
			return true;
		}
		for (String dir : settings.allowedDirs()) {
			Path root = Path.of(dir);
			try {
				root = root.toRealPath();
			}
			catch (IOException ex) {
				root = root.toAbsolutePath().normalize();
			}
			if (real.startsWith(root)) {
				return true;
			}
		}
		return false;
	}

	boolean looksLikeSandbox(String source) {
		String unix = source.replace('\\', '/');
		return SANDBOX_PREFIXES.stream().anyMatch(unix::startsWith) || (windows && source.startsWith("/"));
	}

	private static String sandboxMessage(String source) {
		return ": '" + source + "' is inside the assistant's sandbox (for example an image attached to the chat), "
				+ "not on the computer running this server. Give the image's path on your computer, or use Claude Code, "
				+ "which works with your files directly.";
	}

	private byte @Nullable [] download(String source, Problems problems) {
		if (!settings.allowUrls()) {
			problems.add(": downloading images from URLs is disabled on this server");
			return null;
		}
		URI uri;
		try {
			uri = new URI(source);
		}
		catch (URISyntaxException ex) {
			problems.add(": download failed: invalid URL");
			return null;
		}
		for (int redirects = 0;; redirects++) {
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
				problems.add(": only https:// URLs are allowed");
				return null;
			}
			String host = uri.getHost();
			try {
				for (InetAddress address : resolver.resolve(host)) {
					if (isPrivate(address)) {
						problems.add(": '" + host + "' is a private or local address");
						return null;
					}
				}
				Downloader.Response response = downloader.get(uri, settings.downloadTimeout(),
						settings.maxReadBytes() + 1);
				int status = response.status();
				if (status / 100 == 3 && response.location() != null) {
					if (redirects >= MAX_REDIRECTS) {
						problems.add(": download failed: more than " + MAX_REDIRECTS + " redirects");
						return null;
					}
					uri = uri.resolve(response.location());
					continue;
				}
				if (status / 100 != 2) {
					problems.add(": download failed with HTTP " + status);
					return null;
				}
				if (response.body().length > settings.maxReadBytes()) {
					problems.add(" is larger than " + settings.maxReadBytes() + " bytes");
					return null;
				}
				return response.body();
			}
			catch (UnknownHostException ex) {
				problems.add(": download failed: unknown host " + host);
				return null;
			}
			catch (IOException | IllegalArgumentException ex) {
				problems.add(": download failed: " + ex.getMessage());
				return null;
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				problems.add(": download failed: interrupted");
				return null;
			}
		}
	}

	static boolean isPrivate(InetAddress address) {
		if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
				|| address.isAnyLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC; // fc00::/7
	}

	/** The problems of one image, each stored as the text after {@code "Image <i>"}. */
	private static final class Problems {

		private final int index;

		private final List<String> suffixes = new ArrayList<>();

		Problems(int index) {
			this.index = index;
		}

		void add(String suffix) {
			suffixes.add(suffix);
		}

		boolean isEmpty() {
			return suffixes.isEmpty();
		}

		Inspection result(String source, @Nullable String mimeType, @Nullable Long bytes, @Nullable Integer width,
				@Nullable Integer height, @Nullable Dimensions fitWithin) {
			return result(source, mimeType, bytes, width, height, fitWithin, null);
		}

		Inspection result(String source, @Nullable String mimeType, @Nullable Long bytes, @Nullable Integer width,
				@Nullable Integer height, @Nullable Dimensions fitWithin, @Nullable PreparedImage image) {
			List<String> messages = suffixes.stream().map(s -> s.startsWith(": ") ? s.substring(2) : s.substring(1)).toList();
			ImageCheck check = new ImageCheck(index, source, suffixes.isEmpty(), mimeType, bytes, width, height, messages,
					fitWithin);
			return new Inspection(check, image, suffixes.stream().map(s -> "Image " + index + s).toList());
		}

	}

}
