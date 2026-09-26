package com.socialmcp.platform.mastodon;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Relationships, post actions, bookmarks, replies, quotes, polls and votes on Mastodon (SPEC §5).
 */
class MastodonActionsTest {

    private static final String BASE = "https://mastodon.test";

    private static final String BOB = "bob@remote.social";

    private static final String RELATIONSHIPS = "/api/v1/accounts/relationships?id%5B%5D=42";

    private static final String SCOPE_ERROR = "{\"error\":\"This action is outside the authorized scopes\"}";
    private static final String OPEN_POLL = """
            {"id":"p1","expired":false,"multiple":false,"voted":false,"votes_count":5,"voters_count":null,
             "options":[{"title":"A","votes_count":1},{"title":"B","votes_count":4},{"title":"C","votes_count":0}]}""";
    private MockRestServiceServer server;
    private MastodonService service;

    // --- Fixtures ---

    private static String relationship(String fields) {
        return "{\"id\":\"42\"," + fields + "}";
    }

    private static String status(String id, String accountId, String extra) {
        return """
                {"id":"%s","created_at":"2026-09-25T10:00:00.000Z","content":"<p>post %s</p>",
                 "url":"https://remote.social/@bob/%s","visibility":"public","replies_count":0,"reblogs_count":3,
                 "favourites_count":7,"reblog":null,
                 "account":{"id":"%s","acct":"%s","display_name":"","note":"","url":"u"}%s}"""
                .formatted(id, id, id, accountId, "me".equals(accountId) ? "me@mastodon.test" : BOB, extra);
    }

    private static String pollStatus(String accountId, String poll) {
        return status("7", accountId, ",\"poll\":" + poll);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        SocialProperties properties = TestProperties
                .with(TestProperties.mastodon(BASE, "secret-token", "unlisted"));
        service = new MastodonService(properties, builder, new MutableClock());
    }

    private void expectGet(String pathAndQuery, String json) {
        server.expect(ExpectedCount.once(), requestTo(BASE + pathAndQuery))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private Capture expectPost(String path, String json) {
        Capture capture = new Capture();
        server.expect(ExpectedCount.once(), requestTo(BASE + path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(capture)
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        return capture;
    }

    private void expectPostError(String path, HttpStatus status, String body) {
        server.expect(ExpectedCount.once(), requestTo(BASE + path))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private void expectOwnId() {
        expectGet("/api/v1/accounts/verify_credentials", "{\"id\":\"me\",\"acct\":\"me\"}");
    }

    private void expectInstance(int apiVersion) {
        expectGet("/api/v2/instance", """
                {"domain":"mastodon.test","api_versions":{"mastodon":%d},
                 "configuration":{"statuses":{"max_characters":500,"characters_reserved_per_url":23},
                 "polls":{"max_options":6,"max_characters_per_option":60,"min_expiration":300,"max_expiration":604800}}}"""
                .formatted(apiVersion));
    }

    // --- setRelationship: follow ---

    private void expectBob(boolean locked) {
        expectGet("/api/v1/accounts/lookup?acct=" + enc(BOB), """
                {"id":"42","acct":"%s","display_name":"Bob","note":"","url":"https://remote.social/@bob","locked":%s}"""
                .formatted(BOB, locked));
    }

    private void expectRelationship(String fields) {
        expectGet(RELATIONSHIPS, "[{\"id\":\"42\"," + fields + "}]");
    }

    @Test
    void followsWhenNotFollowingYet() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"following\":false,\"requested\":false,\"blocking\":false,\"blocked_by\":false");
        Capture follow = expectPost("/api/v1/accounts/42/follow", relationship("\"following\":true"));
        RelationshipResult result = service.setRelationship(BOB, AccountAction.FOLLOW);
        server.verify();
        assertThat(result.status()).isEqualTo("following");
        assertThat(result.action()).isEqualTo("follow");
        assertThat(result.account().handle()).isEqualTo("@" + BOB);
        assertThat(result.note()).isNull();
        assertThat(follow.requests.get(0).body()).isNull();
    }

    @Test
    void followOfUnlockedRemoteAccountIsRequestedWithDomainNote() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"following\":false,\"requested\":false");
        expectPost("/api/v1/accounts/42/follow", relationship("\"following\":false,\"requested\":true"));
        RelationshipResult result = service.setRelationship(BOB, AccountAction.FOLLOW);
        assertThat(result.status()).isEqualTo("requested");
        assertThat(result.note()).isEqualTo("Waiting for remote.social to confirm; this usually takes a few seconds.");
    }

