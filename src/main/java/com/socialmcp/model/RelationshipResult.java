package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of a {@code setAccountRelationship} action (SPEC §4, Tool 12). {@code note} is a short remark for the
 * user when the status alone could mislead, otherwise null.
 */
public record RelationshipResult(
        String platform,
        String action,
        String status,
        AccountSummary account,
        @Nullable String note) {
}
