package com.socialmcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.model.PartCheck;
import com.socialmcp.model.PostCheckResult;
import com.socialmcp.model.PostInteractions;
import com.socialmcp.model.PostResult;
import com.socialmcp.model.PostingRules;
import com.socialmcp.model.ProfileResult;
import com.socialmcp.model.PublishedPost;
import com.socialmcp.model.SearchSort;
import com.socialmcp.model.SimilarAccountsResult;
import com.socialmcp.model.ThreadResult;
import com.socialmcp.model.TimelineType;
import com.socialmcp.model.TrendsResult;
import com.socialmcp.platform.ApiErrors;
import com.socialmcp.platform.SocialPlatformService;

/**
 * The MCP tools (SPEC §4). Validates and normalizes arguments, applies thread numbering, then delegates to the
 * matching {@link SocialPlatformService}. Exceptions propagate so Spring AI returns them as {@code isError} results.
 */
@Component
public class SocialMcpTools {

	private static final String PLATFORM_DESC = "Platform to use. The allowed values are the configured platforms; "
			+ "this description and an enum are filled in at startup.";

	private static final String LIMIT_DESC = "Maximum items per returned list. Default 10; values above the server "
			+ "cap (40 by default, lower for some endpoints) are clamped, so ask for up to 40 when you need more context.";

	private static final String HANDLE_DESC = "User handle, with or without a leading @. Examples: "
			+ "user@mastodon.social (Mastodon), alice.bsky.social (Bluesky).";

	private final List<SocialPlatformService> platforms;

	private final SocialProperties properties;

	public SocialMcpTools(List<SocialPlatformService> platforms, SocialProperties properties) {
		this.platforms = platforms;
		this.properties = properties;
	}

