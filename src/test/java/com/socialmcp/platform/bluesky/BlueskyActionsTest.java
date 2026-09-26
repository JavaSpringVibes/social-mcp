package com.socialmcp.platform.bluesky;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.config.TestProperties;
import com.socialmcp.model.AccountAction;
import com.socialmcp.model.PollInput;
import com.socialmcp.model.PostAction;
import com.socialmcp.model.PostActionResult;
import com.socialmcp.model.PostResult;
import com.socialmcp.model.QuoteSummary;
import com.socialmcp.model.QuoteTarget;
import com.socialmcp.model.RelationshipResult;
import com.socialmcp.model.ReplyTarget;
import com.socialmcp.platform.TestSupport;
import com.socialmcp.platform.TestSupport.Capture;
import com.socialmcp.platform.TestSupport.MutableClock;

import static com.socialmcp.platform.TestSupport.enc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Relationships, post actions, bookmarks, replies and quotes on Bluesky (SPEC §5). */
class BlueskyActionsTest {

	private static final String XRPC = "https://pds.test/xrpc";

	private static final String ME = "did:plc:me";

	private static final String BOB = "did:plc:bob";

	private static final String POST_URI = "at://" + BOB + "/app.bsky.feed.post/3p";

	private MockRestServiceServer server;

	private BlueskyService service;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
		SocialProperties properties = TestProperties
			.with(TestProperties.bluesky("https://pds.test", "me.bsky.social", "app-pass"));
		service = new BlueskyService(properties, builder, new MutableClock());
		server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.server.createSession"))
			.andRespond(withSuccess("""
					{"accessJwt":"jwt1","refreshJwt":"refresh-jwt1","did":"%s","handle":"me.bsky.social"}"""
				.formatted(ME), MediaType.APPLICATION_JSON));
	}

	// --- Fixtures ---

	/** Parses expected JSON, so bodies compare structurally (key order doesn't matter to the API). */
	private static JsonNode json(String text) {
		return TestSupport.JSON.readTree(text);
	}

	private void expectGet(String pathAndQuery, String json) {
		server.expect(ExpectedCount.once(), requestTo(XRPC + pathAndQuery))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
	}

	private Capture expectPost(String path, String json) {
		Capture capture = new Capture();
		server.expect(ExpectedCount.once(), requestTo(XRPC + path))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header("Authorization", "Bearer jwt1"))
			.andExpect(capture)
			.andRespond(json.isEmpty() ? withSuccess() : withSuccess(json, MediaType.APPLICATION_JSON));
		return capture;
	}

	private Capture expectCreate() {
		return expectPost("/com.atproto.repo.createRecord", "{\"uri\":\"at://" + ME + "/x/3new\",\"cid\":\"cnew\"}");
	}

	private Capture expectDelete() {
		return expectPost("/com.atproto.repo.deleteRecord", "{}");
	}

	private void expectBob(String viewer) {
		expectGet("/app.bsky.actor.getProfile?actor=bob.bsky.social", """
				{"did":"%s","handle":"bob.bsky.social","displayName":"Bob","viewer":{%s}}""".formatted(BOB, viewer));
	}

	private void expectPostView(String viewer, String extraFields) {
		expectGet("/app.bsky.feed.getPosts?uris=" + enc(POST_URI), "{\"posts\":[" + postView(viewer, extraFields) + "]}");
	}

	private static String postView(String viewer, String extraFields) {
		return """
				{"$type":"app.bsky.feed.defs#postView","uri":"%s","cid":"cp",
				 "author":{"did":"%s","handle":"bob.bsky.social"},
				 "record":{"text":"hello","createdAt":"2026-09-25T10:00:00.000Z"%s},
				 "replyCount":0,"repostCount":2,"likeCount":0,"viewer":{%s}}"""
			.formatted(POST_URI, BOB, extraFields, viewer);
	}

	// --- Relationships ---

	@Test
	void followCreatesARecordForTheDid() {
		expectBob("");
		Capture create = expectCreate();
		RelationshipResult result = service.setRelationship("bob.bsky.social", AccountAction.FOLLOW);
		server.verify();
		assertThat(result.status()).isEqualTo("following");
		assertThat(result.account().handle()).isEqualTo("@bob.bsky.social");
		assertThat(create.requests.get(0).body()).isEqualTo(json("""
				{"repo":"did:plc:me","collection":"app.bsky.graph.follow","record":{"$type":"app.bsky.graph.follow",\
				"subject":"did:plc:bob","createdAt":"2026-09-25T12:00:00Z"}}"""));
	}

	@Test
	void existingFollowIsNotRepeated() {
		expectBob("\"following\":\"at://" + ME + "/app.bsky.graph.follow/3kabc\"");
		assertThat(service.setRelationship("bob.bsky.social", AccountAction.FOLLOW).status())
			.isEqualTo("already-following");
		server.verify();
	}

	@Test
	void followIsRefusedForAnyKindOfBlock() {
		for (String viewer : List.of("\"blockedBy\":true", "\"blocking\":\"at://" + ME + "/app.bsky.graph.block/1\"",
				"\"blockingByList\":{\"name\":\"Spam\"}")) {
			setUp();
			expectBob(viewer);
			assertThatThrownBy(() -> service.setRelationship("bob.bsky.social", AccountAction.FOLLOW))
				.hasMessage("Can't follow 'bob.bsky.social' on bluesky because of a block");
			server.verify();
		}
	}

	@Test
	void ownAccountIsRefused() {
		expectGet("/app.bsky.actor.getProfile?actor=me.bsky.social", "{\"did\":\"" + ME + "\",\"handle\":\"me.bsky.social\"}");
		assertThatThrownBy(() -> service.setRelationship("me.bsky.social", AccountAction.MUTE))
			.hasMessage("You can't mute your own account");
		server.verify();
	}

	@Test
	void unfollowDeletesTheReportedRecord() {
		expectBob("\"following\":\"at://" + ME + "/app.bsky.graph.follow/3kabc\"");
		Capture delete = expectDelete();
		assertThat(service.setRelationship("bob.bsky.social", AccountAction.UNFOLLOW).status()).isEqualTo("unfollowed");
		assertThat(delete.requests.get(0).body())
			.isEqualTo(json("{\"repo\":\"did:plc:me\",\"collection\":\"app.bsky.graph.follow\",\"rkey\":\"3kabc\"}"));
	}

	@Test
	void unfollowWithoutAFollowOrWithAForeignRecordMakesNoWrite() {
		expectBob("");
		assertThat(service.setRelationship("bob.bsky.social", AccountAction.UNFOLLOW).status())
			.isEqualTo("not-following");
		server.verify();

		setUp();
		expectBob("\"following\":\"at://did:plc:other/app.bsky.graph.follow/3kabc\"");
		assertThatThrownBy(() -> service.setRelationship("bob.bsky.social", AccountAction.UNFOLLOW)).hasMessage(
				"Unexpected follow record 'at://did:plc:other/app.bsky.graph.follow/3kabc' on bluesky");
		server.verify();
	}

	@Test
	void blockIsMadeDespiteAListBlockOrBeingBlocked() {
		expectBob("\"blockedBy\":true,\"blockingByList\":{\"name\":\"Spam\"}");
		Capture create = expectCreate();
		assertThat(service.setRelationship("bob.bsky.social", AccountAction.BLOCK).status()).isEqualTo("blocked");
		assertThat(create.requests.get(0).body().path("collection").asString()).isEqualTo("app.bsky.graph.block");
	}

	@Test
	void unblockHandlesListBlocks() {
		expectBob("\"blockingByList\":{\"name\":\"Spam\"}");
		assertThatThrownBy(() -> service.setRelationship("bob.bsky.social", AccountAction.UNBLOCK)).hasMessage(
				"'bob.bsky.social' is blocked through the moderation list 'Spam' on bluesky. Remove them from the list "
						+ "or unsubscribe from it in the Bluesky app.");
		server.verify();

		setUp();
		expectBob("\"blocking\":\"at://" + ME + "/app.bsky.graph.block/3kxyz\",\"blockingByList\":{\"name\":\"Spam\"}");
		Capture delete = expectDelete();
		RelationshipResult result = service.setRelationship("bob.bsky.social", AccountAction.UNBLOCK);
		assertThat(result.status()).isEqualTo("unblocked");
		assertThat(result.note()).isEqualTo("Still blocked through the moderation list 'Spam'.");
		assertThat(delete.requests.get(0).body().path("rkey").asString()).isEqualTo("3kxyz");
	}

	@Test
	void muteSkipsAFullDirectMuteButWidensPartialOnes() {
		expectBob("\"muted\":true");
		assertThat(service.setRelationship("bob.bsky.social", AccountAction.MUTE).status()).isEqualTo("already-muted");
		server.verify();

		for (String viewer : List.of("\"muted\":true,\"mutedOnlyReposts\":true",
				"\"muted\":true,\"mutedByList\":{\"name\":\"Noise\"}", "\"muted\":false")) {
			setUp();
			expectBob(viewer);
			Capture mute = expectPost("/app.bsky.graph.muteActor", "");
			assertThat(service.setRelationship("bob.bsky.social", AccountAction.MUTE).status()).isEqualTo("muted");
			assertThat(mute.requests.get(0).body()).isEqualTo(json("{\"actor\":\"did:plc:bob\"}"));
		}
	}

	@Test
	void unmuteNotesARemainingListMute() {
		expectBob("\"muted\":true,\"mutedByList\":{\"name\":\"Noise\"}");
		expectPost("/app.bsky.graph.unmuteActor", "");
		RelationshipResult result = service.setRelationship("bob.bsky.social", AccountAction.UNMUTE);
		assertThat(result.status()).isEqualTo("unmuted");
		assertThat(result.note()).isEqualTo("Any direct mute was removed, but 'bob.bsky.social' is still muted through "
				+ "the mute list 'Noise'. Remove them from the list or unsubscribe from it in the Bluesky app.");

		setUp();
		expectBob("\"muted\":false");
		assertThat(service.setRelationship("bob.bsky.social", AccountAction.UNMUTE).status()).isEqualTo("not-muted");
		server.verify();
	}

	// --- Post actions ---

	@Test
	void likeUsesAStrongRefAndBumpsTheCount() {
		expectPostView("", "");
		Capture create = expectCreate();
		PostActionResult result = service.setPostAction(POST_URI, PostAction.LIKE);
		assertThat(result.status()).isEqualTo("liked");
		assertThat(result.post().likeCount()).isEqualTo(1);
		assertThat(create.requests.get(0).body().path("record")).isEqualTo(json("""
				{"$type":"app.bsky.feed.like","subject":{"uri":"%s","cid":"cp"},"createdAt":"2026-09-25T12:00:00Z"}"""
			.formatted(POST_URI)));
	}

	@Test
	void alreadyLikedAndUnlikedFloorAtZero() {
		expectPostView("\"like\":\"at://" + ME + "/app.bsky.feed.like/3klik\"", "");
		assertThat(service.setPostAction(POST_URI, PostAction.LIKE).status()).isEqualTo("already-liked");

		setUp();
		expectPostView("\"like\":\"at://" + ME + "/app.bsky.feed.like/3klik\"", "");
		Capture delete = expectDelete();
		PostActionResult result = service.setPostAction(POST_URI, PostAction.UNLIKE);
		assertThat(result.status()).isEqualTo("unliked");
		assertThat(result.post().likeCount()).isZero();
		assertThat(delete.requests.get(0).body().path("rkey").asString()).isEqualTo("3klik");
	}

	@Test
	void repostAndUnrepostAdjustTheRepostCount() {
		expectPostView("", "");
		expectCreate();
		PostActionResult reposted = service.setPostAction(POST_URI, PostAction.REPOST);
		assertThat(reposted.status()).isEqualTo("reposted");
		assertThat(reposted.post().repostCount()).isEqualTo(3);

		setUp();
		expectPostView("\"repost\":\"at://" + ME + "/app.bsky.feed.repost/3krp\"", "");
		expectDelete();
		PostActionResult unreposted = service.setPostAction(POST_URI, PostAction.UNREPOST);
		assertThat(unreposted.status()).isEqualTo("unreposted");
		assertThat(unreposted.post().repostCount()).isEqualTo(1);
	}

	@Test
	void bookmarksUseProceduresNotRecords() {
		expectPostView("\"bookmarked\":false", "");
		Capture create = expectPost("/app.bsky.bookmark.createBookmark", "");
		assertThat(service.setPostAction(POST_URI, PostAction.BOOKMARK).status()).isEqualTo("bookmarked");
		assertThat(create.requests.get(0).body()).isEqualTo(json("{\"uri\":\"" + POST_URI + "\",\"cid\":\"cp\"}"));

		setUp();
		expectPostView("\"bookmarked\":true", "");
		Capture delete = expectPost("/app.bsky.bookmark.deleteBookmark", "");
		assertThat(service.setPostAction(POST_URI, PostAction.UNBOOKMARK).status()).isEqualTo("unbookmarked");
		assertThat(delete.requests.get(0).body()).isEqualTo(json("{\"uri\":\"" + POST_URI + "\"}"));
	}

	@Test
	void missingPostIsNotFound() {
		expectGet("/app.bsky.feed.getPosts?uris=" + enc(POST_URI), "{\"posts\":[]}");
		assertThatThrownBy(() -> service.setPostAction(POST_URI, PostAction.LIKE))
			.hasMessage("Post '" + POST_URI + "' not found on bluesky");
	}

	@Test
	void expiredTokenOnAWriteRefreshesAndRetriesOnce() {
		expectPostView("", "");
		server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.repo.createRecord"))
			.andExpect(header("Authorization", "Bearer jwt1"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":\"ExpiredToken\",\"message\":\"Token has expired\"}"));
		server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.server.refreshSession"))
			.andRespond(withSuccess("""
					{"accessJwt":"jwt2","refreshJwt":"refresh-jwt2","did":"%s","handle":"me.bsky.social"}"""
				.formatted(ME), MediaType.APPLICATION_JSON));
		server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.repo.createRecord"))
			.andExpect(header("Authorization", "Bearer jwt2"))
			.andRespond(withSuccess("{\"uri\":\"at://" + ME + "/x/1\",\"cid\":\"c\"}", MediaType.APPLICATION_JSON));
		assertThat(service.setPostAction(POST_URI, PostAction.LIKE).status()).isEqualTo("liked");
		server.verify();
	}

	@Test
	void bookmarksSkipDeletedAndBlockedItems() {
		expectGet("/app.bsky.bookmark.getBookmarks?limit=100", """
				{"bookmarks":[{"subject":{},"item":%s},
				 {"subject":{},"item":{"$type":"app.bsky.feed.defs#notFoundPost","uri":"x","notFound":true}},
				 {"subject":{},"item":{"$type":"app.bsky.feed.defs#blockedPost","uri":"y","blocked":true}}]}"""
			.formatted(postView("", "")));
		List<PostResult> bookmarks = service.getBookmarks(500);
		server.verify();
		assertThat(bookmarks).extracting(PostResult::id).containsExactly(POST_URI);
	}

	// --- Replies and quotes ---

	@Test
	void replyUsesTheParentsRootDeepInAThread() {
		expectPostView("", ",\"reply\":{\"root\":{\"uri\":\"at://did:plc:r/app.bsky.feed.post/root\",\"cid\":\"croot\"},"
				+ "\"parent\":{\"uri\":\"u\",\"cid\":\"c\"}}");
		Capture create = expectCreate();
		ReplyTarget target = service.replyTarget(POST_URI);
		assertThat(target.mention()).isNull();
		service.reply(target, "thanks", List.of());
		assertThat(create.requests.get(0).body().path("record").path("reply")).isEqualTo(json("""
				{"root":{"uri":"at://did:plc:r/app.bsky.feed.post/root","cid":"croot"},"parent":{"uri":"%s","cid":"cp"}}"""
			.formatted(POST_URI)));
	}

	@Test
	void replyToTopLevelPostUsesItAsRootAndParent() {
		expectPostView("", "");
		ReplyTarget target = service.replyTarget(POST_URI);
		assertThat(target.root()).isEqualTo(target.parent());
	}

	@Test
	void restrictedRepliesAreRefused() {
		expectPostView("\"replyDisabled\":true", "");
		assertThatThrownBy(() -> service.replyTarget(POST_URI))
			.hasMessage("The author of this post on bluesky has restricted who can reply");
	}

	@Test
	void quoteEmbedsAStrongRef() {
		expectPostView("", "");
		Capture create = expectPost("/com.atproto.repo.createRecord",
				"{\"uri\":\"at://" + ME + "/app.bsky.feed.post/3q\",\"cid\":\"cq\"}");
		QuoteTarget target = service.quoteTarget(POST_URI);
		assertThat(service.createTopLevelPost("my take", target, null, List.of()).caveat()).isNull();
		assertThat(create.requests.get(0).body().path("record").path("embed")).isEqualTo(json("""
				{"$type":"app.bsky.embed.record","record":{"uri":"%s","cid":"cp"}}""".formatted(POST_URI)));
	}

	@Test
	void disabledQuotingIsRefused() {
		expectPostView("\"embeddingDisabled\":true", "");
		assertThatThrownBy(() -> service.quoteTarget(POST_URI))
			.hasMessage("You can't quote this post on bluesky (the author has disabled quoting)");
	}

	@Test
	void pollsAreNotSupported() {
		server.reset();
		assertThatThrownBy(() -> service.createTopLevelPost("q", null, new PollInput(List.of("a", "b"), null, null, null), List.of()))
			.hasMessage("Bluesky doesn't support polls");
		assertThatThrownBy(() -> service.vote(POST_URI, List.of(1))).hasMessage("Bluesky doesn't support polls");
		assertThat(service.supportsPolls()).isFalse();
		server.verify();
	}

	// --- Quote mapping ---

	private static QuoteSummary quoteOf(String embed) {
		return BlueskyService.toQuote(TestSupport.JSON.readTree(embed));
	}

	@Test
	void mapsRecordEmbedsToQuotes() {
		String viewRecord = """
				{"$type":"app.bsky.embed.record#viewRecord","uri":"at://did:plc:q/app.bsky.feed.post/3q",
				 "author":{"handle":"q.bsky.social"},"value":{"$type":"app.bsky.feed.post","text":"quoted"}}""";
		QuoteSummary accepted = quoteOf("{\"$type\":\"app.bsky.embed.record#view\",\"record\":" + viewRecord + "}");
		assertThat(accepted).isEqualTo(new QuoteSummary("accepted", "at://did:plc:q/app.bsky.feed.post/3q",
				"@q.bsky.social", "quoted", "https://bsky.app/profile/q.bsky.social/post/3q"));
		assertThat(quoteOf("{\"$type\":\"app.bsky.embed.recordWithMedia#view\",\"record\":{\"record\":" + viewRecord
				+ "},\"media\":{}}")).isEqualTo(accepted);
		assertThat(quoteOf("{\"$type\":\"app.bsky.embed.record#view\",\"record\":{\"$type\":\"app.bsky.embed.record#viewNotFound\"}}")
			.state()).isEqualTo("deleted");
		assertThat(quoteOf("{\"$type\":\"app.bsky.embed.record#view\",\"record\":{\"$type\":\"app.bsky.embed.record#viewBlocked\"}}")
			.state()).isEqualTo("blocked");
		assertThat(quoteOf("{\"$type\":\"app.bsky.embed.record#view\",\"record\":{\"$type\":\"app.bsky.embed.record#viewDetached\"}}")
			.state()).isEqualTo("detached");
		assertThat(quoteOf("{\"$type\":\"app.bsky.embed.record#view\",\"record\":{\"$type\":\"app.bsky.feed.defs#generatorView\"}}"))
			.isNull();
		assertThat(quoteOf("{\"$type\":\"app.bsky.embed.images#view\"}")).isNull();
	}

}
