package com.socialmcp.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The {@code poll} parameter of {@code createSocialPost} (SPEC §4, Tool 8). Defaults: 1440 minutes, single choice,
 * totals shown. Spring AI generates its JSON schema from this record.
 */
public record PollInput(
        @Nullable List<String> options,
        @Nullable Integer expiresInMinutes,
        @Nullable Boolean multiple,
        @Nullable Boolean hideTotals) {

    public static final int DEFAULT_EXPIRES_IN_MINUTES = 1440;

    public int expiresInMinutesOrDefault() {
        return expiresInMinutes == null ? DEFAULT_EXPIRES_IN_MINUTES : expiresInMinutes;
    }

    public boolean multipleOrDefault() {
        return multiple != null && multiple;
    }

    public boolean hideTotalsOrDefault() {
        return hideTotals != null && hideTotals;
    }

}
