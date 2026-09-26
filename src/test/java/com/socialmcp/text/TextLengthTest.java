package com.socialmcp.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextLengthTest {

    private static final String FAMILY = "👨‍👩‍👧‍👦";

    @Test
    void countsGraphemesNotCodeUnits() {
        assertThat(TextLength.graphemes("abc")).isEqualTo(3);
        assertThat(TextLength.graphemes("😀")).isEqualTo(1); // one emoji, two UTF-16 units
        assertThat(TextLength.graphemes(FAMILY)).isEqualTo(1);
        assertThat(TextLength.graphemes("é")).isEqualTo(1); // combining accent
    }

    @Test
    void familyEmojiIs25Utf8Bytes() {
        assertThat(TextLength.utf8Bytes(FAMILY)).isEqualTo(25);
        assertThat(TextLength.utf8Bytes("😀")).isEqualTo(4);
    }

    @Test
    void mastodonCountsEachSchemeUrlAs23() {
        String text = "a".repeat(476) + " https://example.com/" + "p".repeat(80);
        assertThat(TextLength.mastodon(text, 23)).isEqualTo(500);
        assertThat(TextLength.mastodon("a" + text, 23)).isEqualTo(501);
    }

    @Test
    void mastodonDoesNotShortenUrlsWithoutScheme() {
        String url = "example.com/very/long/path";
        assertThat(TextLength.mastodon(url, 23)).isEqualTo(url.length());
    }

    @Test
    void mastodonCountsRemoteMentionAsLocalPart() {
        assertThat(TextLength.mastodon("@bob@example.social", 23)).isEqualTo(4);
        assertThat(TextLength.mastodon("hi @bob", 23)).isEqualTo(7);
    }

    @Test
    void mastodonCountsGraphemes() {
        assertThat(TextLength.mastodon(FAMILY.repeat(500), 23)).isEqualTo(500);
    }

    @Test
    void overReasonFormat() {
        assertThat(TextLength.overReason(327, 300, "graphemes")).isEqualTo("327/300 graphemes (27 over)");
    }

}