	@McpTool(name = "searchSocialPosts", description = """
			Search posts on {platforms} by keyword or hashtag. sort="latest" (default) returns newest first; \
			sort="top" returns highest engagement first. Returns a JSON array of posts \
			{platform, id, author, text, createdAt, url, replyCount, repostCount, likeCount}.""")
	public List<PostResult> searchSocialPosts(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = "Search string, e.g. a keyword or a single #hashtag.") String query,
			@McpToolParam(description = "\"latest\" (default) or \"top\".", required = false) @Nullable String sort,
			@McpToolParam(description = LIMIT_DESC, required = false) @Nullable Integer limit) {
		SocialPlatformService service = platform(platform);
		if (query == null || query.isBlank()) {
			throw new IllegalArgumentException("query must not be blank");
		}
		SearchSort searchSort = parseSort(sort);
		int n = limit(limit);
		return call(service, () -> service.searchPosts(query, searchSort, n));
	}

	@McpTool(name = "getSocialTimeline", description = """
			Read the configured user's timeline on {platforms}, newest first. type="home" is the home feed \
			(posts from accounts they follow; boosts/reposts shown as the original post). type="own" is only posts \
			the user wrote (no replies, no reposts), so limit=1 returns their latest post. Returns a JSON array of \
			posts {platform, id, author, text, createdAt, url, replyCount, repostCount, likeCount}.""")
	public List<PostResult> getSocialTimeline(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = "\"home\" or \"own\".") String type,
			@McpToolParam(description = LIMIT_DESC, required = false) @Nullable Integer limit) {
		SocialPlatformService service = platform(platform);
		TimelineType timelineType = parseTimelineType(type);
		int n = limit(limit);
		return call(service, () -> service.getTimeline(timelineType, n));
	}

	@McpTool(name = "getSocialUserPosts", description = """
			Read the most recent public posts written by a user on {platforms}, newest first (no replies, no \
			reposts). Returns a JSON array of posts {platform, id, author, text, createdAt, url, replyCount, \
			repostCount, likeCount}.""")
	public List<PostResult> getSocialUserPosts(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = HANDLE_DESC) String handle,
			@McpToolParam(description = LIMIT_DESC, required = false) @Nullable Integer limit) {
		SocialPlatformService service = platform(platform);
		String normalized = requireHandle(service, handle);
		int n = limit(limit);
		return call(service, () -> service.getUserPosts(normalized, n));
	}

	@McpTool(name = "getSocialProfile", description = """
			Get a profile summary on {platforms}: bio plus follower, following and post counts. Omit handle \
			for the configured account's own profile. Returns {platform, id, handle, displayName, bio, \
			followersCount, followingCount, postsCount, isPrivate, createdAt, url}.""")
	public ProfileResult getSocialProfile(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = HANDLE_DESC + " Omit for your own profile.", required = false) @Nullable String handle) {
		SocialPlatformService service = platform(platform);
		String normalized = handle == null || handle.isBlank() ? null : requireHandle(service, handle);
		return call(service, () -> service.getProfile(normalized));
	}

	@McpTool(name = "getSocialPostInteractions", description = """
			Show how people interacted with a post on {platforms}: the post with its reply/repost/like \
			counts, its direct replies, and the accounts that liked and reposted it. Works on any visible post. For \
			"my latest post", first call getSocialTimeline with type="own" and limit=1. Returns {post, replies, \
			likedBy, repostedBy}; limit applies to each list, and the totals are in post's counts.""")
	public PostInteractions getSocialPostInteractions(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = "The post's id from a previous result, or its public URL.") String post,
			@McpToolParam(description = LIMIT_DESC, required = false) @Nullable Integer limit) {
		SocialPlatformService service = platform(platform);
		if (post == null || post.isBlank()) {
			throw new IllegalArgumentException("post must not be blank");
		}
		int n = limit(limit);
		return call(service, () -> service.getPostInteractions(post, n));
	}

	@McpTool(name = "getSocialTrends", description = """
			Get what is trending on {platforms}. On Mastodon: the configured instance's view, with tags and their \
			7-day use counts plus trending posts. On Bluesky: trending topics, no trending posts. For \
			"what is trending about a topic", \
			combine with searchSocialPosts using sort="top". Returns {platform, tags: [{name, url, recentUses}], \
			posts, notes}; pass notes on to the user.""")
	public TrendsResult getSocialTrends(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = LIMIT_DESC, required = false) @Nullable Integer limit) {
		SocialPlatformService service = platform(platform);
		int n = limit(limit);
		return call(service, () -> service.getTrends(n));
	}

	@McpTool(name = "findSimilarAccounts", description = """
			Find accounts on {platforms} that post content similar to a user. Bluesky uses the platform's own suggestions; \
			Mastodon uses a heuristic based on the user's top hashtags. Returns {platform, method, basedOn, \
			accounts: [{platform, id, handle, displayName, bio, url}]}.""")
	public SimilarAccountsResult findSimilarAccounts(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = HANDLE_DESC) String handle,
			@McpToolParam(description = LIMIT_DESC, required = false) @Nullable Integer limit) {
		SocialPlatformService service = platform(platform);
		String normalized = requireHandle(service, handle);
		int n = limit(limit);
		return call(service, () -> service.findSimilarAccounts(normalized, n));
	}

	@McpTool(name = "createSocialPost", description = """
			Publish a single public text post on {platforms} as the configured account. Plain text only; \
			markdown is not rendered. For content longer than the platform limit, use createSocialThread. Returns a \
			confirmation with the post URL.""")
	public String createSocialPost(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = "Plain-text post content.") String content) {
		requirePostingEnabled();
		SocialPlatformService service = platform(platform);
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content must not be blank");
		}
		String text = content.trim();
		PartCheck check = call(service, () -> service.checkPart(1, text));
		if (!check.ok()) {
			throw new IllegalArgumentException("Content is " + check.reason() + " on " + service.platform()
					+ ". Split it into parts and use createSocialThread.");
		}
		PublishedPost post = call(service, () -> service.createPost(text, null, null));
		return "Posted to " + service.platform() + ": " + post.url();
	}

	@McpTool(name = "getSocialPostingRules", description = """
			Get the posting limits on {platforms} and how length is counted, to plan splitting long content into thread \
			parts. Do not count characters yourself: measure drafts with checkSocialPost. Returns {platform, \
			maxLength, unit, urlLength, maxBytes, maxThreadParts, numberingFormat, numberingReserve, \
			followUpVisibility, countingNotes, source}.""")
	public PostingRules getSocialPostingRules(@McpToolParam(description = PLATFORM_DESC) String platform) {
		SocialPlatformService service = platform(platform);
		return call(service, service::postingRules);
	}

	@McpTool(name = "checkSocialPost", description = """
			Measure draft posts for {platforms} against the platform's limits without posting anything. Pass the parts of a planned \
			thread in order (or one item for a single post). With numbered=true (default) each part is measured with \
			the " (n/N)" suffix createSocialThread adds. Returns {platform, valid, maxLength, unit, problems, parts: \
			[{index, text, length, bytes, ok, reason}]}; rewrite only the parts that are not ok.""")
	public PostCheckResult checkSocialPost(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = "Draft texts, in order.") List<String> parts,
			@McpToolParam(description = "Add \" (n/N)\" numbering to each part. Default true; ignored for one part.",
					required = false) @Nullable Boolean numbered) {
		SocialPlatformService service = platform(platform);
		return call(service, () -> check(service, parts, numbered));
	}

	@McpTool(name = "createSocialThread", description = """
			Publish a self-thread on {platforms}: part 1 is a public post and each later part replies to the \
			previous one. Every part is checked first, so an over-long thread is rejected with nothing posted. On \
			Mastodon, parts after the first are unlisted so they don't clutter followers' timelines. Returns \
			{platform, partsPosted, urls}; urls[0] is the link to share.""")
	public ThreadResult createSocialThread(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = "Thread parts, in order.") List<String> parts,
			@McpToolParam(description = "Add \" (n/N)\" numbering to each part. Default true; ignored for one part.",
					required = false) @Nullable Boolean numbered) {
		requirePostingEnabled();
		SocialPlatformService service = platform(platform);
		PostCheckResult check = call(service, () -> check(service, parts, numbered));
		if (!check.valid()) {
			throw new IllegalArgumentException(
					"Thread rejected, nothing was posted: " + String.join("; ", check.problems()) + ".");
		}
		List<String> urls = new ArrayList<>();
		PublishedPost root = null;
		PublishedPost parent = null;
		for (PartCheck part : check.parts()) {
			PublishedPost published;
			try {
				PublishedPost r = root;
				PublishedPost p = parent;
				published = call(service, () -> service.createPost(part.text(), r, p));
			}
			catch (RuntimeException ex) {
				if (urls.isEmpty()) {
					throw ex;
				}
				throw new IllegalStateException("Posted " + urls.size() + " of " + check.parts().size() + " parts: "
						+ String.join(", ", urls) + ". Part " + part.index() + " failed: " + ex.getMessage()
						+ ". Already-posted parts were not deleted.", ex);
			}
			urls.add(published.url());
			if (root == null) {
				root = published;
			}
			parent = published;
		}
		return new ThreadResult(service.platform(), urls.size(), List.copyOf(urls));
	}

	// --- Argument handling (SPEC §6) ---

	SocialPlatformService platform(@Nullable String platform) {
		String id = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
		SocialPlatformService service = platforms.stream()
			.filter(p -> p.platform().equals(id))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
					"Unknown platform '" + platform + "'. Use one of: mastodon, bluesky"));
		if (!service.isConfigured()) {
			throw new IllegalArgumentException("Platform " + service.platform() + " is not configured");
		}
		return service;
	}

	int limit(@Nullable Integer limit) {
		if (limit == null) {
			return properties.read().defaultLimit();
		}
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be at least 1");
		}
		return Math.min(limit, properties.read().maxLimit());
	}

	private static String requireHandle(SocialPlatformService service, @Nullable String handle) {
		String normalized = handle == null ? "" : handle.trim();
		if (normalized.startsWith("@")) {
			normalized = normalized.substring(1);
		}
		if (normalized.isEmpty() || !service.isValidHandle(normalized)) {
			throw new IllegalArgumentException("Invalid " + service.platform() + " handle '" + handle + "'");
		}
		return normalized;
	}

	private static SearchSort parseSort(@Nullable String sort) {
		if (sort == null || sort.isBlank()) {
			return SearchSort.LATEST;
		}
		return switch (sort.trim().toLowerCase(Locale.ROOT)) {
			case "latest" -> SearchSort.LATEST;
			case "top" -> SearchSort.TOP;
			default -> throw new IllegalArgumentException("Unknown sort '" + sort + "'. Use one of: latest, top");
		};
	}

	private static TimelineType parseTimelineType(@Nullable String type) {
		String value = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
		return switch (value) {
			case "home" -> TimelineType.HOME;
			case "own" -> TimelineType.OWN;
			default -> throw new IllegalArgumentException(
					"Unknown timeline type '" + type + "'. Use one of: home, own");
		};
	}

	private void requirePostingEnabled() {
		if (!properties.postingEnabled()) {
			throw new IllegalStateException("Posting is disabled");
		}
	}

	/** Applies trimming and numbering (SPEC §6.10), then measures every part. */
	private PostCheckResult check(SocialPlatformService service, @Nullable List<String> parts,
			@Nullable Boolean numbered) {
		if (parts == null || parts.isEmpty()) {
			throw new IllegalArgumentException("parts must contain at least one item");
		}
		int total = parts.size();
		boolean addNumbers = (numbered == null || numbered) && total > 1;
		List<PartCheck> checks = new ArrayList<>();
		List<String> problems = new ArrayList<>();
		for (int i = 0; i < total; i++) {
			String text = parts.get(i) == null ? "" : parts.get(i).trim();
			if (addNumbers && !text.isEmpty()) {
				text = text + " (" + (i + 1) + "/" + total + ")";
			}
			PartCheck check = service.checkPart(i + 1, text);
			checks.add(check);
			if (!check.ok()) {
				problems.add("Part " + check.index() + " is " + check.reason());
			}
		}
		int maxParts = properties.thread().maxParts();
		if (total > maxParts) {
			problems.add(total + " parts, but the maximum is " + maxParts);
		}
		PostingRules rules = service.postingRules();
		return new PostCheckResult(service.platform(), problems.isEmpty(), rules.maxLength(), rules.unit(),
				List.copyOf(problems), List.copyOf(checks));
	}

	private static <T> T call(SocialPlatformService service, Supplier<T> action) {
		return ApiErrors.translate(service.platform(), action);
	}

}
