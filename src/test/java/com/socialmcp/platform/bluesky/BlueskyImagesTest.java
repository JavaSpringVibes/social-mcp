package com.socialmcp.platform.bluesky;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.config.TestProperties;
import com.socialmcp.media.TestImages;
import com.socialmcp.model.*;
import com.socialmcp.platform.TestSupport;
import com.socialmcp.platform.TestSupport.Capture;
import com.socialmcp.platform.TestSupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Image uploads, embeds, media mapping and image limits on Bluesky (SPEC §5, Images).
 */
class BlueskyImagesTest {

    private static final String XRPC = "https://pds.test/xrpc";

    private static final String ME = "did:plc:me";

    private static final String QUOTED = "at://did:plc:bob/app.bsky.feed.post/3q";
    /**
     * Each uploaded blob request: content type and raw body.
     */
    private final List<Object[]> blobs = new ArrayList<>();
    private MockRestServiceServer server;
    private BlueskyService service;

    private static String blob(String cid) {
        return """
                {"blob":{"$type":"blob","ref":{"$link":"%s"},"mimeType":"image/png","size":123}}""".formatted(cid);
    }

    private static PreparedImage image(int index, byte[] bytes, int width, int height, String alt) {
        return new PreparedImage(index, bytes, "image/png", width, height, alt);
    }

    private static JsonNode json(String text) {
        return TestSupport.JSON.readTree(text);
    }

    @BeforeEach
    void setUp() {
        service = service(2_000_000);
    }

