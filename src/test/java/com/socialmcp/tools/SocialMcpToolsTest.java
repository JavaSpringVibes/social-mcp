package com.socialmcp.tools;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.config.TestProperties;
import com.socialmcp.model.PartCheck;
import com.socialmcp.model.PostCheckResult;
import com.socialmcp.model.ThreadResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialMcpToolsTest {

	private FakePlatform mastodon;

	private FakePlatform bluesky;

	private SocialMcpTools tools;

	@BeforeEach
	void setUp() {
		mastodon = new FakePlatform("mastodon");
		bluesky = new FakePlatform("bluesky");
		tools = tools(properties(true, 10));
	}

	private SocialMcpTools tools(SocialProperties properties) {
		return new SocialMcpTools(List.of(mastodon, bluesky), properties);
	}

	private static SocialProperties properties(boolean postingEnabled, int maxParts) {
		return TestProperties.with(postingEnabled, maxParts);
	}

	// --- Routing and arguments ---

	@Test
	void routesPlatformCaseInsensitivelyAfterTrimming() {
		tools.searchSocialPosts("  BlueSky ", "java", null, null);
		tools.searchSocialPosts("mastodon", "java", null, null);
		assertThat(bluesky.calls).containsExactly("search:java:LATEST:10");
		assertThat(mastodon.calls).containsExactly("search:java:LATEST:10");
	}

	@Test
	void unknownPlatformIsRejected() {
		assertThatThrownBy(() -> tools.searchSocialPosts("x", "java", null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Unknown platform 'x'. Use one of: mastodon, bluesky");
	}

	@Test
	void unconfiguredPlatformIsRejected() {
		bluesky.configured = false;
		assertThatThrownBy(() -> tools.getSocialTrends("bluesky", null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Platform bluesky is not configured");
		assertThat(bluesky.calls).isEmpty();
	}

	@Test
	void parsesTypeAndSortInAnyCase() {
		tools.getSocialTimeline("mastodon", "HOME", null);
		tools.getSocialTimeline("mastodon", " own ", null);
		tools.searchSocialPosts("mastodon", "q", "Top", null);
		assertThat(mastodon.calls).containsExactly("timeline:HOME:10", "timeline:OWN:10", "search:q:TOP:10");
	}

	@Test
	void rejectsUnknownTypeAndSort() {
		assertThatThrownBy(() -> tools.getSocialTimeline("mastodon", "public", null))
			.hasMessage("Unknown timeline type 'public'. Use one of: home, own");
		assertThatThrownBy(() -> tools.searchSocialPosts("mastodon", "q", "oldest", null))
			.hasMessage("Unknown sort 'oldest'. Use one of: latest, top");
	}

	@Test
	void limitDefaultsRejectsAndClamps() {
		tools.getSocialTimeline("mastodon", "home", null);
		tools.getSocialTimeline("mastodon", "home", 500);
		assertThat(mastodon.calls).containsExactly("timeline:HOME:10", "timeline:HOME:40");
		assertThatThrownBy(() -> tools.getSocialTimeline("mastodon", "home", 0))
			.hasMessage("limit must be at least 1");
	}

	@Test
	void handleIsNormalizedAndValidated() {
		tools.getSocialUserPosts("bluesky", "@alice.bsky.social", null);
		tools.getSocialUserPosts("bluesky", "alice.bsky.social", null);
		assertThat(bluesky.calls).containsExactly("userPosts:alice.bsky.social:10", "userPosts:alice.bsky.social:10");
		assertThatThrownBy(() -> tools.getSocialUserPosts("bluesky", "Bad Handle!", null))
			.hasMessage("Invalid bluesky handle 'Bad Handle!'");
		assertThat(bluesky.calls).hasSize(2);
	}

	@Test
	void profileWithoutHandleMeansOwnAccount() {
		tools.getSocialProfile("mastodon", null);
		tools.getSocialProfile("mastodon", "  ");
		tools.getSocialProfile("mastodon", "@bob");
		assertThat(mastodon.calls).containsExactly("profile:null", "profile:null", "profile:bob");
	}

	// --- checkSocialPost ---

	@Test
	void checkNumbersPartsAndMeasuresWithSuffix() {
		PostCheckResult result = tools.checkSocialPost("mastodon", List.of(" one ", "two", "three"), null);
		assertThat(result.valid()).isTrue();
		assertThat(result.parts()).extracting(PartCheck::text)
			.containsExactly("one (1/3)", "two (2/3)", "three (3/3)");
		assertThat(result.parts().get(0).length()).isEqualTo(9);
	}

	@Test
	void checkWithoutNumberingAndSinglePartHasNoSuffix() {
		assertThat(tools.checkSocialPost("mastodon", List.of("a", "b"), false).parts()).extracting(PartCheck::text)
			.containsExactly("a", "b");
		assertThat(tools.checkSocialPost("mastodon", List.of("solo"), true).parts().get(0).text()).isEqualTo("solo");
	}

	@Test
	void partThatFitsOnlyWithoutSuffixIsOverWhenNumbered() {
		String text = "x".repeat(15); // fits in 20 alone, but with " (1/2)" it is 21
		PostCheckResult result = tools.checkSocialPost("mastodon", List.of(text, "ok"), true);
		assertThat(result.valid()).isFalse();
		assertThat(result.problems()).containsExactly("Part 1 is 21/20 graphemes (1 over)");
	}

	@Test
	void checkReportsBlankOverlongAndTooManyPartsWithoutThrowing() {
		tools = tools(properties(true, 2));
		PostCheckResult result = tools.checkSocialPost("mastodon", List.of("ok", "  ", "y".repeat(30)), false);
		assertThat(result.valid()).isFalse();
		assertThat(result.problems()).containsExactly("Part 2 is blank", "Part 3 is 30/20 graphemes (10 over)",
				"3 parts, but the maximum is 2");
		assertThat(mastodon.posted).isEmpty();
	}

	@Test
	void checkRequiresParts() {
		assertThatThrownBy(() -> tools.checkSocialPost("mastodon", List.of(), null))
			.hasMessage("parts must contain at least one item");
		assertThatThrownBy(() -> tools.checkSocialPost("mastodon", null, null))
			.hasMessage("parts must contain at least one item");
	}

	@Test
	void checkAndRulesWorkWhenPostingIsDisabled() {
		tools = tools(properties(false, 10));
		assertThat(tools.checkSocialPost("mastodon", List.of("hi"), null).valid()).isTrue();
		assertThat(tools.getSocialPostingRules("mastodon").maxLength()).isEqualTo(20);
		tools.getSocialTimeline("mastodon", "home", null);
		assertThat(mastodon.calls).containsExactly("timeline:HOME:10");
	}

	// --- createSocialPost ---

	@Test
	void createPostPublishesTrimmedText() {
		assertThat(tools.createSocialPost("bluesky", "  hello ")).isEqualTo("Posted to bluesky: https://example/1");
		assertThat(bluesky.posted.get(0)).containsExactly("hello", null, null);
	}

	@Test
	void createPostTooLongSuggestsThread() {
		assertThatThrownBy(() -> tools.createSocialPost("bluesky", "z".repeat(25))).hasMessage(
				"Content is 25/20 graphemes (5 over) on bluesky. Split it into parts and use createSocialThread.");
		assertThat(bluesky.posted).isEmpty();
	}

	@Test
	void postingDisabledBlocksBothPostingTools() {
		tools = tools(properties(false, 10));
		assertThatThrownBy(() -> tools.createSocialPost("mastodon", "hi")).isInstanceOf(IllegalStateException.class)
			.hasMessage("Posting is disabled");
		assertThatThrownBy(() -> tools.createSocialThread("mastodon", List.of("a", "b"), null))
			.hasMessage("Posting is disabled");
		assertThat(mastodon.posted).isEmpty();
	}

	// --- createSocialThread ---

	@Test
	void threadChainsRootAndParent() {
		ThreadResult result = tools.createSocialThread("bluesky", List.of("a", "b", "c"), null);
		assertThat(result.partsPosted()).isEqualTo(3);
		assertThat(result.urls()).containsExactly("https://example/1", "https://example/2", "https://example/3");
		assertThat(bluesky.posted.get(0)).containsExactly("a (1/3)", null, null);
		assertThat(bluesky.posted.get(1)).containsExactly("b (2/3)", "id1", "id1");
		assertThat(bluesky.posted.get(2)).containsExactly("c (3/3)", "id1", "id2");
	}

	@Test
	void invalidThreadPostsNothing() {
		assertThatThrownBy(() -> tools.createSocialThread("bluesky", List.of("ok", "q".repeat(30), " "), false))
			.hasMessage("Thread rejected, nothing was posted: Part 2 is 30/20 graphemes (10 over); Part 3 is blank.");
		assertThat(bluesky.posted).isEmpty();
	}

	@Test
	void partialFailureStopsAndReportsPostedParts() {
		bluesky.failPostNumber = n -> n == 2;
		assertThatThrownBy(() -> tools.createSocialThread("bluesky", List.of("a", "b", "c"), null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Posted 1 of 3 parts: https://example/1. Part 2 failed: bluesky API error 500: boom. "
					+ "Already-posted parts were not deleted.");
		assertThat(bluesky.posted).hasSize(1);
	}

	@Test
	void firstPartFailureIsReportedPlainly() {
		bluesky.failPostNumber = n -> n == 1;
		assertThatThrownBy(() -> tools.createSocialThread("bluesky", List.of("a", "b"), null))
			.hasMessage("bluesky API error 500: boom");
	}

	@Test
	void onePartThreadIsASinglePostWithoutNumbering() {
		tools.createSocialThread("bluesky", List.of("solo"), true);
		assertThat(bluesky.posted.get(0)).containsExactly("solo", null, null);
	}

}
