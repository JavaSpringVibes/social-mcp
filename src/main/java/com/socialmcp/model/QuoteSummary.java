package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * The post a post quotes (SPEC §4, Tool 1). {@code state} is {@code accepted} when the quoted post can be shown;
 * otherwise the other fields may be null. The quoted post's own quote and poll are never included.
 */
public record QuoteSummary(
        String state,
        @Nullable String id,
        @Nullable String author,
        @Nullable String text,
        @Nullable String url) {
}
