package com.socialmcp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformAwareToolDefinitionsTest {

	private static final BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> HANDLER = (ex, req) -> null;

	private static SyncToolSpecification platformTool() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("platform", Map.of("type", "string", "description", "placeholder"));
		properties.put("query", Map.of("type", "string", "description", "Search string."));
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("required", List.of("platform", "query"));
		Tool tool = new Tool("searchSocialPosts", "Search", "Search posts on {platforms} by keyword.", schema, null,
				null, null, null);
		return new SyncToolSpecification(tool, HANDLER);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> platformSchema(SyncToolSpecification spec) {
		Map<String, Object> properties = (Map<String, Object>) spec.tool().inputSchema().get("properties");
		return (Map<String, Object>) properties.get("platform");
	}

	@Test
	void singlePlatformIsTheOnlyAdvertisedValue() {
		SyncToolSpecification result = PlatformAwareToolDefinitions.rewrite(platformTool(), List.of("mastodon"));
		assertThat(result.tool().description()).isEqualTo("Search posts on Mastodon by keyword.");
		assertThat(platformSchema(result)).containsEntry("enum", List.of("mastodon"))
			.containsEntry("description", "Platform: one of \"mastodon\".")
			.containsEntry("type", "string");
	}

	@Test
	void bothPlatformsAreJoinedInOrder() {
		SyncToolSpecification result = PlatformAwareToolDefinitions.rewrite(platformTool(),
				List.of("mastodon", "bluesky"));
		assertThat(result.tool().description()).isEqualTo("Search posts on Mastodon or Bluesky by keyword.");
		assertThat(platformSchema(result)).containsEntry("enum", List.of("mastodon", "bluesky"))
			.containsEntry("description", "Platform: one of \"mastodon\", \"bluesky\".");
	}

	@Test
	void noPlatformMeansNoEnumAndAnExplanation() {
		SyncToolSpecification result = PlatformAwareToolDefinitions.rewrite(platformTool(), List.of());
		assertThat(result.tool().description())
			.isEqualTo("Search posts on a social platform (none is configured on this server) by keyword.");
		assertThat(platformSchema(result)).doesNotContainKey("enum")
			.containsEntry("description",
					"No platform is configured on this server; every call will fail until credentials are set.");
	}

	@Test
	void everythingElseIsPreserved() {
		SyncToolSpecification original = platformTool();
		SyncToolSpecification result = PlatformAwareToolDefinitions.rewrite(original, List.of("bluesky"));
		assertThat(result.callHandler()).isSameAs(HANDLER);
		assertThat(result.tool().name()).isEqualTo("searchSocialPosts");
		assertThat(result.tool().title()).isEqualTo("Search");
		assertThat(result.tool().inputSchema()).containsEntry("required", List.of("platform", "query"))
			.containsEntry("type", "object");
		@SuppressWarnings("unchecked")
		Map<String, Object> properties = (Map<String, Object>) result.tool().inputSchema().get("properties");
		assertThat(properties.get("query")).isEqualTo(Map.of("type", "string", "description", "Search string."));
		// the original definition is not mutated
		assertThat(platformSchema(original)).doesNotContainKey("enum");
		assertThat(original.tool().description()).contains("{platforms}");
	}

	@Test
	void toolsWithoutPlatformParameterAreUntouched() {
		Tool tool = new Tool("other", null, "Something on {platforms}.",
				Map.of("type", "object", "properties", Map.of("x", Map.of("type", "string"))), null, null, null, null);
		SyncToolSpecification spec = new SyncToolSpecification(tool, HANDLER);
		assertThat(PlatformAwareToolDefinitions.rewrite(spec, List.of("mastodon"))).isSameAs(spec);
	}

	@Test
	void displayListFormatsThreeOrMore() {
		assertThat(PlatformAwareToolDefinitions.displayList(List.of("mastodon", "bluesky", "pixelfed")))
			.isEqualTo("Mastodon, Bluesky or Pixelfed");
	}

}
