package com.socialmcp.config;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SocialPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
		.withUserConfiguration(Config.class);

	@Configuration
	@EnableConfigurationProperties(SocialProperties.class)
	static class Config {

	}

	@Test
	void defaultsApplyWithNoProperties() {
		runner.run(context -> {
			SocialProperties p = context.getBean(SocialProperties.class);
			assertThat(p.postingEnabled()).isTrue();
			assertThat(p.read().defaultLimit()).isEqualTo(10);
			assertThat(p.read().maxLimit()).isEqualTo(40);
			assertThat(p.thread().maxParts()).isEqualTo(10);
			assertThat(p.mastodon().instanceUrl()).isEqualTo("https://mastodon.social");
			assertThat(p.mastodon().maxLength()).isEqualTo(500);
			assertThat(p.mastodon().threadVisibility()).isEqualTo("unlisted");
			assertThat(p.mastodon().isConfigured()).isFalse();
			assertThat(p.bluesky().pdsUrl()).isEqualTo("https://bsky.social");
			assertThat(p.bluesky().isConfigured()).isFalse();
		});
	}

	@Test
	void valuesAreNormalized() {
		runner.withPropertyValues("social.mastodon.instance-url=https://example.social/ ",
				"social.mastodon.access-token= tok ", "social.mastodon.thread-visibility=unlisted",
				"social.bluesky.handle= me.bsky.social ", "social.bluesky.app-password=pw")
			.run(context -> {
				SocialProperties p = context.getBean(SocialProperties.class);
				assertThat(p.mastodon().instanceUrl()).isEqualTo("https://example.social");
				assertThat(p.mastodon().accessToken()).isEqualTo("tok");
				assertThat(p.mastodon().isConfigured()).isTrue();
				assertThat(p.bluesky().handle()).isEqualTo("me.bsky.social");
				assertThat(p.bluesky().isConfigured()).isTrue();
			});
	}

	@Test
	void emptyCredentialsAreNotConfigured() {
		runner.withPropertyValues("social.mastodon.access-token=", "social.bluesky.handle=a.bsky.social",
				"social.bluesky.app-password=")
			.run(context -> {
				SocialProperties p = context.getBean(SocialProperties.class);
				assertThat(p.mastodon().isConfigured()).isFalse();
				assertThat(p.bluesky().isConfigured()).isFalse();
			});
	}

	@Test
	void invalidThreadVisibilityFailsStartup() {
		assertStartupFails("social.mastodon.thread-visibility=private", "mastodon.threadVisibility",
				"must be 'unlisted' or 'public'");
	}

	@Test
	void invalidMaxPartsFailsStartup() {
		assertStartupFails("social.thread.max-parts=1", "thread.maxParts", "must be between 2 and 25");
		assertStartupFails("social.thread.max-parts=26", "thread.maxParts", "must be between 2 and 25");
	}

	@Test
	void invalidMaxLimitFailsStartup() {
		assertStartupFails("social.read.max-limit=0", "read.maxLimit", "must be between 1 and 100");
		assertStartupFails("social.read.max-limit=101", "read.maxLimit", "must be between 1 and 100");
	}

	@Test
	void invalidMastodonMaxLengthFailsStartup() {
		assertStartupFails("social.mastodon.max-length=0", "mastodon.maxLength", "must be positive");
	}

	@Test
	void defaultLimitAboveMaxLimitFailsStartup() {
		runner.withPropertyValues("social.read.default-limit=50", "social.read.max-limit=40")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(BindValidationException.class)
				.hasMessageContaining("social.read.default-limit must not exceed social.read.max-limit"));
	}

	private void assertStartupFails(String property, String field, String message) {
		runner.withPropertyValues(property)
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(BindValidationException.class)
				.hasMessageContaining(field)
				.hasMessageContaining(message));
	}

}
