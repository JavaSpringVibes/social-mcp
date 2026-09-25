package com.socialmcp.model;

/**
 * A platform's poll limits (SPEC §4, Tool 9). {@code maxOptionLength} is in graphemes; expirations are whole minutes,
 * rounded inward.
 */
public record PollRules(int maxOptions, int maxOptionLength, int minExpiresInMinutes, int maxExpiresInMinutes) {
}
