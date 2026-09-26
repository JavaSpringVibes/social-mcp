package com.socialmcp.text;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Mastodon HTML ({@code content}, {@code note}) to plain text (SPEC §5, Mastodon).
 */
public final class HtmlText {

    /**
     * An element with the {@code quote-inline} class, e.g. Mastodon's {@code <p class="quote-inline">RE: …</p>}.
     */
    private static final Pattern QUOTE_INLINE = Pattern
            .compile("(?is)<(\\w+)[^>]*\\bclass\\s*=\\s*\"[^\"]*\\bquote-inline\\b[^\"]*\"[^>]*>.*?</\\1\\s*>");

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("(?i)</p>\\s*<p[^>]*>");

    private static final Pattern LINE_BREAK = Pattern.compile("(?i)<br\\s*/?>");

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);");

    private static final Map<String, String> NAMED = Map.of("amp", "&", "lt", "<", "gt", ">", "quot", "\"",
            "apos", "'", "nbsp", " ");

    private HtmlText() {
    }

    public static String toPlainText(String html) {
        return toPlainText(html, false);
    }

    /**
     * @param stripQuoteInline whether to drop {@code quote-inline} elements first. Mastodon prepends one to quote
     *                         posts as a fallback link for older clients; clients should hide it when the status has a {@code quote}.
     */
    public static String toPlainText(String html, boolean stripQuoteInline) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = stripQuoteInline ? QUOTE_INLINE.matcher(html).replaceAll("") : html;
        text = PARAGRAPH_BREAK.matcher(text).replaceAll("\n\n");
        text = LINE_BREAK.matcher(text).replaceAll("\n");
        text = TAG.matcher(text).replaceAll("");
        return decodeEntities(text).trim();
    }

    private static String decodeEntities(String text) {
        Matcher m = ENTITY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(decode(m.group(1), m.group())));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String decode(String entity, String original) {
        try {
            if (entity.startsWith("#x") || entity.startsWith("#X")) {
                return Character.toString(Integer.parseInt(entity.substring(2), 16));
            }
            if (entity.startsWith("#")) {
                return Character.toString(Integer.parseInt(entity.substring(1)));
            }
        } catch (IllegalArgumentException ex) {
            return original;
        }
        return NAMED.getOrDefault(entity, original);
    }

}
