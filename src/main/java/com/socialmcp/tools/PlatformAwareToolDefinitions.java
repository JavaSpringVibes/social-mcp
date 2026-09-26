package com.socialmcp.tools;

import com.socialmcp.platform.SocialPlatformService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Makes the tool definitions advertise only the configured platforms (SPEC §4, "Platform-aware tool definitions").
 *
 * <p>{@code @McpTool} descriptions are compile-time constants, so this post-processes the
 * {@code List<SyncToolSpecification>} bean Spring AI builds from them. For every tool with a {@code platform}
 * parameter, it fills in the {@value #PLACEHOLDER} placeholder and restricts the parameter with an {@code enum}.
 */
@Component
public class PlatformAwareToolDefinitions implements BeanPostProcessor {

    static final String PLACEHOLDER = "{platforms}";

    static final String PLATFORM_PARAM = "platform";

    /**
     * Display order for known platforms; others follow alphabetically.
     */
    private static final List<String> ORDER = List.of("mastodon", "bluesky");

    private static final Logger log = LoggerFactory.getLogger(PlatformAwareToolDefinitions.class);

    private final ObjectProvider<SocialPlatformService> platforms;

    public PlatformAwareToolDefinitions(ObjectProvider<SocialPlatformService> platforms) {
        this.platforms = platforms;
    }

    /**
     * Rewrites one tool definition; tools without a {@code platform} parameter are returned unchanged.
     */
    static SyncToolSpecification rewrite(SyncToolSpecification spec, List<String> configured) {
        Tool tool = spec.tool();
        if (!(tool.inputSchema() != null && tool.inputSchema().get("properties") instanceof Map<?, ?> properties
                && properties.get(PLATFORM_PARAM) instanceof Map<?, ?> platformSchema)) {
            return spec;
        }
        Map<String, Object> newPlatformSchema = copy(platformSchema);
        newPlatformSchema.put("description", parameterDescription(configured));
        if (configured.isEmpty()) {
            newPlatformSchema.remove("enum");
        } else {
            newPlatformSchema.put("enum", List.copyOf(configured));
        }
        Map<String, Object> newProperties = copy(properties);
        newProperties.put(PLATFORM_PARAM, newPlatformSchema);
        Map<String, Object> newInputSchema = new LinkedHashMap<>(tool.inputSchema());
        newInputSchema.put("properties", newProperties);

        String description = tool.description() == null ? null
                : tool.description().replace(PLACEHOLDER, displayList(configured));
        Tool rewritten = new Tool(tool.name(), tool.title(), description, newInputSchema, tool.outputSchema(),
                tool.annotations(), tool.meta(), tool.icons());
        return new SyncToolSpecification(rewritten, spec.callHandler());
    }

    static String displayList(List<String> configured) {
        if (configured.isEmpty()) {
            return "a social platform (none is configured on this server)";
        }
        List<String> names = configured.stream().map(PlatformAwareToolDefinitions::displayName).toList();
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.get(names.size() - 1);
    }

    static String parameterDescription(List<String> configured) {
        if (configured.isEmpty()) {
            return "No platform is configured on this server; every call will fail until credentials are set.";
        }
        List<String> quoted = new ArrayList<>();
        configured.forEach(id -> quoted.add("\"" + id + "\""));
        return "Platform: one of " + String.join(", ", quoted) + ".";
    }

    private static String displayName(String id) {
        return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    private static Map<String, Object> copy(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof List<?> list && !list.isEmpty()
                && list.stream().allMatch(SyncToolSpecification.class::isInstance)) {
            List<String> configured = configuredPlatforms();
            log.info("Advertising configured platforms {} in tool definitions ({})", configured, beanName);
            return list.stream().map(spec -> rewrite((SyncToolSpecification) spec, configured)).toList();
        }
        return bean;
    }

    private List<String> configuredPlatforms() {
        return platforms.stream()
                .filter(SocialPlatformService::isConfigured)
                .map(SocialPlatformService::platform)
                .sorted(Comparator.comparingInt((String id) -> ORDER.contains(id) ? ORDER.indexOf(id) : ORDER.size())
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

}
