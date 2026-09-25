package com.socialmcp.model;

/**
 * The outcome of a {@code setPostAction} action (SPEC §4, Tool 13). {@code post} is always the original post, with
 * counts reflecting the action.
 */
public record PostActionResult(String platform, String action, String status, PostResult post) {
}
