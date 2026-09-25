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
		assertThat(tools.createSocialPost("bluesky", "  hello ", null, null)).isEqualTo("Posted to bluesky: https://example/1");
		assertThat(bluesky.posted.get(0)).containsExactly("hello", null, null);
	}

	@Test
	void createPostTooLongSuggestsThread() {
		assertThatThrownBy(() -> tools.createSocialPost("bluesky", "z".repeat(25), null, null)).hasMessage(
				"Content is 25/20 graphemes (5 over) on bluesky. Split it into parts and use createSocialThread.");
		assertThat(bluesky.posted).isEmpty();
	}

	@Test
	void postingDisabledBlocksBothPostingTools() {
		tools = tools(properties(false, 10));
		assertThatThrownBy(() -> tools.createSocialPost("mastodon", "hi", null, null)).isInstanceOf(IllegalStateException.class)
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

	// --- action dispatch (SPEC §6.11) ---

	@Test
	void actionsMatchCaseInsensitivelyAndEchoTheCanonicalName() {
		assertThat(tools.setAccountRelationship("mastodon", "@bob.social", " Follow ").action()).isEqualTo("follow");
		tools.setAccountRelationship("mastodon", "bob.social", "UNMUTE");
		assertThat(tools.setPostAction("bluesky", "at://x", "Bookmark").action()).isEqualTo("bookmark");
		assertThat(mastodon.calls).containsExactly("relationship:bob.social:FOLLOW", "relationship:bob.social:UNMUTE");
		assertThat(bluesky.calls).containsExactly("postAction:at://x:BOOKMARK");
	}

	@Test
	void unknownActionIsRejectedBeforeTheKillSwitch() {
		tools = tools(properties(false, 10));
		assertThatThrownBy(() -> tools.setAccountRelationship("mastodon", "bob.social", "like"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Unknown action 'like'. Use one of: follow, unfollow, block, unblock, mute, unmute");
		assertThatThrownBy(() -> tools.setPostAction("mastodon", "1", "dislike"))
			.hasMessage("Unknown action 'dislike'. Use one of: like, unlike, repost, unrepost, bookmark, unbookmark");
		assertThatThrownBy(() -> tools.setPostAction("mastodon", "1", null)).hasMessageStartingWith("Unknown action 'null'");
		assertThat(mastodon.calls).isEmpty();
	}

	@Test
	void killSwitchNamesEachWriteAction() {
		tools = tools(properties(false, 10));
		assertThatThrownBy(() -> tools.setAccountRelationship("mastodon", "bob.social", "mute"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Muting is disabled");
		assertThatThrownBy(() -> tools.setPostAction("mastodon", "1", "unbookmark"))
			.hasMessage("Unbookmarking is disabled");
		assertThatThrownBy(() -> tools.replyToSocialPost("mastodon", "1", "hi")).hasMessage("Posting is disabled");
		assertThatThrownBy(() -> tools.voteInSocialPoll("mastodon", "1", List.of(1))).hasMessage("Voting is disabled");
		assertThat(tools.getSocialBookmarks("mastodon", null)).isEmpty();
		assertThat(mastodon.calls).containsExactly("bookmarks:10");
	}

	@Test
	void relationshipHandleIsValidated() {
		assertThatThrownBy(() -> tools.setAccountRelationship("bluesky", "Not Valid", "follow"))
			.hasMessage("Invalid bluesky handle 'Not Valid'");
		assertThat(bluesky.calls).isEmpty();
	}

	// --- replyToSocialPost ---

	@Test
	void replyAddsTheAuthorMention() {
		mastodon.replyMention = "al";
		ReplyResultAssert.of(tools.replyToSocialPost("mastodon", "7", "  thanks ")).hasText("@al thanks");
		assertThat(mastodon.calls).containsExactly("replyTarget:7", "reply:7:@al thanks");
	}

	@Test
	void replyKeepsAnExistingMentionInAnyCase() {
		mastodon.replyMention = "al";
		tools.replyToSocialPost("mastodon", "7", "hey @AL, thanks");
		assertThat(mastodon.calls).contains("reply:7:hey @AL, thanks");
		// The fully qualified handle of the author also counts as a mention.
		assertThat(SocialMcpTools.mentions("thanks @Alice@Example.social!", "al", "@alice@example.social")).isTrue();
	}

	@Test
	void mentionMatchingRespectsWordBoundaries() {
		assertThat(SocialMcpTools.mentions("hi @bob", "bob", "@bob@m.social")).isTrue();
		assertThat(SocialMcpTools.mentions("hi @bobby", "bob", "@bob@m.social")).isFalse();
		assertThat(SocialMcpTools.mentions("hi @bob@other.social", "bob", "@bob@m.social")).isFalse();
		assertThat(SocialMcpTools.mentions("mail bob@m.social", "bob", "@bob@m.social")).isFalse();
	}

	@Test
	void replyWithoutMentionOrWithOwnPost() {
		mastodon.replyMention = null;
		assertThat(tools.replyToSocialPost("mastodon", "7", "more").text()).isEqualTo("more");
	}

	@Test
	void replyThatFitsOnlyWithoutThePrefixIsRejected() {
		mastodon.replyMention = "alice";
		String content = "z".repeat(15); // 15 fits the fake's 20, "@alice " + 15 = 22 doesn't
		assertThatThrownBy(() -> tools.replyToSocialPost("mastodon", "7", content)).hasMessage(
				"Reply is 22/20 graphemes (2 over) on mastodon. Shorten it; replies are single posts. "
						+ "The automatic mention '@alice ' counts toward the limit.");
		assertThat(mastodon.calls).containsExactly("replyTarget:7");
	}

	@Test
	void blankReplyMakesNoCall() {
		assertThatThrownBy(() -> tools.replyToSocialPost("mastodon", "7", "  ")).hasMessage("content must not be blank");
		assertThat(mastodon.calls).isEmpty();
	}

	// --- createSocialPost with quote or poll ---

	@Test
	void quoteIsResolvedThenPostedWithItsCaveat() {
		mastodon.quoteCaveat = "the quote is waiting for @alice@example.social to approve it";
		assertThat(tools.createSocialPost("mastodon", "my take", "7", null)).isEqualTo(
				"Posted to mastodon: https://example/1 (the quote is waiting for @alice@example.social to approve it)");
		assertThat(mastodon.calls).containsExactly("quoteTarget:7", "topLevel:my take:7:null");
	}

	@Test
	void quoteAndPollTogetherAreRejected() {
		assertThatThrownBy(() -> tools.createSocialPost("mastodon", "q", "7", poll("a", "b")))
			.hasMessage("A post can have a quote or a poll, not both");
		assertThat(mastodon.calls).isEmpty();
	}

	@Test
	void validPollIsPosted() {
		tools.createSocialPost("mastodon", "Java or Kotlin?", null, poll("Java", "Kotlin"));
		assertThat(mastodon.calls).containsExactly("topLevel:Java or Kotlin?:null:[Java, Kotlin]");
	}

	@Test
	void pollValidationFollowsTheLimits() {
		assertPollRejected(poll("only"), "A poll needs 2 to 4 options on mastodon");
		assertPollRejected(poll("a", "b", "c", "d", "e"), "A poll needs 2 to 4 options on mastodon");
		assertPollRejected(new com.socialmcp.model.PollInput(null, null, null, null),
				"A poll needs 2 to 4 options on mastodon");
		assertPollRejected(poll("a", " "), "Poll option 2 is blank");
		assertPollRejected(poll("a", "x".repeat(51)), "Poll option 2 is 51/50 graphemes (1 over)");
		assertPollRejected(poll("Java", " Java"), "Poll options must be different");
		assertPollRejected(new com.socialmcp.model.PollInput(List.of("a", "b"), 4, null, null),
				"A poll must last between 5 minutes and 43829 minutes on mastodon");
		assertThat(mastodon.posted).isEmpty();
		// case differs, so these are different options
		tools.createSocialPost("mastodon", "q", null, poll("Java", "java"));
		assertThat(mastodon.posted).hasSize(1);
	}

	@Test
	void blueskyPollIsRejectedWithoutACall() {
		bluesky.polls = false;
		assertThatThrownBy(() -> tools.createSocialPost("bluesky", "q", null, poll("a", "b")))
			.hasMessage("Bluesky doesn't support polls");
		assertThatThrownBy(() -> tools.voteInSocialPoll("bluesky", "at://x", List.of(1)))
			.hasMessage("Bluesky doesn't support polls");
		assertThat(bluesky.calls).isEmpty();
	}

	// --- voteInSocialPoll (SPEC §6.13) ---

	@Test
	void choicesAreValidatedBeforeAnyCall() {
		for (List<Integer> bad : List.of(List.<Integer>of(), List.of(0), List.of(2, 2))) {
			assertThatThrownBy(() -> tools.voteInSocialPoll("mastodon", "7", bad))
				.hasMessage("choices must be distinct option numbers starting at 1");
		}
		assertThatThrownBy(() -> tools.voteInSocialPoll("mastodon", "7", null))
			.hasMessage("choices must be distinct option numbers starting at 1");
		assertThat(mastodon.calls).isEmpty();
		tools.voteInSocialPoll("mastodon", "7", List.of(1, 3));
		assertThat(mastodon.calls).containsExactly("vote:7:[1, 3]");
	}

	private static com.socialmcp.model.PollInput poll(String... options) {
		return new com.socialmcp.model.PollInput(List.of(options), null, null, null);
	}

	private void assertPollRejected(com.socialmcp.model.PollInput poll, String message) {
		assertThatThrownBy(() -> tools.createSocialPost("mastodon", "q", null, poll))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage(message);
	}

	/** Tiny helper so reply assertions read naturally. */
	private record ReplyResultAssert(com.socialmcp.model.ReplyResult result) {

		static ReplyResultAssert of(com.socialmcp.model.ReplyResult result) {
			return new ReplyResultAssert(result);
		}

		void hasText(String text) {
			assertThat(result.text()).isEqualTo(text);
			assertThat(result.url()).isEqualTo("https://example/r1");
		}

	}

}
