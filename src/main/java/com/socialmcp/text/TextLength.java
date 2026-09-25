package com.socialmcp.text;

import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.regex.Pattern;

/** Length counting shared by every posting tool (SPEC §6.2). Never uses {@link String#length()}. */
public final class TextLength {

	private static final Pattern URL = Pattern.compile("https?://\\S+");

	private static final Pattern REMOTE_MENTION = Pattern
		.compile("(?<![\\w/@])@([A-Za-z0-9_]+)@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

	private TextLength() {
	}

	/** Counts extended grapheme clusters (user-perceived characters). */
	public static int graphemes(String text) {
		BreakIterator it = BreakIterator.getCharacterInstance();
		it.setText(text);
		int count = 0;
		while (it.next() != BreakIterator.DONE) {
			count++;
		}
		return count;
	}

	public static int utf8Bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8).length;
	}

	/**
	 * Mastodon's countable length: each http(s) URL counts as {@code urlLength}, a remote mention
	 * {@code @user@domain} counts as {@code @user}, and the result is counted in graphemes, mirroring
	 * Mastodon's {@code StatusLengthValidator}.
	 */
	public static int mastodon(String text, int urlLength) {
		String placeholder = "x".repeat(urlLength);
		String rewritten = URL.matcher(text).replaceAll(placeholder);
		rewritten = REMOTE_MENTION.matcher(rewritten).replaceAll("@$1");
		return graphemes(rewritten);
	}

	/** The standard over-limit reason, e.g. {@code "327/300 graphemes (27 over)"}. */
	public static String overReason(int length, int max, String unit) {
		return length + "/" + max + " " + unit + " (" + (length - max) + " over)";
	}

}
