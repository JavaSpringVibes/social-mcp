package com.socialmcp.platform.mastodon;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.config.TestProperties;
import com.socialmcp.media.TestImages;
import com.socialmcp.model.*;
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
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Image uploads, media mapping and image limits on Mastodon (SPEC §5, Images).
 */
class MastodonImagesTest {

    private static final String BASE = "https://mastodon.test";
    /**
     * The raw bodies and content types of multipart uploads, in order.
     */
    private final List<String[]> uploads = new ArrayList<>();
    private MockRestServiceServer server;
    private MutableClock clock;
    private MastodonService service;

    private static PreparedImage image(int index, String alt) {
        byte[] png = TestImages.png(3, 2);
        return new PreparedImage(index, png, "image/png", 3, 2, alt);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock();
        SocialProperties properties = TestProperties.with(TestProperties.mastodon(BASE, "secret-token", "unlisted"));
        service = new MastodonService(properties, builder, clock, clock::advance);
    }

    private RequestMatcher captureUpload() {
        return request -> {
            MockClientHttpRequest mock = (MockClientHttpRequest) request;
            uploads.add(new String[]{String.valueOf(mock.getHeaders().getContentType()),
                    new String(mock.getBodyAsBytes(), StandardCharsets.ISO_8859_1)});
        };
    }

