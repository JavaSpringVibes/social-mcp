package com.socialmcp.platform.mastodon;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.config.TestProperties;
import com.socialmcp.model.PartCheck;
import com.socialmcp.model.PostInteractions;
import com.socialmcp.model.PostResult;
import com.socialmcp.model.PostingRules;
import com.socialmcp.model.ProfileResult;
import com.socialmcp.model.PublishedPost;
import com.socialmcp.model.SearchSort;
import com.socialmcp.model.SimilarAccountsResult;
import com.socialmcp.model.TimelineType;
import com.socialmcp.model.TrendsResult;
import com.socialmcp.platform.TestSupport.Capture;
import com.socialmcp.platform.TestSupport.MutableClock;

import static com.socialmcp.platform.TestSupport.enc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MastodonServiceTest {

	private static final String BASE = "https://mastodon.test";

	private static final String FAMILY = "👨‍👩‍👧‍👦";

	private MockRestServiceServer server;

	private MutableClock clock;

	private MastodonService service;

	@BeforeEach
	void setUp() {
		service = service("unlisted");
	}

	private MastodonService service(String threadVisibility) {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
		clock = new MutableClock();
		SocialProperties properties = TestProperties.with(TestProperties.mastodon(BASE, "secret-token", threadVisibility));
		return new MastodonService(properties, builder, clock);
	}

	private void expectGet(String pathAndQuery, String json) {
		expectGet(ExpectedCount.once(), pathAndQuery, json);
	}

	private void expectGet(ExpectedCount count, String pathAndQuery, String json) {
		server.expect(count, requestTo(BASE + pathAndQuery))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("Authorization", "Bearer secret-token"))
			.andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
	}

	private void expectInstance(int maxCharacters) {
		expectGet("/api/v2/instance", instanceJson(maxCharacters));
	}

	private static String instanceJson(int maxCharacters) {
		return """
				{"domain":"mastodon.test","configuration":{"statuses":{"max_characters":%d,"characters_reserved_per_url":23}}}"""
			.formatted(maxCharacters);
	}

	private static String status(String id, String acct, int likes, int reblogs, int replies, String createdAt) {
		return """
				{"id":"%s","created_at":"%s","content":"<p>post %s</p>","url":"https://mastodon.test/@x/%s",
				 "replies_count":%d,"reblogs_count":%d,"favourites_count":%d,"reblog":null,
				 "account":{"id":"acc-%s","acct":"%s","display_name":"","note":"","url":"https://u/%s"}}"""
			.formatted(id, createdAt, id, id, replies, reblogs, likes, acct, acct, acct);
	}

	private static String status(String id, String acct) {
		return status(id, acct, 0, 0, 0, "2026-09-25T10:00:00.000Z");
	}

	// --- Search ---

	@Test
	void unicodeHashtagUsesTagTimeline() {
		expectGet("/api/v1/timelines/tag/" + enc("日本語") + "?limit=10", "[" + status("1", "bob@remote.social") + "]");
		List<PostResult> posts = service.searchPosts("#日本語", SearchSort.LATEST, 10);
		server.verify();
		assertThat(posts).singleElement().satisfies(p -> {
			assertThat(p.author()).isEqualTo("@bob@remote.social");
			assertThat(p.text()).isEqualTo("post 1");
			assertThat(p.createdAt()).isEqualTo("2026-09-25T10:00:00Z");
		});
	}

	@Test
	void otherQueriesUseFullTextSearch() {
		expectGet("/api/v2/search?q=" + enc("#rust lang") + "&type=statuses&limit=5", "{\"statuses\":[]}");
		assertThat(service.searchPosts("#rust lang", SearchSort.LATEST, 5)).isEmpty();
		server.verify();
	}

	@Test
	void topSortFetches40AndReranksByEngagementThenRecency() {
		String body = "[" + String.join(",", status("low", "a@r.social", 1, 0, 0, "2026-09-25T10:00:00Z"),
				status("mid-old", "a@r.social", 2, 1, 0, "2026-09-24T10:00:00Z"),
				status("mid-new", "a@r.social", 0, 1, 2, "2026-09-25T11:00:00Z"),
				status("high", "a@r.social", 10, 0, 0, "2026-09-20T10:00:00Z")) + "]";
		expectGet("/api/v1/timelines/tag/java?limit=40", body);
		List<PostResult> posts = service.searchPosts("#java", SearchSort.TOP, 3);
		server.verify();
		// scores: high 10, mid-new 0+2+2=4, mid-old 2+2+0=4 (older), low 1
		assertThat(posts).extracting(PostResult::id).containsExactly("high", "mid-new", "mid-old");
	}

	// --- Timelines and user posts ---

	@Test
	void homeTimelineMapsBoostsToTheOriginal() {
		String boost = """
				{"id":"b1","content":"","url":"u","account":{"id":"me","acct":"me@x.social"},
				 "reblog":%s}""".formatted(status("orig", "carol@y.social", 5, 6, 7, "2026-09-25T09:00:00Z"));
		expectGet("/api/v1/timelines/home?limit=10", "[" + boost + "]");
		PostResult post = service.getTimeline(TimelineType.HOME, 10).get(0);
		assertThat(post.id()).isEqualTo("orig");
		assertThat(post.author()).isEqualTo("@carol@y.social");
		assertThat(post.likeCount()).isEqualTo(5);
		assertThat(post.repostCount()).isEqualTo(6);
		assertThat(post.replyCount()).isEqualTo(7);
	}

	@Test
	void ownTimelineExcludesRepliesAndBoostsAndCachesAccountId() {
		expectGet(ExpectedCount.once(), "/api/v1/accounts/verify_credentials", "{\"id\":\"42\",\"acct\":\"me\"}");
		expectGet(ExpectedCount.twice(), "/api/v1/accounts/42/statuses?limit=1&exclude_replies=true&exclude_reblogs=true",
				"[" + status("9", "me@x.social") + "]");
		service.getTimeline(TimelineType.OWN, 1);
		service.getTimeline(TimelineType.OWN, 1);
		server.verify();
	}

	@Test
	void userPostsFallBackToResolvingSearchOn404() {
		server.expect(requestTo(BASE + "/api/v1/accounts/lookup?acct=" + enc("dave@far.social")))
			.andRespond(withResourceNotFound());
		expectGet("/api/v2/search?q=" + enc("@dave@far.social") + "&type=accounts&resolve=true&limit=1",
				"{\"accounts\":[{\"id\":\"77\",\"acct\":\"dave@far.social\"}]}");
		expectGet("/api/v1/accounts/77/statuses?limit=10&exclude_replies=true&exclude_reblogs=true", "[]");
		assertThat(service.getUserPosts("dave@far.social", 10)).isEmpty();
		server.verify();
	}

	@Test
	void resolvingSearchResultWithOtherAcctIsNotFound() {
		server.expect(requestTo(BASE + "/api/v1/accounts/lookup?acct=" + enc("dave@far.social")))
			.andRespond(withResourceNotFound());
		expectGet("/api/v2/search?q=" + enc("@dave@far.social") + "&type=accounts&resolve=true&limit=1",
				"{\"accounts\":[{\"id\":\"78\",\"acct\":\"davey@far.social\"}]}");
		assertThatThrownBy(() -> service.getUserPosts("dave@far.social", 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("User 'dave@far.social' not found on mastodon");
	}

	@Test
	void localAccountsAreQualifiedWithInstanceDomain() {
		expectInstance(500);
		expectGet("/api/v1/accounts/lookup?acct=alice", "{\"id\":\"5\",\"acct\":\"alice\"}");
		expectGet("/api/v1/accounts/5/statuses?limit=10&exclude_replies=true&exclude_reblogs=true",
				"[" + status("1", "alice") + "]");
		assertThat(service.getUserPosts("alice", 10).get(0).author()).isEqualTo("@alice@mastodon.test");
		server.verify();
	}

	@Test
	void domainFallsBackToConfiguredHostWhenInstanceFetchFails() {
		server.expect(requestTo(BASE + "/api/v2/instance")).andRespond(withServerError());
		expectGet("/api/v1/timelines/home?limit=10", "[" + status("1", "alice") + "]");
		assertThat(service.getTimeline(TimelineType.HOME, 10).get(0).author()).isEqualTo("@alice@mastodon.test");
	}

	// --- Profiles ---

	@Test
	void ownProfileIsFetchedFreshEachTimeAndMapsFields() {
		expectInstance(500);
		expectGet(ExpectedCount.twice(), "/api/v1/accounts/verify_credentials", """
				{"id":"42","acct":"me","display_name":"","note":"<p>Hi &amp; welcome</p>","locked":true,
				 "followers_count":12,"following_count":3,"statuses_count":99,
				 "created_at":"2020-01-01T00:00:00.000Z","url":"https://mastodon.test/@me"}""");
		service.getProfile(null);
		ProfileResult profile = service.getProfile(null);
		server.verify();
		assertThat(profile.handle()).isEqualTo("@me@mastodon.test");
		assertThat(profile.displayName()).isEqualTo("me@mastodon.test");
		assertThat(profile.bio()).isEqualTo("Hi & welcome");
		assertThat(profile.isPrivate()).isTrue();
		assertThat(profile.followersCount()).isEqualTo(12);
		assertThat(profile.followingCount()).isEqualTo(3);
		assertThat(profile.postsCount()).isEqualTo(99);
		assertThat(profile.createdAt()).isEqualTo("2020-01-01T00:00:00Z");
	}

	// --- Post interactions ---

	@Test
	void interactionsByIdKeepOnlyDirectReplies() {
		expectGet("/api/v1/statuses/100", status("100", "me@x.social", 3, 2, 2, "2026-09-25T10:00:00Z"));
		String reply = status("101", "a@r.social").replace("\"reblog\":null", "\"reblog\":null,\"in_reply_to_id\":\"100\"");
		String nested = status("102", "b@r.social").replace("\"reblog\":null", "\"reblog\":null,\"in_reply_to_id\":\"101\"");
		expectGet("/api/v1/statuses/100/context", "{\"ancestors\":[],\"descendants\":[" + reply + "," + nested + "]}");
		expectGet("/api/v1/statuses/100/favourited_by?limit=10", "[{\"id\":\"1\",\"acct\":\"fan@r.social\"}]");
		expectGet("/api/v1/statuses/100/reblogged_by?limit=10", "[]");
		PostInteractions result = service.getPostInteractions("100", 10);
		server.verify();
		assertThat(result.post().likeCount()).isEqualTo(3);
		assertThat(result.replies()).extracting(PostResult::id).containsExactly("101");
		assertThat(result.likedBy()).singleElement().satisfies(a -> assertThat(a.handle()).isEqualTo("@fan@r.social"));
		assertThat(result.repostedBy()).isEmpty();
	}

	@Test
	void interactionsByUrlResolveThroughSearch() {
		String url = "https://other.social/@x/555";
		expectGet("/api/v2/search?q=" + enc(url) + "&type=statuses&resolve=true&limit=1",
				"{\"statuses\":[{\"id\":\"900\"}]}");
		expectGet("/api/v1/statuses/900", status("900", "x@other.social"));
		expectGet("/api/v1/statuses/900/context", "{\"descendants\":[]}");
		expectGet("/api/v1/statuses/900/favourited_by?limit=10", "[]");
		expectGet("/api/v1/statuses/900/reblogged_by?limit=10", "[]");
		assertThat(service.getPostInteractions(url, 10).post().id()).isEqualTo("900");
		server.verify();
	}

	@Test
	void invalidReferenceMakesNoCallAndMissingPostIsNotFound() {
		assertThatThrownBy(() -> service.getPostInteractions("bsky.app/profile/a.b/post/x", 10))
			.hasMessage("Invalid mastodon post reference 'bsky.app/profile/a.b/post/x'");
		server.expect(requestTo(BASE + "/api/v1/statuses/404")).andRespond(withResourceNotFound());
		assertThatThrownBy(() -> service.getPostInteractions("404", 10))
			.hasMessage("Post '404' not found on mastodon");
		server.verify();
	}

	// --- Trends ---

	@Test
	void trendsSumHistoryAndNameTheInstance() {
		expectInstance(500);
		expectGet("/api/v1/trends/tags?limit=20", """
				[{"name":"java","url":"https://mastodon.test/tags/java",
				  "history":[{"day":"1","uses":"10","accounts":"3"},{"day":"2","uses":"5","accounts":"2"}]}]""");
		expectGet("/api/v1/trends/statuses?limit=30", "[" + status("1", "a@r.social") + "]");
		TrendsResult trends = service.getTrends(30);
		server.verify();
		assertThat(trends.tags()).singleElement().satisfies(t -> {
			assertThat(t.name()).isEqualTo("#java");
			assertThat(t.recentUses()).isEqualTo(15);
		});
		assertThat(trends.posts()).hasSize(1);
		assertThat(trends.notes()).isEqualTo("Trends reflect the mastodon.test instance only.");
	}

	@Test
	void emptyTrendsMentionTheyMayBeDisabled() {
		expectInstance(500);
		expectGet("/api/v1/trends/tags?limit=10", "[]");
		expectGet("/api/v1/trends/statuses?limit=10", "[]");
		assertThat(service.getTrends(10).notes()).endsWith(" This instance may have trends disabled.");
	}

	// --- Similar accounts ---

	@Test
	void similarAccountsUseTopThreeTagsAndRankAuthors() {
		expectGet("/api/v1/accounts/lookup?acct=" + enc("t@r.social"), "{\"id\":\"T\",\"acct\":\"t@r.social\"}");
		String tagged = """
				[{"id":"1","tags":[{"name":"Rust"},{"name":"go"}]},
				 {"id":"2","tags":[{"name":"rust"},{"name":"zig"},{"name":"c"}]},
				 {"id":"3","tags":[{"name":"go"}]}]""";
		expectGet("/api/v1/accounts/T/statuses?limit=40&exclude_replies=true&exclude_reblogs=true", tagged);
		expectGet("/api/v1/accounts/verify_credentials", "{\"id\":\"ME\",\"acct\":\"me\"}");
		// rust 2, go 2, then c/zig tie at 1 -> "c" wins alphabetically
		String a = "{\"id\":\"s\",\"account\":{\"id\":\"A\",\"acct\":\"a@r.social\"}}";
		String b = "{\"id\":\"s\",\"account\":{\"id\":\"B\",\"acct\":\"b@r.social\"}}";
		String target = "{\"id\":\"s\",\"account\":{\"id\":\"T\",\"acct\":\"t@r.social\"}}";
		String me = "{\"id\":\"s\",\"account\":{\"id\":\"ME\",\"acct\":\"me\"}}";
		expectGet("/api/v1/timelines/tag/go?limit=40", "[" + a + "," + target + "," + me + "]");
		expectGet("/api/v1/timelines/tag/rust?limit=40", "[" + b + "," + b + "," + b + "," + a + "]");
		expectGet("/api/v1/timelines/tag/c?limit=40", "[" + b + "]");
		SimilarAccountsResult result = service.findSimilarAccounts("t@r.social", 10);
		server.verify();
		assertThat(result.method()).isEqualTo("hashtag-overlap");
		assertThat(result.basedOn()).containsExactly("#go", "#rust", "#c");
		// B: 2 tags (rust, c), 4 posts; A: 2 tags (go, rust), 2 posts
		assertThat(result.accounts()).extracting(acc -> acc.handle()).containsExactly("@b@r.social", "@a@r.social");
	}

	@Test
	void userWithoutHashtagsGetsNoSuggestionsAndNoTagCalls() {
		expectGet("/api/v1/accounts/lookup?acct=" + enc("t@r.social"), "{\"id\":\"T\",\"acct\":\"t@r.social\"}");
		expectGet("/api/v1/accounts/T/statuses?limit=40&exclude_replies=true&exclude_reblogs=true",
				"[{\"id\":\"1\",\"tags\":[]}]");
		SimilarAccountsResult result = service.findSimilarAccounts("t@r.social", 10);
		server.verify();
		assertThat(result.accounts()).isEmpty();
		assertThat(result.basedOn()).isEmpty();
	}

	// --- Posting rules and length ---

	@Test
	void instanceLimitIsReadOnceAndRaisesTheLimit() {
		expectInstance(1000);
		PostingRules rules = service.postingRules();
		assertThat(rules.maxLength()).isEqualTo(1000);
		assertThat(rules.source()).isEqualTo("instance");
		assertThat(rules.unit()).isEqualTo("graphemes");
		assertThat(rules.followUpVisibility()).isEqualTo("unlisted");
		assertThat(rules.numberingReserve()).isEqualTo(8);
		assertThat(service.checkPart(1, "a".repeat(900)).ok()).isTrue();
		server.verify();
	}

	@Test
	void instanceFailureFallsBackAndRetriesAfterBackoff() {
		server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v2/instance")).andRespond(withServerError());
		assertThat(service.postingRules().source()).isEqualTo("fallback");
		assertThat(service.postingRules().maxLength()).isEqualTo(500); // within backoff: no new request
		server.verify();

		server.reset();
		expectInstance(700);
		clock.advance(Duration.ofMinutes(10));
		assertThat(service.postingRules().maxLength()).isEqualTo(700);
		server.verify();
	}

	@Test
	void lengthFollowsMastodonRules() {
		expectInstance(500);
		PartCheck over = service.checkPart(1, "a".repeat(501));
		assertThat(over.ok()).isFalse();
		assertThat(over.reason()).isEqualTo("501/500 graphemes (1 over)");
		assertThat(service.checkPart(1, FAMILY.repeat(500)).ok()).isTrue();
		assertThat(service.checkPart(1, "a".repeat(476) + " https://example.com/" + "p".repeat(80)).ok()).isTrue();
		assertThat(service.checkPart(1, "@bob@example.social").length()).isEqualTo(4);
		assertThat(service.checkPart(1, "  ").reason()).isEqualTo("blank");
	}

	// --- Posting ---

	@Test
	void threadPartsReplyToParentWithConfiguredVisibility() {
		Capture capture = new Capture();
		server.expect(ExpectedCount.twice(), requestTo(BASE + "/api/v1/statuses"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(capture)
			.andRespond(withSuccess("{\"id\":\"1\",\"url\":\"https://mastodon.test/@me/1\"}", MediaType.APPLICATION_JSON));
		PublishedPost first = service.createPost("part one", null, null);
		service.createPost("part two", first, first);
		server.verify();
		assertThat(first.url()).isEqualTo("https://mastodon.test/@me/1");
		assertThat(first.cid()).isNull();
		var one = capture.requests.get(0);
		var two = capture.requests.get(1);
		assertThat(one.body().get("visibility").stringValue()).isEqualTo("public");
		assertThat(one.body().has("in_reply_to_id")).isFalse();
		assertThat(two.body().get("visibility").stringValue()).isEqualTo("unlisted");
		assertThat(two.body().get("in_reply_to_id").stringValue()).isEqualTo("1");
		assertThat(one.headers().getFirst("Idempotency-Key")).isNotEqualTo(two.headers().getFirst("Idempotency-Key"));
	}

	@Test
	void publicThreadVisibilityIsHonoured() {
		service = service("public");
		Capture capture = new Capture();
		server.expect(requestTo(BASE + "/api/v1/statuses"))
			.andExpect(capture)
			.andRespond(withSuccess("{\"id\":\"2\",\"url\":\"u\"}", MediaType.APPLICATION_JSON));
		service.createPost("reply", new PublishedPost("1", null, "u"), new PublishedPost("1", null, "u"));
		assertThat(capture.requests.get(0).body().get("visibility").stringValue()).isEqualTo("public");
	}

	@Test
	void serverErrorIsRetriedOnceWithTheSameIdempotencyKey() {
		Capture capture = new Capture();
		server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v1/statuses")).andExpect(capture).andRespond(withServerError());
		server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v1/statuses"))
			.andExpect(capture)
			.andRespond(withSuccess("{\"id\":\"3\",\"url\":\"u3\"}", MediaType.APPLICATION_JSON));
		assertThat(service.createPost("hello", null, null).id()).isEqualTo("3");
		server.verify();
		assertThat(capture.requests).hasSize(2);
		assertThat(capture.requests.get(0).headers().getFirst("Idempotency-Key"))
			.isEqualTo(capture.requests.get(1).headers().getFirst("Idempotency-Key"));
	}

	@Test
	void persistentServerErrorPropagatesAfterOneRetry() {
		server.expect(ExpectedCount.twice(), requestTo(BASE + "/api/v1/statuses"))
			.andRespond(withServerError());
		assertThatThrownBy(() -> service.createPost("hello", null, null))
			.isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
		server.verify();
	}

	@Test
	void handleValidation() {
		assertThat(service.isValidHandle("alice")).isTrue();
		assertThat(service.isValidHandle("alice@mastodon.social")).isTrue();
		assertThat(service.isValidHandle("alice.bsky.social")).isFalse();
	}

}
