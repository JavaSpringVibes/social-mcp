package com.socialmcp.model;

import java.util.List;

/**
 * A post with its direct replies, likers and reposters (SPEC §4, Tool 5).
 */
public record PostInteractions(
        PostResult post,
        List<PostResult> replies,
        List<AccountSummary> likedBy,
        List<AccountSummary> repostedBy) {
}
