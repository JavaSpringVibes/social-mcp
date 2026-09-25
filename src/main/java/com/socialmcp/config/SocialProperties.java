package com.socialmcp.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

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
		@DefaultValue @Valid Mastodon mastodon,
		@DefaultValue @Valid Bluesky bluesky) {

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
			@DefaultValue("") String appPassword) {

		public Bluesky {
			pdsUrl = pdsUrl.isBlank() ? "https://bsky.social" : stripTrailingSlash(pdsUrl.trim());
			handle = handle.trim();
			appPassword = appPassword.trim();
		}

		public boolean isConfigured() {
			return !handle.isEmpty() && !appPassword.isEmpty();
		}
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

}