    private BlueskyService service(long maxImageBytes) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SocialProperties properties = TestProperties.with(new SocialProperties.Bluesky("https://pds.test", "me.bsky.social",
                "app-pass", maxImageBytes));
        return new BlueskyService(properties, builder, new MutableClock());
    }

    private void expectLogin(String jwt) {
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.server.createSession"))
                .andRespond(withSuccess("""
                                {"accessJwt":"%s","refreshJwt":"refresh","did":"%s","handle":"me.bsky.social"}""".formatted(jwt, ME),
                        MediaType.APPLICATION_JSON));
    }

    private void expectBlob(String cid, String jwt) {
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.repo.uploadBlob"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + jwt))
                .andExpect(request -> {
                    MockClientHttpRequest mock = (MockClientHttpRequest) request;
                    blobs.add(new Object[]{String.valueOf(mock.getHeaders().getContentType()), mock.getBodyAsBytes()});
                })
                .andRespond(withSuccess(blob(cid), MediaType.APPLICATION_JSON));
    }

    private Capture expectCreate() {
        Capture capture = new Capture();
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.repo.createRecord"))
                .andExpect(capture)
                .andRespond(withSuccess("{\"uri\":\"at://" + ME + "/app.bsky.feed.post/3new\",\"cid\":\"cnew\"}",
                        MediaType.APPLICATION_JSON));
        return capture;
    }

    // --- Uploads and embeds ---

    @Test
    void imagesAreUploadedAsRawBlobsAndEmbeddedWithAspectRatios() {
        expectLogin("jwt1");
        expectBlob("bafy1", "jwt1");
        expectBlob("bafy2", "jwt1");
        Capture create = expectCreate();
        byte[] first = TestImages.png(4, 3);
        service.createTopLevelPost("", null, null,
                List.of(image(1, first, 4000, 3000, "A lake"), image(2, TestImages.png(2, 2), 2, 2, "A café")));
        server.verify();
        assertThat(blobs.get(0)[0]).isEqualTo("image/png");
        assertThat((byte[]) blobs.get(0)[1]).isEqualTo(first);
        JsonNode record = create.requests.get(0).body().path("record");
        assertThat(record.path("text").asString()).isEmpty();
        assertThat(record.path("embed")).isEqualTo(json("""
                {"$type":"app.bsky.embed.images","images":[
                 {"image":{"$type":"blob","ref":{"$link":"bafy1"},"mimeType":"image/png","size":123},"alt":"A lake",
                  "aspectRatio":{"width":4000,"height":3000}},
                 {"image":{"$type":"blob","ref":{"$link":"bafy2"},"mimeType":"image/png","size":123},"alt":"A café",
                  "aspectRatio":{"width":2,"height":2}}]}"""));
    }

    @Test
    void imagesWithAQuoteUseRecordWithMedia() {
        expectLogin("jwt1");
        expectBlob("bafy1", "jwt1");
        Capture create = expectCreate();
        QuoteTarget quote = new QuoteTarget(new PublishedPost(QUOTED, "cq", "u"), "@bob.bsky.social", null, null);
        service.createTopLevelPost("my take", quote, null, List.of(image(1, TestImages.png(2, 2), 2, 2, "alt")));
        JsonNode embed = create.requests.get(0).body().path("record").path("embed");
        assertThat(embed.path("$type").asString()).isEqualTo("app.bsky.embed.recordWithMedia");
        assertThat(embed.path("record")).isEqualTo(json("""
                {"$type":"app.bsky.embed.record","record":{"uri":"%s","cid":"cq"}}""".formatted(QUOTED)));
        assertThat(embed.path("media").path("$type").asString()).isEqualTo("app.bsky.embed.images");
    }

    @Test
    void aReplyWithImagesHasBothReplyAndEmbed() {
        expectLogin("jwt1");
        expectBlob("bafy1", "jwt1");
        Capture create = expectCreate();
        PublishedPost parent = new PublishedPost(QUOTED, "cq", "u");
        PostResult post = new PostResult("bluesky", QUOTED, "@bob.bsky.social", "hi", null, "u", 0, 0, 0, null, null,
                List.of());
        service.reply(new ReplyTarget(post, parent, parent, null, null), "look",
                List.of(image(1, TestImages.png(2, 2), 2, 2, "alt")));
        JsonNode record = create.requests.get(0).body().path("record");
        assertThat(record.path("reply").path("parent").path("uri").asString()).isEqualTo(QUOTED);
        assertThat(record.path("embed").path("$type").asString()).isEqualTo("app.bsky.embed.images");
    }

    @Test
    void anExpiredTokenOnUploadRefreshesAndRetriesTheUpload() {
        expectLogin("jwt1");
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.repo.uploadBlob"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"ExpiredToken\",\"message\":\"Token has expired\"}"));
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.server.refreshSession"))
                .andRespond(withSuccess("""
                                {"accessJwt":"jwt2","refreshJwt":"refresh2","did":"%s","handle":"me.bsky.social"}""".formatted(ME),
                        MediaType.APPLICATION_JSON));
        expectBlob("bafy1", "jwt2");
        expectCreate();
        service.createTopLevelPost("x", null, null, List.of(image(1, TestImages.png(2, 2), 2, 2, "alt")));
        server.verify();
    }

    @Test
    void aFailedUploadNamesTheImageAndPostsNothing() {
        expectLogin("jwt1");
        expectBlob("bafy1", "jwt1");
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.repo.uploadBlob"))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"InternalServerError\",\"message\":\"Internal Server Error\"}"));
        assertThatThrownBy(() -> service.createTopLevelPost("x", null, null,
                List.of(image(1, TestImages.png(2, 2), 2, 2, "a"), image(2, TestImages.png(2, 2), 2, 2, "b"))))
                .hasMessage("Image 2 of 2 could not be uploaded to bluesky: Internal Server Error. Nothing was posted.");
        server.verify();
    }

    @Test
    void aRejectedPostWithAnImageOverOneMegabyteGetsTheOldPdsHint() {
        expectLogin("jwt1");
        expectBlob("bafy1", "jwt1");
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.repo.createRecord"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"InvalidRequest\",\"message\":\"blob too big\"}"));
        assertThatThrownBy(() -> service.createTopLevelPost("x", null, null,
                List.of(image(1, new byte[1_500_000], 2, 2, "a"))))
                .hasMessageStartingWith("bluesky API error 400: ")
                .hasMessageEndingWith("set BLUESKY_MAX_IMAGE_BYTES=1000000, or resize the images below 1 MB.");
    }

    @Test
    void smallImagesGetNoOldPdsHint() {
        expectLogin("jwt1");
        expectBlob("bafy1", "jwt1");
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.repo.createRecord"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"InvalidRequest\",\"message\":\"nope\"}"));
        assertThatThrownBy(() -> service.createTopLevelPost("x", null, null,
                List.of(image(1, new byte[900_000], 2, 2, "a"))))
                .isNotInstanceOf(IllegalStateException.class);
    }

    // --- Reading media ---

    @Test
    void mediaIsMappedFromEachEmbedKind() {
        assertThat(BlueskyService.toMedia(json("""
                {"$type":"app.bsky.embed.images#view","images":[{"thumb":"t1","fullsize":"f1","alt":"A cat"},
                 {"thumb":"t2","fullsize":"f2","alt":""}]}"""))).containsExactly(new MediaSummary("image", "f1", "t1", "A cat"),
                new MediaSummary("image", "f2", "t2", null));
        assertThat(BlueskyService.toMedia(json("""
                {"$type":"app.bsky.embed.recordWithMedia#view","record":{"record":{}},
                 "media":{"$type":"app.bsky.embed.images#view","images":[{"thumb":"t","fullsize":"f","alt":"x"}]}}""")))
                .containsExactly(new MediaSummary("image", "f", "t", "x"));
        assertThat(BlueskyService.toMedia(json("""
                {"$type":"app.bsky.embed.gallery#view","items":[
                 {"$type":"app.bsky.embed.gallery#viewImage","thumbnail":"t","fullsize":"f","alt":"g","aspectRatio":{"width":1,"height":1}},
                 {"$type":"app.bsky.embed.gallery#viewSomethingNew"}]}""")))
                .containsExactly(new MediaSummary("image", "f", "t", "g"));
        assertThat(BlueskyService.toMedia(json("""
                {"$type":"app.bsky.embed.video#view","cid":"c","playlist":"https://video/p.m3u8","thumbnail":"t","alt":"v"}""")))
                .containsExactly(new MediaSummary("video", "https://video/p.m3u8", "t", "v"));
        assertThat(BlueskyService.toMedia(json("{\"$type\":\"app.bsky.embed.record#view\",\"record\":{}}"))).isEmpty();
        assertThat(BlueskyService.toMedia(json("{\"$type\":\"app.bsky.embed.external#view\",\"external\":{}}"))).isEmpty();
        assertThat(BlueskyService.toMedia(null)).isEmpty();
    }

    // --- Limits ---

    private void expectDescribeServer(String extra) {
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.server.describeServer"))
                .andRespond(withSuccess("{\"did\":\"did:web:pds.test\",\"availableUserDomains\":[]" + extra + "}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void theLexiconLimitAppliesWhenThePdsAllowsMore() {
        expectDescribeServer(",\"blobUploadLimit\":314572800");
        ImageRules rules = service.postingRules().images();
        assertThat(rules).isEqualTo(new ImageRules(4, 2_000_000, null, 2000,
                List.of("image/jpeg", "image/png", "image/gif", "image/webp"), true, false, 4000, "lexicon+server"));
        service.postingRules();
        server.verify(); // fetched once
    }

    @Test
    void aSmallerPdsLimitWins() {
        expectDescribeServer(",\"blobUploadLimit\":500000");
        assertThat(service.postingRules().images().maxBytes()).isEqualTo(500_000);
    }

    @Test
    void noPdsLimitMeansTheLexiconAlone() {
        expectDescribeServer("");
        assertThat(service.postingRules().images().source()).isEqualTo("lexicon");
    }

    @Test
    void aFailedDescribeServerFallsBackAndRetriesOnlyAfterTheBackoff() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        MutableClock clock = new MutableClock();
        service = new BlueskyService(TestProperties.with(TestProperties.bluesky("https://pds.test", "me.bsky.social", "p")),
                builder, clock);
        server.expect(ExpectedCount.once(), requestTo(XRPC + "/com.atproto.server.describeServer")).andRespond(withServerError());
        assertThat(service.postingRules().images().maxBytes()).isEqualTo(2_000_000);
        assertThat(service.postingRules().images().source()).isEqualTo("lexicon");
        server.verify();
        server.reset();
        clock.advance(java.time.Duration.ofMinutes(10));
        expectDescribeServer(",\"blobUploadLimit\":500000");
        assertThat(service.postingRules().images().maxBytes()).isEqualTo(500_000);
    }

    @Test
    void theConfiguredLimitCanBeLowered() {
        service = service(1_000_000);
        expectDescribeServer(",\"blobUploadLimit\":314572800");
        assertThat(service.postingRules().images().maxBytes()).isEqualTo(1_000_000);
    }

}
