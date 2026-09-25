package com.socialmcp.tools;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The real Spring AI tool definitions, as built at startup for different credential setups. */
class PlatformAwareToolDefinitionsContextTest {

	static List<SyncToolSpecification> toolSpecs(ApplicationContext context) {
		return context.getBeansOfType(List.class)
			.values()
			.stream()
			.filter(list -> !list.isEmpty() && list.get(0) instanceof SyncToolSpecification)
			.flatMap(list -> ((List<?>) list).stream())
			.map(SyncToolSpecification.class::cast)
			.toList();
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> platformSchema(Tool tool) {
		Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
		return (Map<String, Object>) properties.get("platform");
	}

	static void assertNoPlaceholderLeft(List<SyncToolSpecification> specs) {
		assertThat(specs).allSatisfy(spec -> {
			assertThat(spec.tool().description()).doesNotContain("{platforms}");
			assertThat(spec.tool().inputSchema().toString()).doesNotContain("{platforms}");
		});
	}

	@Nested
	@SpringBootTest(properties = "social.mastodon.access-token=test-token")
	class MastodonOnly {

		@Autowired
		ApplicationContext context;

		@Autowired
		SocialMcpTools tools;

		@Test
		void advertisesOnlyMastodon() {
			List<SyncToolSpecification> specs = toolSpecs(context);
			assertThat(specs).hasSize(16);
			assertNoPlaceholderLeft(specs);
			assertThat(specs).allSatisfy(spec -> {
				assertThat(platformSchema(spec.tool())).containsEntry("enum", List.of("mastodon"))
					.containsEntry("description", "Platform: one of \"mastodon\".");
				assertThat(spec.tool().inputSchema().get("required").toString()).contains("platform");
			});
			Tool search = specs.stream().filter(s -> s.tool().name().equals("searchSocialPosts")).findFirst().orElseThrow().tool();
			assertThat(search.description()).startsWith("Search posts on Mastodon by keyword");
		}

		@Test
		void blueskyIsStillRejectedAtRuntime() {
			assertThatThrownBy(() -> tools.getSocialTrends("bluesky", null))
				.hasMessage("Platform bluesky is not configured");
		}

	}

	@Nested
	@SpringBootTest(properties = { "social.mastodon.access-token=test-token", "social.bluesky.handle=me.bsky.social",
			"social.bluesky.app-password=pw" })
	class BothPlatforms {

		@Autowired
		ApplicationContext context;

		@Test
		void advertisesBothInOrder() {
			List<SyncToolSpecification> specs = toolSpecs(context);
			assertNoPlaceholderLeft(specs);
			assertThat(specs).allSatisfy(spec -> assertThat(platformSchema(spec.tool())).containsEntry("enum",
					List.of("mastodon", "bluesky")));
			assertThat(specs).anySatisfy(spec -> assertThat(spec.tool().description())
				.startsWith("Publish a self-thread on Mastodon or Bluesky"));
		}

	}

	@Nested
	@SpringBootTest(properties = { "social.bluesky.handle=me.bsky.social", "social.bluesky.app-password=pw" })
	class BlueskyOnly {

		@Autowired
		ApplicationContext context;

		@Test
		void advertisesOnlyBluesky() {
			assertThat(toolSpecs(context)).allSatisfy(
					spec -> assertThat(platformSchema(spec.tool())).containsEntry("enum", List.of("bluesky")));
		}

	}

	@Nested
	@SpringBootTest
	class NoPlatform {

		@Autowired
		ApplicationContext context;

		@Test
		void advertisesNoneWithoutEnum() {
			List<SyncToolSpecification> specs = toolSpecs(context);
			assertThat(specs).hasSize(16);
			assertNoPlaceholderLeft(specs);
			assertThat(specs).allSatisfy(spec -> {
				assertThat(platformSchema(spec.tool())).doesNotContainKey("enum");
				assertThat(spec.tool().description()).contains("none is configured on this server");
			});
		}

	}

}
