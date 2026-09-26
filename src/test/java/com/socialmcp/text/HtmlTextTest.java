package com.socialmcp.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlTextTest {

    @Test
    void convertsParagraphsAndBreaksAndStripsTags() {
        String html = "<p>Hello <a href=\"https://x\"><span class=\"invisible\">https://</span>x</a></p><p>line1<br>line2<br/>end</p>";
        assertThat(HtmlText.toPlainText(html)).isEqualTo("Hello https://x\n\nline1\nline2\nend");
    }

    @Test
    void decodesNamedAndNumericEntities() {
        assertThat(HtmlText.toPlainText("<p>a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&#39; &#x27;f&#x27; g&nbsp;h</p>"))
                .isEqualTo("a & b <c> \"d\" 'e' 'f' g h");
    }

    @Test
    void stripsQuoteInlineFallbackOnlyWhenAsked() {
        String html = "<p class=\"quote-inline\">RE: <a href=\"https://m/@b/1\">https://m/@b/1</a></p><p>My take</p>";
        assertThat(HtmlText.toPlainText(html, true)).isEqualTo("My take");
        assertThat(HtmlText.toPlainText(html, false)).isEqualTo("RE: https://m/@b/1\n\nMy take");
    }

    @Test
    void leavesUnknownEntitiesAndHandlesEmpty() {
        assertThat(HtmlText.toPlainText("&bogus; ok")).isEqualTo("&bogus; ok");
        assertThat(HtmlText.toPlainText(null)).isEmpty();
        assertThat(HtmlText.toPlainText("")).isEmpty();
    }

}
