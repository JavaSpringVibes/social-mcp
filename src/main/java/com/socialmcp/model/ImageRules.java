package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A platform's image limits (SPEC §4, Tool 9). {@code maxPixels} and {@code recommendedMaxDimension} are null where
 * the platform has none; {@code maxAltTextLength} is in graphemes.
 */
public record ImageRules(
        int maxImages,
        long maxBytes,
        @Nullable Long maxPixels,
        int maxAltTextLength,
        List<String> mimeTypes,
        boolean withQuote,
        boolean withPoll,
        @Nullable Integer recommendedMaxDimension,
        String source) {
}
