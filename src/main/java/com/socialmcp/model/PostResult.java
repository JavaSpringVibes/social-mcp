package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/** A normalized post (SPEC §4, Tool 1). {@code quote} and {@code poll} are null when the post has none. */
public record PostResult(
		String platform,
		String id,
		String author,
		String text,
		@Nullable String createdAt,
		String url,
		long replyCount,
		long repostCount,
		long likeCount,
		@Nullable QuoteSummary quote,
		@Nullable PollSummary poll) {

	public PostResult withLikeCount(long count) {
		return new PostResult(platform, id, author, text, createdAt, url, replyCount, repostCount, count, quote, poll);
	}

	public PostResult withRepostCount(long count) {
		return new PostResult(platform, id, author, text, createdAt, url, replyCount, count, likeCount, quote, poll);
	}

	public PostResult withPoll(@Nullable PollSummary newPoll) {
		return new PostResult(platform, id, author, text, createdAt, url, replyCount, repostCount, likeCount, quote,
				newPoll);
	}

}