    @Test
    void followOfLockedAccountNotesApproval() {
        expectBob(true);
        expectOwnId();
        expectRelationship("\"following\":false,\"requested\":false");
        expectPost("/api/v1/accounts/42/follow", relationship("\"requested\":true"));
        assertThat(service.setRelationship(BOB, AccountAction.FOLLOW).note())
                .isEqualTo("Waiting for the account to approve the request.");
    }

    @Test
    void existingFollowOrRequestIsNotRepeated() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"following\":true");
        assertThat(service.setRelationship(BOB, AccountAction.FOLLOW).status()).isEqualTo("already-following");
        server.verify(); // no follow call was expected

        setUp();
        expectBob(false);
        expectOwnId();
        expectRelationship("\"following\":false,\"requested\":true");
        assertThat(service.setRelationship(BOB, AccountAction.FOLLOW).status()).isEqualTo("already-requested");
        server.verify();
    }

    @Test
    void followIsRefusedWhenEitherSideBlocks() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"blocked_by\":true");
        assertThatThrownBy(() -> service.setRelationship(BOB, AccountAction.FOLLOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Can't follow '" + BOB + "' on mastodon because of a block");
        server.verify();
    }

    @Test
    void ownAccountIsRefusedWithoutRelationshipCall() {
        expectGet("/api/v1/accounts/lookup?acct=me", "{\"id\":\"me\",\"acct\":\"me\"}");
        expectOwnId();
        assertThatThrownBy(() -> service.setRelationship("me", AccountAction.BLOCK))
                .hasMessage("You can't block your own account");
        server.verify();
    }

    // --- setRelationship: unfollow, block, unblock, mute, unmute ---

    @Test
    void missingScopeNamesTheScopesToAdd() {
        expectBob(false);
        expectOwnId();
        server.expect(requestTo(BASE + RELATIONSHIPS))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body(SCOPE_ERROR));
        assertThatThrownBy(() -> service.setRelationship(BOB, AccountAction.MUTE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The Mastodon access token lacks the read:follows, write:mutes scope(s) needed by "
                        + "setAccountRelationship (mute). Add them to the application and regenerate the token.");
    }

    @Test
    void otherForbiddenFollowMeansNotAllowed() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"following\":false");
        expectPostError("/api/v1/accounts/42/follow", HttpStatus.FORBIDDEN, "{\"error\":\"This action is not allowed\"}");
        assertThatThrownBy(() -> service.setRelationship(BOB, AccountAction.FOLLOW)).hasMessage(
                "Mastodon doesn't allow following '" + BOB + "' (the account may have moved, or its server may be blocked)");
    }

    @Test
    void unfollowReportsWhatWasUndone() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"following\":false,\"requested\":true");
        expectPost("/api/v1/accounts/42/unfollow", relationship("\"following\":false,\"requested\":false"));
        assertThat(service.setRelationship(BOB, AccountAction.UNFOLLOW).status()).isEqualTo("request-cancelled");

        setUp();
        expectBob(false);
        expectOwnId();
        expectRelationship("\"following\":false,\"requested\":false");
        assertThat(service.setRelationship(BOB, AccountAction.UNFOLLOW).status()).isEqualTo("not-following");
        server.verify();
    }

    @Test
    void unconfirmedUnfollowIsAnError() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"following\":true");
        expectPost("/api/v1/accounts/42/unfollow", relationship("\"following\":true"));
        assertThatThrownBy(() -> service.setRelationship(BOB, AccountAction.UNFOLLOW))
                .hasMessage("Mastodon did not confirm the unfollow of '" + BOB + "'");
    }

    @Test
    void blockIsMadeEvenWhenBlockedByThem() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"blocking\":false,\"blocked_by\":true");
        expectPost("/api/v1/accounts/42/block", relationship("\"blocking\":true"));
        assertThat(service.setRelationship(BOB, AccountAction.BLOCK).status()).isEqualTo("blocked");
    }

    @Test
    void unblockNotesADomainBlock() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"blocking\":true,\"domain_blocking\":true");
        expectPost("/api/v1/accounts/42/unblock", relationship("\"blocking\":false"));
        RelationshipResult result = service.setRelationship(BOB, AccountAction.UNBLOCK);
        assertThat(result.status()).isEqualTo("unblocked");
        assertThat(result.note())
                .isEqualTo("Their server remote.social is also blocked; manage domain blocks in Mastodon's settings.");
    }

    // --- setPostAction ---

    @Test
    void muteSkipsAFullIndefiniteMuteButWidensOthers() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"muting\":true,\"muting_notifications\":true,\"muting_expires_at\":null");
        assertThat(service.setRelationship(BOB, AccountAction.MUTE).status()).isEqualTo("already-muted");
        server.verify();

        setUp();
        expectBob(false);
        expectOwnId();
        expectRelationship("\"muting\":true,\"muting_notifications\":false,\"muting_expires_at\":null");
        Capture mute = expectPost("/api/v1/accounts/42/mute", relationship("\"muting\":true"));
        assertThat(service.setRelationship(BOB, AccountAction.MUTE).status()).isEqualTo("muted");
        assertThat(mute.requests.get(0).body()).isNull();
    }

    @Test
    void unmuteSkipsWhenNotMuted() {
        expectBob(false);
        expectOwnId();
        expectRelationship("\"muting\":false");
        assertThat(service.setRelationship(BOB, AccountAction.UNMUTE).status()).isEqualTo("not-muted");
        server.verify();
    }

    @Test
    void likeFavouritesAndReportsTheNewCount() {
        expectGet("/api/v1/statuses/7", status("7", "42", ",\"favourited\":false"));
        expectPost("/api/v1/statuses/7/favourite", status("7", "42", ",\"favourited\":true,\"favourites_count\":8")
                .replace("\"favourites_count\":7,", ""));
        PostActionResult result = service.setPostAction("7", PostAction.LIKE);
        server.verify();
        assertThat(result.status()).isEqualTo("liked");
        assertThat(result.post().likeCount()).isEqualTo(8);
    }

    @Test
    void alreadyLikedMakesNoWrite() {
        expectGet("/api/v1/statuses/7", status("7", "42", ",\"favourited\":true"));
        assertThat(service.setPostAction("7", PostAction.LIKE).status()).isEqualTo("already-liked");
        server.verify();
    }

    @Test
    void actionsOnABoostTargetTheOriginal() {
        String boost = "{\"id\":\"99\",\"account\":{\"id\":\"me\",\"acct\":\"me\"},\"reblog\":"
                + status("7", "42", ",\"bookmarked\":false") + "}";
        expectGet("/api/v1/statuses/99", boost);
        expectPost("/api/v1/statuses/7/bookmark", status("7", "42", ",\"bookmarked\":true"));
        PostActionResult result = service.setPostAction("99", PostAction.BOOKMARK);
        server.verify();
        assertThat(result.status()).isEqualTo("bookmarked");
        assertThat(result.post().id()).isEqualTo("7");
    }

    @Test
    void missingPostIsNotFound() {
        server.expect(requestTo(BASE + "/api/v1/statuses/7")).andRespond(withResourceNotFound());
        assertThatThrownBy(() -> service.setPostAction("7", PostAction.UNLIKE))
                .hasMessage("Post '7' not found on mastodon");
    }

    @Test
    void invalidReferenceMakesNoCall() {
        assertThatThrownBy(() -> service.setPostAction("hello", PostAction.LIKE))
                .hasMessage("Invalid mastodon post reference 'hello'");
        server.verify();
    }

    @Test
    void missingFavouritesScopeIsNamed() {
        expectGet("/api/v1/statuses/7", status("7", "42", ",\"favourited\":false"));
        expectPostError("/api/v1/statuses/7/favourite", HttpStatus.FORBIDDEN, SCOPE_ERROR);
        assertThatThrownBy(() -> service.setPostAction("7", PostAction.LIKE))
                .hasMessageContaining("lacks the write:favourites scope(s) needed by setPostAction (like)");
    }

    @Test
    void repostMapsTheBoostWrapperToTheOriginal() {
        expectGet("/api/v1/statuses/7", status("7", "42", ",\"reblogged\":false"));
        expectOwnId();
        expectPost("/api/v1/statuses/7/reblog", "{\"id\":\"100\",\"account\":{\"id\":\"me\",\"acct\":\"me\"},\"reblog\":"
                + status("7", "42", ",\"reblogged\":true") + "}");
        PostActionResult result = service.setPostAction("7", PostAction.REPOST);
        assertThat(result.status()).isEqualTo("reposted");
        assertThat(result.post().id()).isEqualTo("7");
    }

    @Test
    void repostRefusesDirectAndOthersFollowersOnlyPosts() {
        expectGet("/api/v1/statuses/7", status("7", "42", ",\"reblogged\":false").replace("\"public\"", "\"private\""));
        expectOwnId();
        assertThatThrownBy(() -> service.setPostAction("7", PostAction.REPOST))
                .hasMessage("This post can't be reposted on mastodon (it is followers-only)");
        server.verify();

        setUp();
        expectGet("/api/v1/statuses/8", status("8", "me", ",\"reblogged\":false").replace("\"public\"", "\"direct\""));
        expectOwnId();
        assertThatThrownBy(() -> service.setPostAction("8", PostAction.REPOST))
                .hasMessage("This post can't be reposted on mastodon (it is a direct message)");
        server.verify();
    }

    // --- Replies ---

    @Test
    void ownFollowersOnlyPostCanBeReposted() {
        expectGet("/api/v1/statuses/8", status("8", "me", ",\"reblogged\":false").replace("\"public\"", "\"private\""));
        expectOwnId();
        expectPost("/api/v1/statuses/8/reblog", "{\"id\":\"101\",\"reblog\":" + status("8", "me", ",\"reblogged\":true") + "}");
        assertThat(service.setPostAction("8", PostAction.REPOST).status()).isEqualTo("reposted");
    }

    @Test
    void bookmarksAreReadInOrder() {
        expectGet("/api/v1/bookmarks?limit=40", "[" + status("2", "42", "") + "," + status("1", "42", "") + "]");
        List<PostResult> bookmarks = service.getBookmarks(100);
        server.verify();
        assertThat(bookmarks).extracting(PostResult::id).containsExactly("2", "1");
    }

    // --- Quotes ---

    @Test
    void replyTargetMentionsOthersAndMirrorsVisibility() {
        expectGet("/api/v1/statuses/7", status("7", "42", "").replace("\"public\"", "\"unlisted\""));
        expectOwnId();
        Capture reply = expectPost("/api/v1/statuses", "{\"id\":\"200\",\"url\":\"https://mastodon.test/@me/200\"}");
        ReplyTarget target = service.replyTarget("7");
        assertThat(target.mention()).isEqualTo(BOB);
        assertThat(target.visibility()).isEqualTo("unlisted");
        assertThat(service.reply(target, "@" + BOB + " thanks", List.of()).url()).isEqualTo("https://mastodon.test/@me/200");
        assertThat(reply.requests.get(0).body().toString())
                .isEqualTo("{\"status\":\"@" + BOB + " thanks\",\"in_reply_to_id\":\"7\",\"visibility\":\"unlisted\"}");
        assertThat(reply.requests.get(0).headers().getFirst("Idempotency-Key")).isNotBlank();
    }

    @Test
    void replyToOwnPostNeedsNoMention() {
        expectGet("/api/v1/statuses/8", status("8", "me", ""));
        expectOwnId();
        assertThat(service.replyTarget("8").mention()).isNull();
    }

    @Test
    void quoteOfAutomaticallyAllowedPost() {
        expectInstance(7);
        expectGet("/api/v1/statuses/7", status("7", "42", ",\"quote_approval\":{\"current_user\":\"automatic\"}"));
        Capture post = expectPost("/api/v1/statuses",
                "{\"id\":\"300\",\"url\":\"https://mastodon.test/@me/300\",\"quote\":{\"state\":\"accepted\"}}");
        QuoteTarget target = service.quoteTarget("7");
        assertThat(target.caveat()).isNull();
        assertThat(target.visibility()).isEqualTo("public");
        NewPost created = service.createTopLevelPost("my take", target, null, List.of());
        assertThat(created.caveat()).isNull();
        assertThat(post.requests.get(0).body().toString())
                .isEqualTo("{\"status\":\"my take\",\"visibility\":\"public\",\"quoted_status_id\":\"7\"}");
    }

    @Test
    void quoteNeedingApprovalCarriesAPendingCaveat() {
        expectInstance(7);
        expectGet("/api/v1/statuses/7", status("7", "42", ",\"quote_approval\":{\"current_user\":\"manual\"}"));
        assertThat(service.quoteTarget("7").caveat()).isEqualTo("the quote is waiting for @" + BOB + " to approve it");
    }

    @Test
    void pendingStateInTheResponseAddsTheCaveat() {
        expectInstance(7);
        expectGet("/api/v1/statuses/7", status("7", "42", ",\"quote_approval\":{\"current_user\":\"automatic\"}"));
        expectPost("/api/v1/statuses", "{\"id\":\"300\",\"url\":\"u\",\"quote\":{\"state\":\"pending\"}}");
        QuoteTarget target = service.quoteTarget("7");
        assertThat(service.createTopLevelPost("x", target, null, List.of()).caveat())
                .isEqualTo("the quote is waiting for @" + BOB + " to approve it");
    }

    @Test
    void quoteIsRefusedWhenDeniedUnknownOrDirect() {
        for (String extra : List.of(",\"quote_approval\":{\"current_user\":\"denied\"}",
                ",\"quote_approval\":{\"current_user\":\"unknown\"}", "")) {
            setUp();
            expectInstance(7);
            expectGet("/api/v1/statuses/7", status("7", "42", extra));
            assertThatThrownBy(() -> service.quoteTarget("7"))
                    .hasMessage("You can't quote this post on mastodon (the author doesn't allow you to quote it)");
        }
        setUp();
        expectInstance(7);
        expectGet("/api/v1/statuses/7", status("7", "42", ",\"quote_approval\":{\"current_user\":\"automatic\"}")
                .replace("\"public\"", "\"direct\""));
        assertThatThrownBy(() -> service.quoteTarget("7"))
                .hasMessage("You can't quote this post on mastodon (it is a direct message)");
    }

    @Test
    void quotingAFollowersOnlyPostPostsFollowersOnly() {
        expectInstance(7);
        expectGet("/api/v1/statuses/7", status("7", "42", ",\"quote_approval\":{\"current_user\":\"automatic\"}")
                .replace("\"public\"", "\"private\""));
        QuoteTarget target = service.quoteTarget("7");
        assertThat(target.visibility()).isEqualTo("private");
        assertThat(target.caveat()).isEqualTo("posted as followers-only, because the quoted post is followers-only");
    }

    @Test
    void oldServerCannotQuoteAndMakesNoStatusCall() {
        expectInstance(6);
        assertThatThrownBy(() -> service.quoteTarget("7")).hasMessage(
                "You can't quote this post on mastodon (this server doesn't support quote posts; it needs Mastodon 4.5 or later)");
        server.verify();
    }

    // --- Polls and votes ---

    @Test
    void postingRulesReportQuotesAndPollLimits() {
        expectInstance(7);
        PostingRules rules = service.postingRules();
        assertThat(rules.quotes()).isTrue();
        assertThat(rules.polls()).isNotNull();
        assertThat(rules.polls().maxOptions()).isEqualTo(6);
        assertThat(rules.polls().maxOptionLength()).isEqualTo(60);
        assertThat(rules.polls().minExpiresInMinutes()).isEqualTo(5);
        assertThat(rules.polls().maxExpiresInMinutes()).isEqualTo(10080);
    }

    @Test
    void pollDefaultsApplyWhenTheInstanceOmitsThem() {
        assertThat(MastodonService.pollRules(null)).satisfies(r -> {
            assertThat(r.maxOptions()).isEqualTo(4);
            assertThat(r.maxOptionLength()).isEqualTo(50);
            assertThat(r.minExpiresInMinutes()).isEqualTo(5);
            assertThat(r.maxExpiresInMinutes()).isEqualTo(43829);
        });
    }

    @Test
    void pollIsPostedWithSecondsAndDefaults() {
        Capture post = expectPost("/api/v1/statuses", "{\"id\":\"400\",\"url\":\"u\"}");
        service.createTopLevelPost("Java or Kotlin?", null, new PollInput(List.of(" Java ", "Kotlin"), null, null, null), List.of());
        assertThat(post.requests.get(0).body().toString()).isEqualTo("""
                {"status":"Java or Kotlin?","visibility":"public","poll":{"options":["Java","Kotlin"],\
                "expires_in":86400,"multiple":false,"hide_totals":false}}""");
    }

    @Test
    void voteSendsZeroBasedChoicesAndReturnsTheUpdatedPoll() {
        expectGet("/api/v1/statuses/7", pollStatus("42", OPEN_POLL));
        expectOwnId();
        Capture vote = expectPost("/api/v1/polls/p1/votes", """
                {"id":"p1","expired":false,"multiple":false,"voted":true,"own_votes":[1],"votes_count":6,
                 "options":[{"title":"A","votes_count":1},{"title":"B","votes_count":5},{"title":"C","votes_count":0}]}""");
        VoteResult result = service.vote("7", List.of(2));
        assertThat(result.status()).isEqualTo("voted");
        assertThat(vote.requests.get(0).body().toString()).isEqualTo("{\"choices\":[1]}");
        assertThat(result.post().poll()).isNotNull();
        assertThat(result.post().poll().ownVotes()).containsExactly(2);
        assertThat(result.post().poll().options().get(1).votesCount()).isEqualTo(5);
        assertThat(result.post().poll().votersCount()).isNull();
    }

    @Test
    void alreadyVotedMakesNoCallEvenWhenExpired() {
        expectGet("/api/v1/statuses/7",
                pollStatus("42", OPEN_POLL.replace("\"voted\":false", "\"voted\":true,\"own_votes\":[0]")
                        .replace("\"expired\":false", "\"expired\":true")));
        VoteResult result = service.vote("7", List.of(1));
        server.verify();
        assertThat(result.status()).isEqualTo("already-voted");
        assertThat(result.post().poll().ownVotes()).containsExactly(1);
    }

    @Test
    void voteChecksNeedingThePoll() {
        expectGet("/api/v1/statuses/7", pollStatus("42", OPEN_POLL.replace("\"expired\":false", "\"expired\":true")));
        assertThatThrownBy(() -> service.vote("7", List.of(1))).hasMessage("The poll has ended");

        setUp();
        expectGet("/api/v1/statuses/7", pollStatus("me", OPEN_POLL));
        expectOwnId();
        assertThatThrownBy(() -> service.vote("7", List.of(1))).hasMessage("You can't vote in your own poll");

        setUp();
        expectGet("/api/v1/statuses/7", pollStatus("42", OPEN_POLL));
        expectOwnId();
        assertThatThrownBy(() -> service.vote("7", List.of(1, 2)))
                .hasMessage("This poll has 3 options, and allows only one choice");

        setUp();
        expectGet("/api/v1/statuses/7", status("7", "42", ""));
        assertThatThrownBy(() -> service.vote("7", List.of(1))).hasMessage("Post '7' has no poll");
        server.verify();
    }

    @Test
    void alreadyVotedRaceIsReportedAsAlreadyVoted() {
        server.expect(ExpectedCount.twice(), requestTo(BASE + "/api/v1/statuses/7"))
                .andRespond(withSuccess(pollStatus("42", OPEN_POLL), MediaType.APPLICATION_JSON));
        expectOwnId();
        expectPostError("/api/v1/polls/p1/votes", HttpStatus.UNPROCESSABLE_CONTENT,
                "{\"error\":\"Validation failed: You have already voted on this poll\"}");
        assertThat(service.vote("7", List.of(1)).status()).isEqualTo("already-voted");
    }

    // --- Read mapping ---

    @Test
    void mapsQuotesAndStripsTheQuoteInlineFallback() {
        String quoted = status("5", "42", "");
        String quotePost = """
                {"id":"6","content":"<p class=\\"quote-inline\\">RE: <a href=\\"u\\">u</a></p><p>My take</p>",
                 "url":"u6","account":{"id":"42","acct":"%s"},"reblog":null,
                 "quote":{"state":"accepted","quoted_status":%s}}""".formatted(BOB, quoted);
        String muted = """
                {"id":"8","content":"<p>x</p>","url":"u8","account":{"id":"42","acct":"%s"},
                 "quote":{"state":"muted_account","quoted_status_id":"5"}}""".formatted(BOB);
        expectGet("/api/v1/timelines/home?limit=10", "[" + quotePost + "," + muted + "]");
        List<PostResult> posts = service.getTimeline(com.socialmcp.model.TimelineType.HOME, 10);
        assertThat(posts.get(0).text()).isEqualTo("My take");
        assertThat(posts.get(0).quote()).isNotNull();
        assertThat(posts.get(0).quote().state()).isEqualTo("accepted");
        assertThat(posts.get(0).quote().id()).isEqualTo("5");
        assertThat(posts.get(0).quote().text()).isEqualTo("post 5");
        assertThat(posts.get(1).quote().state()).isEqualTo("muted");
        assertThat(posts.get(1).quote().id()).isEqualTo("5");
        assertThat(posts.get(1).quote().author()).isNull();
    }

}
