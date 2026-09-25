package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/** A platform's posting limits (SPEC §4, Tool 9). */
public record PostingRules(
		String platform,
		int maxLength,
		String unit,
		@Nullable Integer urlLength,
		@Nullable Integer maxBytes,
		int maxThreadParts,
		String numberingFormat,
		int numberingReserve,
		@Nullable String followUpVisibility,
		String countingNotes,
		String source) {
}
