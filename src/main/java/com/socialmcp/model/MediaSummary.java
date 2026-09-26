package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * One file attached to a post (SPEC §4, Tool 1). {@code type} is {@code image}, {@code gifv}, {@code video},
 * {@code audio} or {@code unknown}; {@code altText} is null when the author gave none.
 */
public record MediaSummary(String type, @Nullable String url, @Nullable String previewUrl, @Nullable String altText) {
}
