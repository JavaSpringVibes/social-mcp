package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * Internal handle to a post just created, carrying what is needed to reply to it.
 * {@code cid} is Bluesky-only and null on Mastodon.
 */
public record PublishedPost(String id, @Nullable String cid, String url) {
}
