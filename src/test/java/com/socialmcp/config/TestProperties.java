package com.socialmcp.config;

import java.time.Duration;
import java.util.List;

/**
 * Builds {@link SocialProperties} with the same defaults as the {@code @DefaultValue} annotations.
 */
public final class TestProperties {

    private TestProperties() {
    }

    public static SocialProperties.Mastodon mastodon(String instanceUrl, String accessToken, String threadVisibility) {
        return new SocialProperties.Mastodon(instanceUrl, accessToken, 500, threadVisibility);
    }

    public static SocialProperties.Bluesky bluesky(String pdsUrl, String handle, String appPassword) {
        return new SocialProperties.Bluesky(pdsUrl, handle, appPassword, 2_000_000);
    }

    public static SocialProperties.Media media() {
        return new SocialProperties.Media(List.of(), true, 20_971_520, Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    public static SocialProperties with(boolean postingEnabled, int maxParts) {
        return new SocialProperties(postingEnabled, new SocialProperties.Read(10, 40),
                new SocialProperties.ThreadSettings(maxParts), media(), mastodon("https://mastodon.social", "", "unlisted"),
                bluesky("https://bsky.social", "", ""));
    }

    public static SocialProperties with(SocialProperties.Mastodon mastodon) {
        SocialProperties base = with(true, 10);
        return new SocialProperties(true, base.read(), base.thread(), base.media(), mastodon, base.bluesky());
    }

    public static SocialProperties with(SocialProperties.Bluesky bluesky) {
        SocialProperties base = with(true, 10);
        return new SocialProperties(true, base.read(), base.thread(), base.media(), base.mastodon(), bluesky);
    }

}
