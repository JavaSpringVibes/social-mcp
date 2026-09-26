package com.socialmcp.platform;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiErrorsTest {

    @Test
    void passesResultsThrough() {
        assertThat(ApiErrors.translate("mastodon", () -> "ok")).isEqualTo("ok");
    }

    @Test
    void httpErrorsBecomeStatusAndShortExcerpt() {
        String body = "{\"error\":\"" + "x".repeat(500) + "\"}";
        assertThatThrownBy(() -> ApiErrors.translate("mastodon", () -> {
            throw HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable", null,
                    body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("mastodon API error 422: {\"error\":\"xxx")
                .hasMessageEndingWith("…")
                .satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(260));
    }

    @Test
    void networkFailuresSayUnreachable() {
        assertThatThrownBy(() -> ApiErrors.translate("bluesky", () -> {
            throw new ResourceAccessException("I/O error", new ConnectException("Connection refused"));
        })).isInstanceOf(IllegalStateException.class).hasMessage("bluesky is unreachable: Connection refused");
    }

}
