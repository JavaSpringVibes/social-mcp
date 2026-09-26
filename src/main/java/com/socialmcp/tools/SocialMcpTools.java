package com.socialmcp.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.media.ImageLoader;
import com.socialmcp.model.AccountAction;
import com.socialmcp.model.ImageCheck;
import com.socialmcp.model.ImageInput;
import com.socialmcp.model.ImageRules;
import com.socialmcp.model.NewPost;
import com.socialmcp.model.PartCheck;
import com.socialmcp.model.PollInput;
import com.socialmcp.model.PollRules;
import com.socialmcp.model.PostAction;
import com.socialmcp.model.PostActionResult;
import com.socialmcp.model.PostCheckResult;
import com.socialmcp.model.PostInteractions;
import com.socialmcp.model.PostResult;
import com.socialmcp.model.PostingRules;
import com.socialmcp.model.PreparedImage;
import com.socialmcp.model.ProfileResult;
import com.socialmcp.model.PublishedPost;
import com.socialmcp.model.QuoteTarget;
import com.socialmcp.model.RelationshipResult;
import com.socialmcp.model.ReplyResult;
import com.socialmcp.model.ReplyTarget;
import com.socialmcp.model.SearchSort;
import com.socialmcp.model.SimilarAccountsResult;
import com.socialmcp.model.ThreadResult;
import com.socialmcp.model.TimelineType;
import com.socialmcp.model.TrendsResult;
import com.socialmcp.model.VoteResult;
import com.socialmcp.platform.ApiErrors;
import com.socialmcp.platform.SocialPlatformService;
import com.socialmcp.text.TextLength;

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

	private static final String POST_DESC = "the post's id from a previous result, or its public URL.";

	private static final String POLL_DESC = """
			Optional poll (Mastodon only): {options: 2+ answers, expiresInMinutes (default 1440), multiple (default \
			false), hideTotals (default false)}. Limits are in getSocialPostingRules.polls.""";

	private static final String IMAGES_DESC = """
			Optional images, up to 4 (see getSocialPostingRules.images). Each item: {source: an absolute file path on \
			the user's computer or an https URL, altText: what the image shows, for people who can't see it}. Size and \
			format limits differ by platform and server, and the server never resizes. Before attaching, call \
			checkSocialPost with these images; resize or convert any it reports, check again, then post. Images \
			attached to the chat can't be posted: ask the user for the file's path on their computer. If you can't \
			resize an image, tell the user the fitWithin size, or suggest Claude Code.""";

	private static final String IMAGE_RULES = """
			Images: check them with checkSocialPost first and resize or convert any it reports with your own tools \
			(the server never resizes), write alt text that describes what each image shows (never invented), and \
			attach only images the user asked to post, because their contents are published.""";

	private final List<SocialPlatformService> platforms;

	private final SocialProperties properties;

	private final ImageLoader imageLoader;

	public SocialMcpTools(List<SocialPlatformService> platforms, SocialProperties properties, ImageLoader imageLoader) {
		this.platforms = platforms;
		this.properties = properties;
		this.imageLoader = imageLoader;
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
			markdown is not rendered. For content longer than the platform limit, use createSocialThread. Optionally \
			quote another post (quote = its id or URL), attach up to 4 images, or attach a poll (Mastodon only; check \
			the poll limits in getSocialPostingRules first). A post can have a quote or a poll, not both; images can't \
			go with a poll, and on Mastodon not with a quote either. With images, content may be empty.""" + " "
			+ IMAGE_RULES + " " + """
					Returns a confirmation with the post URL, plus any caveat in parentheses (e.g. a quote waiting for \
					the author's approval).""")
	public String createSocialPost(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = "Plain-text post content. May be empty when images are attached.",
					required = false) @Nullable String content,
			@McpToolParam(description = "Optional post to quote: " + POST_DESC, required = false) @Nullable String quote,
			@McpToolParam(description = POLL_DESC, required = false) @Nullable PollInput poll,
			@McpToolParam(description = IMAGES_DESC, required = false) @Nullable List<ImageInput> images) {
		requirePostingEnabled();
		SocialPlatformService service = platform(platform);
		List<ImageInput> imageList = images == null ? List.of() : images;
		boolean hasImages = !imageList.isEmpty();
		String text = content == null ? "" : content.trim();
		if (text.isEmpty() && !hasImages) {
			throw new IllegalArgumentException("content must not be blank");
		}
		if (!text.isEmpty()) {
			PartCheck check = call(service, () -> service.checkPart(1, text));
			if (!check.ok()) {
				throw new IllegalArgumentException("Content is " + check.reason() + " on " + service.platform()
						+ ". Split it into parts and use createSocialThread.");
			}
		}
		String quoteRef = quote == null || quote.isBlank() ? null : quote;
		if (quoteRef != null && poll != null) {
			throw new IllegalArgumentException("A post can have a quote or a poll, not both");
		}
		List<PreparedImage> prepared = List.of();
		if (hasImages) {
			ImageRules rules = call(service, service::postingRules).images();
			if (poll != null) {
				throw new IllegalArgumentException("A post can have images or a poll, not both");
			}
			if (quoteRef != null && !rules.withQuote()) {
				throw new IllegalArgumentException(capitalize(service.platform()) + " doesn't allow images in a quote post");
			}
			prepared = prepareImages(service, imageList, rules);
		}
		if (poll != null) {
			checkPoll(service, poll);
		}
		QuoteTarget target = quoteRef == null ? null : call(service, () -> service.quoteTarget(quoteRef));
		List<PreparedImage> attached = prepared;
		NewPost post = call(service, () -> service.createTopLevelPost(text, target, poll, attached));
		return "Posted to " + service.platform() + ": " + post.post().url()
				+ (post.caveat() == null ? "" : " (" + post.caveat() + ")");
	}

	@McpTool(name = "getSocialPostingRules", description = """
			Get the posting limits on {platforms} and how length is counted, to plan splitting long content into thread \
			parts. Do not count characters yourself: measure drafts with checkSocialPost. Also reports quote support, \
			poll limits and image limits; the image limits are for planning, and checkSocialPost checks actual image \
			files against them. Returns {platform, maxLength, unit, urlLength, maxBytes, maxThreadParts, \
			numberingFormat, numberingReserve, followUpVisibility, countingNotes, source, quotes, polls, images: \
			{maxImages, maxBytes, maxPixels, maxAltTextLength, mimeTypes, withQuote, withPoll, \
			recommendedMaxDimension, source}}.""")
	public PostingRules getSocialPostingRules(@McpToolParam(description = PLATFORM_DESC) String platform) {
		SocialPlatformService service = platform(platform);
		return call(service, service::postingRules);
	}

	@McpTool(name = "checkSocialPost", description = """
			Measure draft posts for {platforms} against the platform's limits, and check image files against its \
			image limits, without posting or uploading anything. Pass the parts of a planned thread in order (or one \
			item for a single post). With numbered=true (default) each part is measured with the " (n/N)" suffix \
			createSocialThread adds. Before attaching images to createSocialPost or replyToSocialPost, check them \
			here: each image gets its size, dimensions, problems and fitWithin (the size to resize to); resize or \
			convert any that are not ok, and check again. Returns {platform, valid, maxLength, unit, problems, parts: \
			[{index, text, length, bytes, ok, reason}], images: [{index, source, ok, mimeType, bytes, width, height, \
			problems, fitWithin}]}; fix only what is not ok.""")
	public PostCheckResult checkSocialPost(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = "Draft texts, in order. May be omitted when only checking images.",
					required = false) @Nullable List<String> parts,
			@McpToolParam(description = "Add \" (n/N)\" numbering to each part. Default true; ignored for one part.",
					required = false) @Nullable Boolean numbered,
			@McpToolParam(description = """
					Optional images one post would carry, in order: [{source: absolute file path or https URL, \
					altText}]. altText may be left out here; it is then reported as a problem.""",
					required = false) @Nullable List<ImageInput> images) {
		SocialPlatformService service = platform(platform);
		List<ImageInput> imageList = images == null ? List.of() : images;
		boolean hasImages = !imageList.isEmpty();
		PostCheckResult text = call(service,
				() -> check(service, parts, numbered, hasImages));
		if (!hasImages) {
			return text;
		}
		ImageRules rules = call(service, service::postingRules).images();
		List<String> problems = new ArrayList<>(text.problems());
		if (imageList.size() > rules.maxImages()) {
			problems.add(imageList.size() + " images, but the maximum on " + service.platform() + " is " + rules.maxImages());
		}
		List<ImageCheck> checks = new ArrayList<>();
		for (int i = 0; i < imageList.size(); i++) {
			ImageLoader.Inspection inspection = imageLoader.inspect(i + 1, imageList.get(i), rules, service.platform(),
					service.stripsImageMetadata());
			checks.add(inspection.check());
			inspection.errors().forEach(e -> problems.add(e.endsWith(".") ? e.substring(0, e.length() - 1) : e));
		}
		return new PostCheckResult(text.platform(), problems.isEmpty(), text.maxLength(), text.unit(),
				List.copyOf(problems), text.parts(), List.copyOf(checks));
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

	@McpTool(name = "setAccountRelationship", description = """
			Change the configured account's relationship with one account on {platforms}: follow, unfollow, block, \
			unblock, mute or unmute. Checks the current state first and changes nothing if it is already as asked, so \
			retrying is safe. Only act when the user asked for that action on that specific account; confirm the \
			right person first (e.g. getSocialProfile) when the handle was guessed or found by search. NEVER block on \
			your own judgement, and before blocking tell the user the side effects unless they already know them: on \
			Mastodon a block removes follows in both directions and unblocking does not restore them; on Bluesky \
			blocks are public. Suggest mute (private; the account isn't told) when the user only wants to stop seeing \
			someone. On Mastodon, following a locked or remote account may return "requested". Returns {platform, \
			action, status, account, note}; pass note on to the user.""")
	public RelationshipResult setAccountRelationship(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = HANDLE_DESC) String handle,
			@McpToolParam(description = """
					One of: follow, unfollow (also cancels a pending request), block, unblock, mute (hide their \
					posts, private), unmute.""") String action) {
		AccountAction accountAction = parseAction(action, AccountAction::parse,
				"follow, unfollow, block, unblock, mute, unmute");
		requireWritesEnabled(accountAction.gerund());
		SocialPlatformService service = platform(platform);
		String normalized = requireHandle(service, handle);
		return call(service, () -> service.setRelationship(normalized, accountAction));
	}

	@McpTool(name = "setPostAction", description = """
			Change the configured account's interaction with one post on {platforms}: like, unlike, repost, \
			unrepost, bookmark or unbookmark. Checks the post first and changes nothing if it is already as asked, so \
			retrying is safe. Only act when the user asked for that action on that specific post. Find post ids with \
			searchSocialPosts, getSocialTimeline, getSocialUserPosts, getSocialPostInteractions or \
			getSocialBookmarks. Likes and reposts are public and notify the author; bookmarks are private. There is \
			no dislike: unlike only removes the user's own like. Returns {platform, action, status, post}.""")
	public PostActionResult setPostAction(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = POST_DESC) String post,
			@McpToolParam(description = "One of: like, unlike, repost, unrepost, bookmark, unbookmark.") String action) {
		PostAction postAction = parseAction(action, PostAction::parse,
				"like, unlike, repost, unrepost, bookmark, unbookmark");
		requireWritesEnabled(postAction.gerund());
		SocialPlatformService service = platform(platform);
		String ref = requirePost(post);
		return call(service, () -> service.setPostAction(ref, postAction));
	}

	@McpTool(name = "replyToSocialPost", description = """
			Publish a plain-text reply, optionally with up to 4 images, to any post on {platforms} (other people's or \
			your own) as the configured account. Only reply when the user asked to, and show them the exact text \
			first unless they dictated it. On Mastodon the server adds "@author " in front so the author is notified; \
			it counts toward the limit, so include it when measuring drafts with checkSocialPost. With images, content \
			may be empty.""" + " " + IMAGE_RULES + " " + """
					A reply is one post: for more, reply again to your own reply. Returns {platform, url, inReplyTo, \
					text}.""")
	public ReplyResult replyToSocialPost(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = "The post to reply to: " + POST_DESC) String post,
			@McpToolParam(description = "Plain-text reply content. May be empty when images are attached.",
					required = false) @Nullable String content,
			@McpToolParam(description = IMAGES_DESC, required = false) @Nullable List<ImageInput> images) {
		requirePostingEnabled();
		SocialPlatformService service = platform(platform);
		String ref = requirePost(post);
		List<ImageInput> imageList = images == null ? List.of() : images;
		boolean hasImages = !imageList.isEmpty();
		String trimmed = content == null ? "" : content.trim();
		if (trimmed.isEmpty() && !hasImages) {
			throw new IllegalArgumentException("content must not be blank");
		}
		ReplyTarget target = call(service, () -> service.replyTarget(ref));
		String mention = target.mention();
		boolean addPrefix = mention != null && !mentions(trimmed, mention, target.inReplyTo().author());
		String text = (addPrefix ? "@" + mention + " " + trimmed : trimmed).trim();
		if (!text.isEmpty()) {
			PartCheck check = call(service, () -> service.checkPart(1, text));
			if (!check.ok()) {
				throw new IllegalArgumentException("Reply is " + check.reason() + " on " + service.platform()
						+ ". Shorten it; replies are single posts."
						+ (addPrefix ? " The automatic mention '@" + mention + " ' counts toward the limit." : ""));
			}
		}
		List<PreparedImage> prepared = hasImages
				? prepareImages(service, imageList, call(service, service::postingRules).images()) : List.of();
		PublishedPost published = call(service, () -> service.reply(target, text, prepared));
		return new ReplyResult(service.platform(), published.url(), target.inReplyTo(), text);
	}

	@McpTool(name = "getSocialBookmarks", description = """
			Read the configured account's bookmarked posts on {platforms}, most recently bookmarked first. Returns a \
			JSON array of posts {platform, id, author, text, createdAt, url, replyCount, repostCount, likeCount, \
			quote, poll}; pass an id to setPostAction (e.g. unbookmark) or replyToSocialPost.""")
	public List<PostResult> getSocialBookmarks(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = LIMIT_DESC, required = false) @Nullable Integer limit) {
		SocialPlatformService service = platform(platform);
		int n = limit(limit);
		return call(service, () -> service.getBookmarks(n));
	}

	@McpTool(name = "voteInSocialPoll", description = """
			Vote in the poll attached to a post on {platforms}, as the configured account. Polls exist on Mastodon \
			only. Read the post first (its poll lists numbered options) and vote only for what the user chose. A \
			vote can't be changed or withdrawn, and you can't vote in your own poll. Returns {platform, status, \
			post}; status is "voted" or "already-voted".""")
	public VoteResult voteInSocialPoll(
			@McpToolParam(description = PLATFORM_DESC) String platform,
			@McpToolParam(description = "The post carrying the poll: " + POST_DESC) String post,
			@McpToolParam(description = """
					The 1-based option numbers to vote for, from the poll's options: exactly one for a single-choice \
					poll, one or more for a multiple-choice poll.""") List<Integer> choices) {
		requireWritesEnabled("Voting");
		SocialPlatformService service = platform(platform);
		if (!service.supportsPolls()) {
			throw new IllegalArgumentException(capitalize(service.platform()) + " doesn't support polls");
		}
		String ref = requirePost(post);
		if (choices == null || choices.isEmpty() || choices.stream().anyMatch(c -> c == null || c < 1)
				|| choices.stream().distinct().count() != choices.size()) {
			throw new IllegalArgumentException("choices must be distinct option numbers starting at 1");
		}
		List<Integer> distinct = List.copyOf(choices);
		return call(service, () -> service.vote(ref, distinct));
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
		requireWritesEnabled("Posting");
	}

	/** The write kill switch (SPEC §6.3), e.g. {@code "Following is disabled"}. */
	private void requireWritesEnabled(String gerund) {
		if (!properties.postingEnabled()) {
			throw new IllegalStateException(gerund + " is disabled");
		}
	}

	/** Parses an {@code action} argument (SPEC §6.11) before any other check. */
	private static <A> A parseAction(@Nullable String action, Function<String, Optional<A>> parser, String allowed) {
		return (action == null ? Optional.<A>empty() : parser.apply(action))
			.orElseThrow(() -> new IllegalArgumentException("Unknown action '" + action + "'. Use one of: " + allowed));
	}

	/** A non-blank post reference; the platform validates its form before any HTTP call (SPEC §6.9). */
	private static String requirePost(@Nullable String post) {
		if (post == null || post.isBlank()) {
			throw new IllegalArgumentException("post must not be blank");
		}
		return post;
	}

	/**
	 * Whether {@code text} already mentions the author, as the bare acct or the fully qualified handle, ignoring case
	 * (SPEC §4, Tool 14).
	 */
	static boolean mentions(String text, String acct, String qualifiedAuthor) {
		String qualified = qualifiedAuthor.startsWith("@") ? qualifiedAuthor.substring(1) : qualifiedAuthor;
		return containsMention(text, acct) || containsMention(text, qualified);
	}

	private static boolean containsMention(String text, String acct) {
		return Pattern.compile("(?<![\\w@])@" + Pattern.quote(acct) + "(?![\\w@])", Pattern.CASE_INSENSITIVE)
			.matcher(text)
			.find();
	}

	/** Poll validation (SPEC §6.12), before any posting call. */
	private void checkPoll(SocialPlatformService service, PollInput poll) {
		String name = service.platform();
		PollRules rules = service.supportsPolls() ? call(service, service::postingRules).polls() : null;
		if (rules == null) {
			throw new IllegalArgumentException(capitalize(name) + " doesn't support polls");
		}
		List<String> options = poll.options() == null ? List.of()
				: poll.options().stream().map(o -> o == null ? "" : o.trim()).toList();
		if (options.size() < 2 || options.size() > rules.maxOptions()) {
			throw new IllegalArgumentException("A poll needs 2 to " + rules.maxOptions() + " options on " + name);
		}
		for (int i = 0; i < options.size(); i++) {
			String option = options.get(i);
			int length = TextLength.graphemes(option);
			if (option.isEmpty()) {
				throw new IllegalArgumentException("Poll option " + (i + 1) + " is blank");
			}
			if (length > rules.maxOptionLength()) {
				throw new IllegalArgumentException("Poll option " + (i + 1) + " is "
						+ TextLength.overReason(length, rules.maxOptionLength(), "graphemes"));
			}
		}
		if (new HashSet<>(options).size() != options.size()) {
			throw new IllegalArgumentException("Poll options must be different");
		}
		int minutes = poll.expiresInMinutesOrDefault();
		if (minutes < rules.minExpiresInMinutes() || minutes > rules.maxExpiresInMinutes()) {
			throw new IllegalArgumentException("A poll must last between " + rules.minExpiresInMinutes() + " minutes and "
					+ rules.maxExpiresInMinutes() + " minutes on " + name);
		}
	}

	private static String capitalize(String id) {
		return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
	}

	/** Applies trimming and numbering (SPEC §6.10), then measures every part. */
	private PostCheckResult check(SocialPlatformService service, @Nullable List<String> parts,
			@Nullable Boolean numbered) {
		return check(service, parts, numbered, false);
	}

	/** @param allowMissing whether a null {@code parts} means "no text to check" ({@code checkSocialPost} with images) */
	private PostCheckResult check(SocialPlatformService service, @Nullable List<String> parts,
			@Nullable Boolean numbered, boolean allowMissing) {
		if (parts == null && allowMissing) {
			PostingRules rules = service.postingRules();
			return new PostCheckResult(service.platform(), true, rules.maxLength(), rules.unit(), List.of(), List.of(),
					List.of());
		}
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
				List.copyOf(problems), List.copyOf(checks), List.of());
	}

	/**
	 * Loads and checks every image of a post before anything is uploaded (SPEC §6.14), throwing the first problem. The
	 * combination rules are the caller's.
	 */
	private List<PreparedImage> prepareImages(SocialPlatformService service, List<ImageInput> inputs, ImageRules rules) {
		if (inputs.size() > rules.maxImages()) {
			throw new IllegalArgumentException(
					"At most " + rules.maxImages() + " images per post on " + service.platform());
		}
		List<PreparedImage> prepared = new ArrayList<>();
		for (int i = 0; i < inputs.size(); i++) {
			ImageLoader.Inspection inspection = imageLoader.inspect(i + 1, inputs.get(i), rules, service.platform(),
					service.stripsImageMetadata());
			PreparedImage image = inspection.image();
			if (image == null) {
				throw new IllegalArgumentException(String.valueOf(inspection.error()));
			}
			prepared.add(image);
		}
		return List.copyOf(prepared);
	}

	private static <T> T call(SocialPlatformService service, Supplier<T> action) {
		return ApiErrors.translate(service.platform(), action);
	}

}