    private void expectUpload(HttpStatus status, String json) {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v2/media"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(captureUpload())
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(json));
    }

    private void expectMedia(String id, HttpStatus status, boolean ready) {
        server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v1/media/" + id))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"" + id + "\",\"url\":" + (ready ? "\"https://files/" + id + "\"" : "null") + "}"));
    }

    private Capture expectStatus() {
        Capture capture = new Capture();
        server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v1/statuses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(capture)
                .andRespond(withSuccess("{\"id\":\"500\",\"url\":\"https://mastodon.test/@me/500\"}", MediaType.APPLICATION_JSON));
        return capture;
    }

    // --- Uploads ---

    @Test
    void imagesAreUploadedInOrderThenAttached() {
        expectUpload(HttpStatus.OK, "{\"id\":\"m1\",\"url\":\"https://files/m1\"}");
        expectUpload(HttpStatus.OK, "{\"id\":\"m2\",\"url\":\"https://files/m2\"}");
        Capture status = expectStatus();

        service.createTopLevelPost("Tour day 1", null, null, List.of(image(1, "A lake at dawn"), image(2, "Café ☕")));

        server.verify();
        assertThat(uploads).hasSize(2);
        assertThat(uploads.get(0)[0]).startsWith("multipart/form-data");
        assertThat(uploads.get(0)[1]).contains("name=\"file\"; filename=\"image-1.png\"")
                .contains("Content-Type: image/png")
                .contains("name=\"description\"")
                .contains("A lake at dawn");
        assertThat(new String(uploads.get(1)[1].getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8))
                .contains("Café ☕");
        assertThat(status.requests.get(0).body().toString())
                .isEqualTo("{\"status\":\"Tour day 1\",\"visibility\":\"public\",\"media_ids\":[\"m1\",\"m2\"]}");
        assertThat(status.requests.get(0).headers().getFirst("Idempotency-Key")).isNotBlank();
    }

    @Test
    void anUploadStillProcessingIsPolledUntilReady() {
        expectUpload(HttpStatus.ACCEPTED, "{\"id\":\"m1\",\"url\":null}");
        expectMedia("m1", HttpStatus.PARTIAL_CONTENT, false);
        expectMedia("m1", HttpStatus.PARTIAL_CONTENT, false);
        expectMedia("m1", HttpStatus.OK, true);
        expectStatus();
        service.createTopLevelPost("", null, null, List.of(image(1, "A GIF")));
        server.verify();
    }

    @Test
    void processingThatNeverFinishesFailsWithoutPosting() {
        expectUpload(HttpStatus.ACCEPTED, "{\"id\":\"m1\",\"url\":null}");
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/api/v1/media/m1"))
                .andRespond(withStatus(HttpStatus.PARTIAL_CONTENT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"m1\",\"url\":null}"));
        assertThatThrownBy(() -> service.createTopLevelPost("x", null, null, List.of(image(1, "alt"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Image 1 of 1 could not be uploaded to mastodon: Mastodon is still processing it. Nothing was posted.");
        assertThat(Duration.between(java.time.Instant.parse("2026-09-25T12:00:00Z"), clock.instant()))
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aRejectedUploadNamesTheImageAndPostsNothing() {
        expectUpload(HttpStatus.OK, "{\"id\":\"m1\",\"url\":\"https://files/m1\"}");
        expectUpload(HttpStatus.UNPROCESSABLE_CONTENT, "{\"error\":\"Validation failed: File has contents that are not what they are reported to be\"}");
        assertThatThrownBy(() -> service.createTopLevelPost("x", null, null, List.of(image(1, "a"), image(2, "b"))))
                .hasMessage("Image 2 of 2 could not be uploaded to mastodon: Validation failed: File has contents that are "
                        + "not what they are reported to be. Nothing was posted.");
        server.verify();
    }

    @Test
    void aMissingScopeNamesWriteMedia() {
        expectUpload(HttpStatus.FORBIDDEN, "{\"error\":\"This action is outside the authorized scopes\"}");
        assertThatThrownBy(() -> service.createTopLevelPost("x", null, null, List.of(image(1, "a"))))
                .hasMessageContaining("lacks the write:media scope(s)");
    }

    @Test
    void aFailedStatusIsRetriedWithTheSameKeyAndMediaButNoNewUpload() {
        expectUpload(HttpStatus.OK, "{\"id\":\"m1\",\"url\":\"https://files/m1\"}");
        Capture first = new Capture();
        server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v1/statuses")).andExpect(first).andRespond(withServerError());
        Capture second = expectStatus();
        service.createTopLevelPost("x", null, null, List.of(image(1, "a")));
        server.verify();
        assertThat(uploads).hasSize(1);
        assertThat(second.requests.get(0).headers().getFirst("Idempotency-Key"))
                .isEqualTo(first.requests.get(0).headers().getFirst("Idempotency-Key"));
        assertThat(second.requests.get(0).body().get("media_ids").toString()).isEqualTo("[\"m1\"]");
    }

    @Test
    void mediaNotReadyAtPostTimeIsAwaitedThenRetriedOnce() {
        expectUpload(HttpStatus.OK, "{\"id\":\"m1\",\"url\":\"https://files/m1\"}");
        Capture first = new Capture();
        server.expect(ExpectedCount.once(), requestTo(BASE + "/api/v1/statuses"))
                .andExpect(first)
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Cannot attach files that have not finished processing. Try again in a moment!\"}"));
        expectMedia("m1", HttpStatus.OK, true);
        Capture second = expectStatus();
        service.createTopLevelPost("x", null, null, List.of(image(1, "a")));
        server.verify();
        assertThat(second.requests.get(0).headers().getFirst("Idempotency-Key"))
                .isEqualTo(first.requests.get(0).headers().getFirst("Idempotency-Key"));
    }

    @Test
    void anImageOnlyReplyCarriesTheMentionAndMedia() {
        expectUpload(HttpStatus.OK, "{\"id\":\"m1\",\"url\":\"https://files/m1\"}");
        Capture reply = expectStatus();
        PostResult parent = new PostResult("mastodon", "7", "@alice@example.social", "hi", null, "u", 0, 0, 0, null, null,
                List.of());
        PublishedPost ref = new PublishedPost("7", null, "u");
        service.reply(new ReplyTarget(parent, ref, ref, "unlisted", "alice@example.social"), "@alice@example.social",
                List.of(image(1, "a")));
        assertThat(reply.requests.get(0).body().toString()).isEqualTo("{\"status\":\"@alice@example.social\","
                + "\"in_reply_to_id\":\"7\",\"visibility\":\"unlisted\",\"media_ids\":[\"m1\"]}");
    }

    // --- Reading media ---

    @Test
    void attachmentsAreMappedIncludingBoostsAndRemoteFiles() {
        server.expect(requestTo(BASE + "/api/v1/timelines/home?limit=10")).andRespond(withSuccess("""
                        [{"id":"1","content":"<p>boost</p>","url":"u1","account":{"id":"a","acct":"x@y.z"},"media_attachments":[],
                          "reblog":{"id":"2","content":"<p>photos</p>","url":"u2","account":{"id":"b","acct":"bob@y.z"},
                           "media_attachments":[
                             {"type":"image","url":"https://f/a.png","preview_url":"https://f/s/a.png","description":"A cat"},
                             {"type":"gifv","url":null,"remote_url":"https://remote/b.mp4","preview_url":null,"description":null},
                             {"type":"sticker","url":"https://f/c","preview_url":null,"description":"  "}]}},
                         {"id":"3","content":"<p>none</p>","url":"u3","account":{"id":"a","acct":"x@y.z"}}]""",
                MediaType.APPLICATION_JSON));
        List<PostResult> posts = service.getTimeline(TimelineType.HOME, 10);
        assertThat(posts.get(0).media()).containsExactly(
                new MediaSummary("image", "https://f/a.png", "https://f/s/a.png", "A cat"),
                new MediaSummary("gifv", "https://remote/b.mp4", null, null),
                new MediaSummary("unknown", "https://f/c", null, null));
        assertThat(posts.get(1).media()).isEmpty();
    }

    // --- Limits ---

    @Test
    void imageLimitsComeFromTheInstance() {
        server.expect(requestTo(BASE + "/api/v2/instance")).andRespond(withSuccess("""
                        {"domain":"mastodon.test","configuration":{
                          "statuses":{"max_characters":500,"characters_reserved_per_url":23,"max_media_attachments":6},
                          "media_attachments":{"supported_mime_types":["image/jpeg","image/png","image/gif","video/mp4"],
                           "image_size_limit":10485760,"image_matrix_limit":16777216,"description_limit":1000}}}""",
                MediaType.APPLICATION_JSON));
        ImageRules rules = service.postingRules().images();
        assertThat(rules).isEqualTo(new ImageRules(6, 10_485_760, 16_777_216L, 1000,
                List.of("image/jpeg", "image/png", "image/gif"), false, false, null, "instance"));
    }

    @Test
    void missingImageLimitsUseMastodonDefaults() {
        server.expect(requestTo(BASE + "/api/v2/instance")).andRespond(withSuccess("""
                        {"domain":"mastodon.test","configuration":{"statuses":{"max_characters":500,"characters_reserved_per_url":23}}}""",
                MediaType.APPLICATION_JSON));
        assertThat(service.postingRules().images()).isEqualTo(new ImageRules(4, 16_777_216, 33_177_600L, 1500,
                List.of("image/jpeg", "image/png", "image/gif", "image/webp"), false, false, null, "instance"));
    }

    @Test
    void aFailedInstanceFetchFallsBackToTheDefaults() {
        server.expect(requestTo(BASE + "/api/v2/instance")).andRespond(withServerError());
        ImageRules rules = service.postingRules().images();
        assertThat(rules.maxBytes()).isEqualTo(16_777_216);
        assertThat(rules.source()).isEqualTo("fallback");
    }

}
