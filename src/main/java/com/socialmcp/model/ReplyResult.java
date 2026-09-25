package com.socialmcp.model;

/**
 * A published reply (SPEC §4, Tool 14). {@code text} is exactly what was published, including any mention prefix.
 */
public record ReplyResult(String platform, String url, PostResult inReplyTo, String text) {
}
