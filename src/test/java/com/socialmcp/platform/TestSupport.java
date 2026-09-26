package com.socialmcp.platform;

import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers for platform tests using {@code MockRestServiceServer}.
 */
public final class TestSupport {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private TestSupport() {
    }

    /**
     * Strictly encodes a URI variable the way {@code RestClient} does for template variables.
     */
    public static String enc(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * A captured request: its body parsed as JSON (or null) and its headers.
     */
    public record Captured(JsonNode body, HttpHeaders headers) {
    }

    /**
     * Records every request it sees, for asserting on bodies and headers afterwards.
     */
    public static final class Capture implements RequestMatcher {

        public final List<Captured> requests = new ArrayList<>();

        @Override
        public void match(org.springframework.http.client.ClientHttpRequest request) {
            MockClientHttpRequest mock = (MockClientHttpRequest) request;
            String body = mock.getBodyAsString();
            requests.add(new Captured(body.isEmpty() ? null : JSON.readTree(body), mock.getHeaders()));
        }

    }

    /**
     * A clock that tests can move forward.
     */
    public static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-09-25T12:00:00Z");

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

    }

}
