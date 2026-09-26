package com.socialmcp.platform.bluesky;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.config.TestProperties;
import com.socialmcp.model.*;
import com.socialmcp.platform.TestSupport.Capture;
import com.socialmcp.platform.TestSupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.socialmcp.platform.TestSupport.enc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BlueskyServiceTest {

    private static final String XRPC = "https://pds.test/xrpc";

    private static final String ME = "did:plc:me";

    private static final String FAMILY = "👨‍👩‍👧‍👦";

    private MockRestServiceServer server;

    private BlueskyService service;

    private static String session(String accessJwt) {
        return """
                {"accessJwt":"%s","refreshJwt":"refresh-%s","did":"%s","handle":"me.bsky.social"}"""
                .formatted(accessJwt, accessJwt, ME);
    }

    private static String post(String rkey, String author, int likes) {
        return """
                {"uri":"at://did:plc:%s/app.bsky.feed.post/%s","cid":"c%s",
                 "author":{"did":"did:plc:%s","handle":"%s"},
                 "record":{"text":"text %s","createdAt":"2026-09-25T10:00:00.000Z"},
                 "replyCount":1,"repostCount":2,"likeCount":%d}"""
                .formatted(author, rkey, rkey, author, author, rkey, likes);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        SocialProperties properties = TestProperties
                .with(TestProperties.bluesky("https://pds.test", "me.bsky.social", "app-pass"));
        service = new BlueskyService(properties, builder, new MutableClock());
    }

    private void expectLogin(String accessJwt) {
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.server.createSession"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(session(accessJwt), MediaType.APPLICATION_JSON));
    }

    private void expectGet(String pathAndQuery, String json) {
        expectGet(ExpectedCount.once(), pathAndQuery, "jwt1", json);
    }

    private void expectGet(ExpectedCount count, String pathAndQuery, String jwt, String json) {
        server.expect(count, requestTo(XRPC + pathAndQuery))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + jwt))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void expectError(String pathAndQuery, HttpStatus status, String error, String message) {
        server.expect(requestTo(XRPC + pathAndQuery))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}"));
    }

    // --- Session ---

    @Test
    void logsInOnceAcrossCalls() {
        expectLogin("jwt1");
        expectGet(ExpectedCount.twice(), "/app.bsky.feed.getTimeline?limit=10", "jwt1", "{\"feed\":[]}");
        service.getTimeline(TimelineType.HOME, 10);
        service.getTimeline(TimelineType.HOME, 10);
        server.verify();
    }

    @Test
    void expiredTokenRefreshesAndRetriesOnce() {
        expectLogin("jwt1");
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/app.bsky.feed.getTimeline?limit=10"))
                .andExpect(header("Authorization", "Bearer jwt1"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"ExpiredToken\",\"message\":\"Token has expired\"}"));
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.server.refreshSession"))
                .andExpect(header("Authorization", "Bearer refresh-jwt1"))
                .andRespond(withSuccess(session("jwt2"), MediaType.APPLICATION_JSON));
        expectGet(ExpectedCount.once(), "/app.bsky.feed.getTimeline?limit=10", "jwt2", "{\"feed\":[]}");
        assertThat(service.getTimeline(TimelineType.HOME, 10)).isEmpty();
        server.verify();
    }

    @Test
    void loginErrorsHaveSpecificMessagesAndNoRetry() {
        expectError("/com.atproto.server.createSession", HttpStatus.UNAUTHORIZED, "AuthenticationRequired",
                "Invalid identifier or password");
        assertThatThrownBy(() -> service.getTimeline(TimelineType.HOME, 10))
                .hasMessage("Bluesky login failed: check BLUESKY_HANDLE and BLUESKY_APP_PASSWORD");
        server.verify();

        server.reset();
        expectError("/com.atproto.server.createSession", HttpStatus.UNAUTHORIZED, "AuthFactorTokenRequired", "2FA");
        assertThatThrownBy(() -> service.getTimeline(TimelineType.HOME, 10)).hasMessageContaining("use an app password");

        server.reset();
        expectError("/com.atproto.server.createSession", HttpStatus.BAD_REQUEST, "AccountTakedown", "gone");
        assertThatThrownBy(() -> service.getTimeline(TimelineType.HOME, 10))
                .hasMessage("Bluesky account is taken down");
    }

    // --- Search and feeds ---

    @Test
    void searchPassesSortAndMapsCounts() {
        expectLogin("jwt1");
        expectGet("/app.bsky.feed.searchPosts?q=" + enc("rust lang") + "&limit=100&sort=top",
                "{\"posts\":[" + post("abc", "alice.bsky.social", 9) + "]}");
        PostResult result = service.searchPosts("rust lang", SearchSort.TOP, 100).get(0);
        server.verify();
        assertThat(result.id()).isEqualTo("at://did:plc:alice.bsky.social/app.bsky.feed.post/abc");
        assertThat(result.author()).isEqualTo("@alice.bsky.social");
        assertThat(result.url()).isEqualTo("https://bsky.app/profile/alice.bsky.social/post/abc");
        assertThat(result.createdAt()).isEqualTo("2026-09-25T10:00:00Z");
        assertThat(result.likeCount()).isEqualTo(9);
        assertThat(result.repostCount()).isEqualTo(2);
        assertThat(result.replyCount()).isEqualTo(1);
    }

    @Test
    void homeKeepsRepostsAsOriginalPosts() {
        expectLogin("jwt1");
        expectGet("/app.bsky.feed.getTimeline?limit=10", "{\"feed\":[{\"post\":" + post("r", "carol.test", 1)
                + ",\"reason\":{\"$type\":\"app.bsky.feed.defs#reasonRepost\"}}]}");
        assertThat(service.getTimeline(TimelineType.HOME, 10)).extracting(PostResult::author)
                .containsExactly("@carol.test");
    }

    @Test
    void ownFeedSkipsReposts() {
        expectLogin("jwt1");
        String feed = "{\"feed\":[{\"post\":" + post("r", "carol.test", 1)
                + ",\"reason\":{\"$type\":\"app.bsky.feed.defs#reasonRepost\"}},{\"post\":" + post("a", "me.bsky.social", 0)
                + "},{\"post\":" + post("b", "me.bsky.social", 0) + "}]}";
        expectGet("/app.bsky.feed.getAuthorFeed?actor=" + enc(ME) + "&limit=3&filter=posts_no_replies", feed);
        assertThat(service.getTimeline(TimelineType.OWN, 3)).extracting(PostResult::url)
                .containsExactly("https://bsky.app/profile/me.bsky.social/post/a",
                        "https://bsky.app/profile/me.bsky.social/post/b");
        server.verify();
    }

    @Test
    void userPostsMapNotFoundAndBlocks() {
        expectLogin("jwt1");
        String query = "&limit=10&filter=posts_no_replies";
        expectError("/app.bsky.feed.getAuthorFeed?actor=ghost.test" + query, HttpStatus.BAD_REQUEST, "InvalidRequest",
                "Profile not found");
        expectError("/app.bsky.feed.getAuthorFeed?actor=rude.test" + query, HttpStatus.BAD_REQUEST, "BlockedByActor",
                "blocked");
        expectError("/app.bsky.feed.getAuthorFeed?actor=muted.test" + query, HttpStatus.BAD_REQUEST, "BlockedActor",
                "blocked");
        assertThatThrownBy(() -> service.getUserPosts("ghost.test", 10))
                .hasMessage("User 'ghost.test' not found on bluesky");
        assertThatThrownBy(() -> service.getUserPosts("rude.test", 10))
                .hasMessage("Posts by 'rude.test' on bluesky are unavailable because of a block");
        assertThatThrownBy(() -> service.getUserPosts("muted.test", 10)).hasMessageContaining("because of a block");
        server.verify();
    }

    // --- Profile ---

    @Test
    void ownProfileUsesSessionDid() {
        expectLogin("jwt1");
        expectGet("/app.bsky.actor.getProfile?actor=" + enc(ME), """
                {"did":"did:plc:me","handle":"me.bsky.social","displayName":"","followersCount":5,
                 "followsCount":6,"postsCount":7,"createdAt":"2023-04-01T12:00:00.000Z"}""");
        ProfileResult profile = service.getProfile(null);
        server.verify();
        assertThat(profile.id()).isEqualTo(ME);
        assertThat(profile.handle()).isEqualTo("@me.bsky.social");
        assertThat(profile.displayName()).isEqualTo("me.bsky.social");
        assertThat(profile.bio()).isEmpty();
        assertThat(profile.followersCount()).isEqualTo(5);
        assertThat(profile.followingCount()).isEqualTo(6);
        assertThat(profile.postsCount()).isEqualTo(7);
        assertThat(profile.isPrivate()).isFalse();
        assertThat(profile.url()).isEqualTo("https://bsky.app/profile/me.bsky.social");
    }

    // --- Post interactions ---

    @Test
    void interactionsFromWebUrlResolveHandleAndSkipBlockedReplies() {
        expectLogin("jwt1");
        expectGet("/com.atproto.identity.resolveHandle?handle=alice.test", "{\"did\":\"did:plc:alice\"}");
        String uri = "at://did:plc:alice/app.bsky.feed.post/xyz";
        expectGet("/app.bsky.feed.getPostThread?uri=" + enc(uri) + "&depth=1&parentHeight=0", """
                {"thread":{"$type":"app.bsky.feed.defs#threadViewPost","post":%s,
                 "replies":[{"$type":"app.bsky.feed.defs#threadViewPost","post":%s},
                            {"$type":"app.bsky.feed.defs#blockedPost","blocked":true}]}}"""
                .formatted(post("xyz", "alice.test", 57), post("r1", "bob.test", 0)));
        expectGet("/app.bsky.feed.getLikes?uri=" + enc(uri) + "&limit=10",
                "{\"likes\":[{\"actor\":{\"did\":\"did:plc:f\",\"handle\":\"fan.test\",\"description\":\"hi\"}}]}");
        expectGet("/app.bsky.feed.getRepostedBy?uri=" + enc(uri) + "&limit=10", "{\"repostedBy\":[]}");
        PostInteractions result = service.getPostInteractions("https://bsky.app/profile/alice.test/post/xyz", 10);
        server.verify();
        assertThat(result.post().likeCount()).isEqualTo(57);
        assertThat(result.replies()).extracting(PostResult::author).containsExactly("@bob.test");
        assertThat(result.likedBy()).singleElement().satisfies(a -> {
            assertThat(a.handle()).isEqualTo("@fan.test");
            assertThat(a.bio()).isEqualTo("hi");
        });
    }

    @Test
    void atUriWithDidIsUsedDirectlyAndNotFoundThreadIsReported() {
        expectLogin("jwt1");
        String uri = "at://did:plc:alice/app.bsky.feed.post/gone";
        expectGet("/app.bsky.feed.getPostThread?uri=" + enc(uri) + "&depth=1&parentHeight=0",
                "{\"thread\":{\"$type\":\"app.bsky.feed.defs#notFoundPost\",\"notFound\":true}}");
        assertThatThrownBy(() -> service.getPostInteractions(uri, 10)).hasMessage("Post '" + uri + "' not found on bluesky");
        server.verify();
    }

    @Test
    void invalidReferencesMakeNoCall() {
        assertThatThrownBy(() -> service.getPostInteractions("12345", 10))
                .hasMessage("Invalid bluesky post reference '12345'");
        assertThatThrownBy(() -> service.getPostInteractions("hello", 10)).hasMessageContaining("Invalid bluesky");
        server.verify();
    }

    // --- Trends ---

    @Test
    void trendsUseGetTrendsWithPostCounts() {
        expectLogin("jwt1");
        expectGet("/app.bsky.unspecced.getTrends?limit=25", """
                {"trends":[{"topic":"t","displayName":"Eclipse","link":"/profile/trending.bsky.app/feed/1","postCount":321,
                            "startedAt":"2026-09-25T08:00:00Z","actors":[]}]}""");
        TrendsResult trends = service.getTrends(40);
        server.verify();
        assertThat(trends.tags()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("Eclipse");
            assertThat(t.url()).isEqualTo("https://bsky.app/profile/trending.bsky.app/feed/1");
            assertThat(t.recentUses()).isEqualTo(321);
        });
        assertThat(trends.posts()).isEmpty();
        assertThat(trends.notes()).contains("since each trend started");
    }

    @Test
    void trendsFallBackToTrendingTopics() {
        expectLogin("jwt1");
        expectError("/app.bsky.unspecced.getTrends?limit=10", HttpStatus.NOT_IMPLEMENTED, "MethodNotImplemented", "no");
        expectGet("/app.bsky.unspecced.getTrendingTopics?limit=10",
                "{\"topics\":[{\"topic\":\"raw\",\"link\":\"/search?q=raw\"}],\"suggested\":[]}");
        TrendsResult trends = service.getTrends(10);
        server.verify();
        assertThat(trends.tags()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("raw");
            assertThat(t.recentUses()).isNull();
        });
        assertThat(trends.notes()).contains("no usage counts");
    }

    @Test
    void trendsUnavailableWhenBothEndpointsFail() {
        expectLogin("jwt1");
        expectGet("/app.bsky.unspecced.getTrends?limit=10", "{}");
        expectError("/app.bsky.unspecced.getTrendingTopics?limit=10", HttpStatus.NOT_FOUND, "NotFound", "gone");
        assertThatThrownBy(() -> service.getTrends(10)).hasMessage("Bluesky trending topics are currently unavailable");
    }

    // --- Similar accounts ---

    @Test
    void similarAccountsExcludeTargetAndSelf() {
        expectLogin("jwt1");
        expectGet("/app.bsky.graph.getSuggestedFollowsByActor?actor=alice.test", """
                {"suggestions":[{"did":"did:plc:alice","handle":"alice.test"},{"did":"did:plc:me","handle":"me.bsky.social"},
                                {"did":"did:plc:a","handle":"a.test"},{"did":"did:plc:b","handle":"b.test"},
                                {"did":"did:plc:c","handle":"c.test"}]}""");
        SimilarAccountsResult result = service.findSimilarAccounts("alice.test", 2);
        server.verify();
        assertThat(result.method()).isEqualTo("platform-suggestions");
        assertThat(result.basedOn()).isEmpty();
        assertThat(result.accounts()).extracting(a -> a.handle()).containsExactly("@a.test", "@b.test");
    }

    // --- Posting ---

    @Test
    void threadRecordsReferenceRootAndParent() {
        expectLogin("jwt1");
        Capture capture = new Capture();
        server.expect(ExpectedCount.times(3), requestTo(XRPC + "/com.atproto.repo.createRecord"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer jwt1"))
                .andExpect(capture)
                .andRespond(withSuccess("{\"uri\":\"at://did:plc:me/app.bsky.feed.post/k1\",\"cid\":\"cid1\"}",
                        MediaType.APPLICATION_JSON));
        PublishedPost first = service.createPost("one", null, null);
        PublishedPost second = service.createPost("two", first, first);
        service.createPost("three", first, new PublishedPost("at://did:plc:me/app.bsky.feed.post/k2", "cid2", "u"));
        server.verify();
        assertThat(first.url()).isEqualTo("https://bsky.app/profile/me.bsky.social/post/k1");
        assertThat(first.cid()).isEqualTo("cid1");
        var r1 = capture.requests.get(0).body();
        assertThat(r1.get("repo").stringValue()).isEqualTo(ME);
        assertThat(r1.get("collection").stringValue()).isEqualTo("app.bsky.feed.post");
        assertThat(r1.get("record").has("reply")).isFalse();
        assertThat(r1.get("record").get("createdAt").stringValue()).isEqualTo("2026-09-25T12:00:00Z");
        var reply2 = capture.requests.get(1).body().get("record").get("reply");
        assertThat(reply2.get("root").get("cid").stringValue()).isEqualTo("cid1");
        assertThat(reply2.get("parent").get("cid").stringValue()).isEqualTo("cid1");
        var reply3 = capture.requests.get(2).body().get("record").get("reply");
        assertThat(reply3.get("root").get("uri").stringValue()).isEqualTo(first.id());
        assertThat(reply3.get("parent").get("uri").stringValue()).isEqualTo("at://did:plc:me/app.bsky.feed.post/k2");
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void expiredTokenMidThreadRetriesThatPartOnce() {
        expectLogin("jwt1");
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.repo.createRecord"))
                .andExpect(header("Authorization", "Bearer jwt1"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"ExpiredToken\",\"message\":\"expired\"}"));
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.server.refreshSession"))
                .andRespond(withSuccess(session("jwt2"), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.repo.createRecord"))
                .andExpect(header("Authorization", "Bearer jwt2"))
                .andRespond(withSuccess("{\"uri\":\"at://did:plc:me/app.bsky.feed.post/k9\",\"cid\":\"c9\"}",
                        MediaType.APPLICATION_JSON));
        assertThat(service.createPost("part", null, null).cid()).isEqualTo("c9");
        server.verify();
    }

    // --- Length ---

    @Test
    void lengthUsesGraphemesAndBytes() {
        PartCheck over = service.checkPart(1, "a".repeat(301));
        assertThat(over.ok()).isFalse();
        assertThat(over.reason()).isEqualTo("301/300 graphemes (1 over)");
        PartCheck emoji = service.checkPart(1, "😀".repeat(300));
        assertThat(emoji.ok()).isTrue();
        assertThat(emoji.bytes()).isEqualTo(1200);
        PartCheck family = service.checkPart(1, FAMILY.repeat(300));
        assertThat(family.ok()).isFalse();
        assertThat(family.reason()).isEqualTo("7500/3000 bytes (4500 over)");
        server.verify(); // no HTTP calls for length checks
    }

    @Test
    void postingRulesAreFixedApartFromThePdsUploadLimit() {
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.server.describeServer"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"did\":\"did:web:pds.test\",\"availableUserDomains\":[],\"blobUploadLimit\":314572800}",
                        MediaType.APPLICATION_JSON));
        assertThat(service.postingRules().maxBytes()).isEqualTo(3000);
        assertThat(service.postingRules().followUpVisibility()).isNull();
        server.verify(); // describeServer is fetched once and cached
    }

    @Test
    void handleValidation() {
        assertThat(service.isValidHandle("alice.bsky.social")).isTrue();
        assertThat(service.isValidHandle("did:plc:abc")).isTrue();
        assertThat(service.isValidHandle("alice")).isFalse();
        assertThat(List.of("user@mastodon.social").stream().noneMatch(service::isValidHandle)).isTrue();
    }

}
