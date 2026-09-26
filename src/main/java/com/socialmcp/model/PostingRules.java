package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * A platform's posting limits (SPEC §4, Tool 9). {@code quotes} says whether quote posts are supported;
 * {@code polls} is null where the platform has no polls; {@code images} holds the image limits.
 */
public record PostingRules(
        String platform,
        int maxLength,
        String unit,
        @Nullable Integer urlLength,
        @Nullable Integer maxBytes,
        int maxThreadParts,
        String numberingFormat,
        int numberingReserve,
        @Nullable String followUpVisibility,
        String countingNotes,
        String source,
        boolean quotes,
        @Nullable PollRules polls,
        ImageRules images) {
}
