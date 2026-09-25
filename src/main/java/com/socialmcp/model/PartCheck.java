package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/** The measurement of one final post text (SPEC §4, Tool 10). {@code bytes} is null on platforms without a byte limit. */
public record PartCheck(int index, String text, int length, @Nullable Integer bytes, boolean ok,
		@Nullable String reason) {
}
