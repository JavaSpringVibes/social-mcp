package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * Internal: what a quoting post needs to know about the quoted post (SPEC §5, Quote). {@code visibility} is
 * Mastodon-only; {@code caveat} is appended to the confirmation, e.g. when the quote awaits approval.
 */
public record QuoteTarget(
        PublishedPost quoted,
        String author,
        @Nullable String visibility,
        @Nullable String caveat) {
}
