package com.socialmcp.model;

import java.util.List;

/** The per-part and per-image check of a draft (SPEC §4, Tool 10). */
public record PostCheckResult(
		String platform,
		boolean valid,
		int maxLength,
		String unit,
		List<String> problems,
		List<PartCheck> parts,
		List<ImageCheck> images) {
}
