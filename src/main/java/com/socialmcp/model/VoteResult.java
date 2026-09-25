package com.socialmcp.model;

/** The outcome of a vote (SPEC §4, Tool 16): {@code voted} or {@code already-voted}, with the updated poll. */
public record VoteResult(String platform, String status, PostResult post) {
}
