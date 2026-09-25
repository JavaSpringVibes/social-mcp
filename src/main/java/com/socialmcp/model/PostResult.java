package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/** A normalized post (SPEC §4, Tool 1). */
public record PostResult(
		String platform,
		String id,
		String author,
		String text,
		@Nullable String createdAt,
		String url,
		long replyCount,
		long repostCount,
		long likeCount) {
}
