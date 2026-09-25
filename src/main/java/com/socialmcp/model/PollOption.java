package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * One answer of a poll (SPEC §4, Tool 1). {@code number} is 1-based; {@code votesCount} is null while the author
 * hides totals until the poll ends.
 */
public record PollOption(int number, String title, @Nullable Long votesCount) {
}
