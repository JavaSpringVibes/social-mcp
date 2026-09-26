package com.socialmcp;

import com.socialmcp.tools.SocialMcpTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SocialMcpServerApplicationTests {

    @Autowired
    private SocialMcpTools tools;

    @Test
    void contextStartsWithoutCredentialsAndToolsReportNotConfigured() {
        assertThatThrownBy(() -> tools.getSocialTimeline("mastodon", "home", null))
                .hasMessage("Platform mastodon is not configured");
        assertThatThrownBy(() -> tools.getSocialPostingRules("bluesky"))
                .hasMessage("Platform bluesky is not configured");
    }

}
