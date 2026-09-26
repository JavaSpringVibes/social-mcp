package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * A trending tag or topic (SPEC §4, Tool 6). {@code recentUses} is null when the platform reports no count.
 */
public record TrendTag(String name, String url, @Nullable Long recentUses) {
}
