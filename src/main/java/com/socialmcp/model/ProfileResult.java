package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/** A profile summary with counts (SPEC §4, Tool 4). */
public record ProfileResult(
		String platform,
		String id,
		String handle,
		String displayName,
		String bio,
		long followersCount,
		long followingCount,
		long postsCount,
		boolean isPrivate,
		@Nullable String createdAt,
		String url) {
}
