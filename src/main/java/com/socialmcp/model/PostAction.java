package com.socialmcp.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The actions of {@code setPostAction} (SPEC §4, Tool 13). */
public enum PostAction {

	LIKE("Liking"), UNLIKE("Unliking"), REPOST("Reposting"), UNREPOST("Unreposting"), BOOKMARK("Bookmarking"),
	UNBOOKMARK("Unbookmarking");

	private final String gerund;

	PostAction(String gerund) {
		this.gerund = gerund;
	}

	/** The canonical lowercase name, as accepted and echoed by the tool. */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** Used in the kill-switch message, e.g. {@code "Liking is disabled"} (SPEC §6.3). */
	public String gerund() {
		return gerund;
	}

	/** Matches case-insensitively after trimming (SPEC §6.11). */
	public static Optional<PostAction> parse(String value) {
		String id = value.trim().toLowerCase(Locale.ROOT);
		return Arrays.stream(values()).filter(a -> a.id().equals(id)).findFirst();
	}

}
