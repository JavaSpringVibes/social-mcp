package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * Internal: a post created with an optional quote or poll, plus a caveat for the confirmation (SPEC §4, Tool 8), e.g.
 * that the quote is waiting for approval.
 */
public record NewPost(PublishedPost post, @Nullable String caveat) {
}
