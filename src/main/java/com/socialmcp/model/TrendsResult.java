package com.socialmcp.model;

import java.util.List;

/**
 * Trending tags/topics and posts (SPEC §4, Tool 6).
 */
public record TrendsResult(String platform, List<TrendTag> tags, List<PostResult> posts, String notes) {
}
