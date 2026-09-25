package com.socialmcp.model;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A poll attached to a post (SPEC §4, Tool 1). {@code votersCount} is null for single-choice polls;
 * {@code ownVotes} holds the configured account's 1-based choices.
 */
public record PollSummary(
		List<PollOption> options,
		boolean multiple,
		boolean expired,
		@Nullable String expiresAt,
		long votesCount,
		@Nullable Long votersCount,
		boolean voted,
		List<Integer> ownVotes) {
}
