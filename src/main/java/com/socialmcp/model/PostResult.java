package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A normalized post (SPEC §4, Tool 1). {@code quote} and {@code poll} are null when the post has none; {@code media} is
 * empty when nothing is attached.
 */
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
        @Nullable PollSummary poll,
        List<MediaSummary> media) {

    public PostResult withLikeCount(long count) {
        return new PostResult(platform, id, author, text, createdAt, url, replyCount, repostCount, count, quote, poll,
                media);
    }

    public PostResult withRepostCount(long count) {
        return new PostResult(platform, id, author, text, createdAt, url, replyCount, count, likeCount, quote, poll,
                media);
    }

    public PostResult withPoll(@Nullable PollSummary newPoll) {
        return new PostResult(platform, id, author, text, createdAt, url, replyCount, repostCount, likeCount, quote,
                newPoll, media);
    }

}
