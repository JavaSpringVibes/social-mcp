package com.socialmcp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Typed binding of the {@code social.*} properties (SPEC §7).
 *
 * <p>Every value has a default ({@link DefaultValue}), so the application starts without any credentials and no
 * component is ever null. Invalid non-credential values fail startup through Bean Validation.
 */
@Validated
@ConfigurationProperties("social")
public record SocialProperties(
        @DefaultValue("true") boolean postingEnabled,
        @DefaultValue @Valid Read read,
        @DefaultValue @Valid ThreadSettings thread,
        @DefaultValue @Valid Media media,
        @DefaultValue @Valid Mastodon mastodon,
        @DefaultValue @Valid Bluesky bluesky) {

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Image loading (SPEC §6.14). An empty {@code allowedDirs} means any file the process can read.
     */
    public record Media(
            @DefaultValue List<String> allowedDirs,
            @DefaultValue("true") boolean allowUrls,
            @DefaultValue("20971520") @Min(value = 1, message = "must be between 1 and 104857600")
            @Max(value = 104_857_600, message = "must be between 1 and 104857600") long maxReadBytes,
            @DefaultValue("30s") Duration downloadTimeout,
            @DefaultValue("30s") Duration processingTimeout) {

        private static final Duration MIN_TIMEOUT = Duration.ofSeconds(1);

        private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);

        public Media {
            allowedDirs = allowedDirs.stream().map(String::trim).filter(d -> !d.isEmpty()).toList();
        }

        private static boolean inRange(Duration timeout) {
            return timeout.compareTo(MIN_TIMEOUT) >= 0 && timeout.compareTo(MAX_TIMEOUT) <= 0;
        }

        @AssertTrue(message = "social.media.allowed-dirs entries must be absolute paths")
        public boolean isAllowedDirsAbsolute() {
            return allowedDirs.stream().allMatch(d -> Path.of(d).isAbsolute());
        }

        @AssertTrue(message = "social.media.download-timeout and social.media.processing-timeout must be between 1s and 5m")
        public boolean isTimeoutsInRange() {
            return inRange(downloadTimeout) && inRange(processingTimeout);
        }
    }

    public record Read(
            @DefaultValue("10") @Min(value = 1, message = "must be at least 1") int defaultLimit,
            @DefaultValue("40") @Min(value = 1, message = "must be between 1 and 100")
            @Max(value = 100, message = "must be between 1 and 100") int maxLimit) {

        @AssertTrue(message = "social.read.default-limit must not exceed social.read.max-limit")
        public boolean isDefaultLimitWithinMaxLimit() {
            return defaultLimit <= maxLimit;
        }
    }

    public record ThreadSettings(
            @DefaultValue("10") @Min(value = 2, message = "must be between 2 and 25")
            @Max(value = 25, message = "must be between 2 and 25") int maxParts) {
    }

    public record Mastodon(
            @DefaultValue("https://mastodon.social") String instanceUrl,
            @DefaultValue("") String accessToken,
            @DefaultValue("500") @Min(value = 1, message = "must be positive") int maxLength,
            @DefaultValue("unlisted") @Pattern(regexp = "unlisted|public",
                    message = "must be 'unlisted' or 'public'") String threadVisibility) {

        public Mastodon {
            instanceUrl = instanceUrl.isBlank() ? "https://mastodon.social" : stripTrailingSlash(instanceUrl.trim());
            accessToken = accessToken.trim();
            threadVisibility = threadVisibility.trim().toLowerCase();
        }

        public boolean isConfigured() {
            return !accessToken.isEmpty();
        }
    }

    public record Bluesky(
            @DefaultValue("https://bsky.social") String pdsUrl,
            @DefaultValue("") String handle,
            @DefaultValue("") String appPassword,
            @DefaultValue("2000000") @Min(value = 1, message = "must be between 1 and 2000000")
            @Max(value = 2_000_000, message = "must be between 1 and 2000000") long maxImageBytes) {

        public Bluesky {
            pdsUrl = pdsUrl.isBlank() ? "https://bsky.social" : stripTrailingSlash(pdsUrl.trim());
            handle = handle.trim();
            appPassword = appPassword.trim();
        }

        public boolean isConfigured() {
            return !handle.isEmpty() && !appPassword.isEmpty();
        }
    }

}
