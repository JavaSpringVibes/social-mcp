package com.socialmcp.model;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The check of one image (SPEC §4, Tool 10). {@code problems} holds the SPEC §6.14 messages without their
 * {@code "Image <i>"} prefix; {@code fitWithin} is the size to resize to when the image is too large.
 */
public record ImageCheck(
		int index,
		String source,
		boolean ok,
		@Nullable String mimeType,
		@Nullable Long bytes,
		@Nullable Integer width,
		@Nullable Integer height,
		List<String> problems,
		@Nullable Dimensions fitWithin) {
}
