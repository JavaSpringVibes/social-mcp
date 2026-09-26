package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * Internal: what a reply needs to know about the post it answers (SPEC §5, Reply). {@code visibility} is Mastodon-only;
 * {@code mention} is the author acct to mention, or null when no mention is needed.
 */
public record ReplyTarget(
        PostResult inReplyTo,
        PublishedPost parent,
        PublishedPost root,
        @Nullable String visibility,
        @Nullable String mention) {
}
