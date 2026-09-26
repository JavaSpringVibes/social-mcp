package com.socialmcp.model;

/**
 * A normalized account in a list (SPEC §4, Tool 5).
 */
public record AccountSummary(String platform, String id, String handle, String displayName, String bio, String url) {
}
