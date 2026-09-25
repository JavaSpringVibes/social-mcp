package com.socialmcp.platform.bluesky;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.model.AccountAction;
import com.socialmcp.model.AccountSummary;
import com.socialmcp.model.NewPost;
import com.socialmcp.model.PartCheck;
import com.socialmcp.model.PollInput;
import com.socialmcp.model.PostAction;
import com.socialmcp.model.PostActionResult;
import com.socialmcp.model.PostInteractions;
import com.socialmcp.model.PostResult;
import com.socialmcp.model.PostingRules;
import com.socialmcp.model.ProfileResult;
import com.socialmcp.model.PublishedPost;
import com.socialmcp.model.QuoteSummary;
import com.socialmcp.model.QuoteTarget;
import com.socialmcp.model.RelationshipResult;
import com.socialmcp.model.ReplyTarget;
import com.socialmcp.model.SearchSort;
import com.socialmcp.model.SimilarAccountsResult;
import com.socialmcp.model.TimelineType;
import com.socialmcp.model.TrendTag;
import com.socialmcp.model.TrendsResult;
import com.socialmcp.model.VoteResult;
import com.socialmcp.platform.Json;
import com.socialmcp.platform.SocialPlatformService;
import com.socialmcp.text.TextLength;

/** Bluesky via AT Protocol XRPC through the account's PDS (SPEC §5, Bluesky). */
@Service
public class BlueskyService implements SocialPlatformService {

	static final String PLATFORM = "bluesky";

	private static final int PAGE_MAX = 100;

	private static final int TRENDS_MAX = 25;

	private static final int MAX_GRAPHEMES = 300;

	private static final int MAX_BYTES = 3000;

	private static final String REASON_REPOST = "app.bsky.feed.defs#reasonRepost";

	private static final String THREAD_VIEW_POST = "app.bsky.feed.defs#threadViewPost";

	private static final String POST_VIEW = "app.bsky.feed.defs#postView";

	private static final String EMBED_RECORD_VIEW = "app.bsky.embed.record#view";

	private static final String EMBED_RECORD_WITH_MEDIA_VIEW = "app.bsky.embed.recordWithMedia#view";

	private static final String NO_POLLS = "Bluesky doesn't support polls";

	private static final String FOLLOW_COLLECTION = "app.bsky.graph.follow";

	private static final String BLOCK_COLLECTION = "app.bsky.graph.block";

	private static final String LIKE_COLLECTION = "app.bsky.feed.like";

	private static final String REPOST_COLLECTION = "app.bsky.feed.repost";

	private static final Pattern HANDLE = Pattern
		.compile("^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,}$");

	private static final Pattern AT_URI = Pattern.compile("^at://([^/]+)/app\\.bsky\\.feed\\.post/([^/?#]+)$");

	private static final Pattern WEB_URL = Pattern
		.compile("^https://bsky\\.app/profile/([^/]+)/post/([^/?#]+)/?$");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final SocialProperties properties;

	private final RestClient http;

	private final Clock clock;

	/** The cached login; null until the first authenticated call. */
	private @Nullable Session session;

	record Session(String accessJwt, String refreshJwt, String did, String handle) {
	}

	@Autowired
	public BlueskyService(SocialProperties properties, RestClient.Builder builder) {
		this(properties, builder, Clock.systemUTC());
	}

	BlueskyService(SocialProperties properties, RestClient.Builder builder, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		this.http = builder.baseUrl(properties.bluesky().pdsUrl() + "/xrpc").build();
	}

	@Override
	public String platform() {
		return PLATFORM;
	}

	@Override
	public boolean isConfigured() {
		return properties.bluesky().isConfigured();
	}

	@Override
	public boolean isValidHandle(String handle) {
		return handle.startsWith("did:") || HANDLE.matcher(handle).matches();
	}

	@Override
	public boolean supportsPolls() {
		return false;
	}

	// --- Search, timelines, user posts ---

	@Override
	public List<PostResult> searchPosts(String query, SearchSort sort, int limit) {
		JsonNode body = get("/app.bsky.feed.searchPosts?q={q}&limit={limit}&sort={sort}", query.trim(),
				Math.min(limit, PAGE_MAX), sort.name().toLowerCase());
		return Json.array(body, "posts").stream().map(this::toPost).toList();
	}

	@Override
	public List<PostResult> getTimeline(TimelineType type, int limit) {
		int n = Math.min(limit, PAGE_MAX);
		return switch (type) {
			case HOME -> Json.array(get("/app.bsky.feed.getTimeline?limit={limit}", n), "feed")
				.stream()
				.map(item -> toPost(item.get("post")))
				.toList();
			case OWN -> authoredFeed(session().did(), n, null);
		};
	}

	@Override
	public List<PostResult> getUserPosts(String handle, int limit) {
		return authoredFeed(handle, Math.min(limit, PAGE_MAX), handle);
	}

	/** Posts the actor wrote: replies are filtered by the API, reposts are skipped here (the API has no filter). */
	private List<PostResult> authoredFeed(String actor, int limit, @Nullable String handleForErrors) {
		JsonNode body;
		try {
			body = get("/app.bsky.feed.getAuthorFeed?actor={actor}&limit={limit}&filter=posts_no_replies", actor, limit);
		}
		catch (HttpClientErrorException ex) {
			throw handleForErrors == null ? ex : actorError(ex, handleForErrors);
		}
		return Json.array(body, "feed")
			.stream()
			.filter(item -> !REASON_REPOST.equals(Json.text(item.get("reason"), "$type")))
			.map(item -> toPost(item.get("post")))
			.toList();
	}

	// --- Profiles ---

	@Override
	public ProfileResult getProfile(@Nullable String handle) {
		JsonNode profile;
		try {
			profile = get("/app.bsky.actor.getProfile?actor={actor}", handle != null ? handle : session().did());
		}
		catch (HttpClientErrorException ex) {
			throw handle == null ? ex : actorError(ex, handle);
		}
		AccountSummary summary = toAccount(profile);
		return new ProfileResult(PLATFORM, summary.id(), summary.handle(), summary.displayName(), summary.bio(),
				Json.number(profile, "followersCount"), Json.number(profile, "followsCount"),
				Json.number(profile, "postsCount"), false, Json.isoUtc(Json.text(profile, "createdAt")), summary.url());
	}

	// --- Post interactions ---

	@Override
	public PostInteractions getPostInteractions(String postRef, int limit) {
		String uri = resolvePostUri(postRef);
		JsonNode thread;
		try {
			thread = get("/app.bsky.feed.getPostThread?uri={uri}&depth=1&parentHeight=0", uri).path("thread");
		}
		catch (HttpClientErrorException ex) {
			if ("NotFound".equals(errorName(ex))) {
				throw postNotFound(postRef);
			}
			throw ex;
		}
		if (!THREAD_VIEW_POST.equals(Json.text(thread, "$type"))) {
			throw postNotFound(postRef);
		}
		int n = Math.min(limit, PAGE_MAX);
		List<PostResult> replies = Json.array(thread, "replies")
			.stream()
			.filter(r -> THREAD_VIEW_POST.equals(Json.text(r, "$type")))
			.limit(n)
			.map(r -> toPost(r.get("post")))
			.toList();
		List<AccountSummary> likedBy = Json.array(get("/app.bsky.feed.getLikes?uri={uri}&limit={limit}", uri, n), "likes")
			.stream()
			.map(like -> toAccount(like.get("actor")))
			.toList();
		List<AccountSummary> repostedBy = Json
			.array(get("/app.bsky.feed.getRepostedBy?uri={uri}&limit={limit}", uri, n), "repostedBy")
			.stream()
			.map(this::toAccount)
			.toList();
		return new PostInteractions(toPost(thread.get("post")), replies, likedBy, repostedBy);
	}

	/** Resolves a post reference to an AT URI with a DID authority (SPEC §6.9). An invalid one makes no HTTP call. */
	private String resolvePostUri(String postRef) {
		String ref = postRef.trim();
		Matcher m = AT_URI.matcher(ref);
		if (!m.matches()) {
			m = WEB_URL.matcher(ref);
			if (!m.matches()) {
				throw new IllegalArgumentException("Invalid bluesky post reference '" + postRef + "'");
			}
		}
		String authority = m.group(1);
		String did = authority.startsWith("did:") ? authority : resolveHandle(authority, postRef);
		return "at://" + did + "/app.bsky.feed.post/" + m.group(2);
	}

	/** Reads a post for an action (SPEC §5, "Reading a post for an action"). */
	private JsonNode readPost(String postRef) {
		String uri = resolvePostUri(postRef);
		List<JsonNode> posts = Json.array(get("/app.bsky.feed.getPosts?uris={uri}", uri), "posts");
		if (posts.isEmpty()) {
			throw postNotFound(postRef);
		}
		return posts.get(0);
	}

	private String resolveHandle(String handle, String postRef) {
		try {
			return Json.text(get("/com.atproto.identity.resolveHandle?handle={handle}", handle), "did");
		}
		catch (HttpClientErrorException ex) {
			throw postNotFound(postRef);
		}
	}

	private static IllegalArgumentException postNotFound(String postRef) {
		return new IllegalArgumentException("Post '" + postRef + "' not found on " + PLATFORM);
	}

	// --- Trends ---

	@Override
	public TrendsResult getTrends(int limit) {
		int n = Math.min(limit, TRENDS_MAX);
		try {
			JsonNode body = get("/app.bsky.unspecced.getTrends?limit={limit}", n);
			if (body.path("trends").isArray()) {
				List<TrendTag> tags = Json.array(body, "trends")
					.stream()
					.map(t -> new TrendTag(Json.text(t, "displayName"), "https://bsky.app" + Json.text(t, "link"),
							Json.number(t, "postCount")))
					.toList();
				return new TrendsResult(PLATFORM, tags, List.of(),
						"Bluesky trends come from an unstable (unspecced) API; post counts cover the time since each trend started.");
			}
		}
		catch (RestClientResponseException ex) {
			// Fall through to the older endpoint.
		}
		try {
			JsonNode body = get("/app.bsky.unspecced.getTrendingTopics?limit={limit}", n);
			if (body.path("topics").isArray()) {
				List<TrendTag> tags = Json.array(body, "topics").stream().map(t -> {
					String name = Json.text(t, "displayName");
					return new TrendTag(name.isEmpty() ? Json.text(t, "topic") : name,
							"https://bsky.app" + Json.text(t, "link"), null);
				}).toList();
				return new TrendsResult(PLATFORM, tags, List.of(),
						"Bluesky trending topics come from an unstable (unspecced) API and have no usage counts.");
			}
		}
		catch (RestClientResponseException ex) {
			// Reported below.
		}
		throw new IllegalStateException("Bluesky trending topics are currently unavailable");
	}

	// --- Similar accounts ---

	@Override
	public SimilarAccountsResult findSimilarAccounts(String handle, int limit) {
		JsonNode body;
		try {
			body = get("/app.bsky.graph.getSuggestedFollowsByActor?actor={actor}", handle);
		}
		catch (HttpClientErrorException ex) {
			throw actorError(ex, handle);
		}
		String ownDid = session().did();
		List<AccountSummary> accounts = Json.array(body, "suggestions")
			.stream()
			.filter(p -> {
				String did = Json.text(p, "did");
				return !did.equals(ownDid) && !did.equalsIgnoreCase(handle)
						&& !Json.text(p, "handle").equalsIgnoreCase(handle);
			})
			.limit(Math.min(limit, PAGE_MAX))
			.map(this::toAccount)
			.toList();
		return new SimilarAccountsResult(PLATFORM, "platform-suggestions", List.of(), accounts);
	}

	// --- Posting ---

	@Override
	public PublishedPost createPost(String content, @Nullable PublishedPost root, @Nullable PublishedPost parent) {
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("$type", "app.bsky.feed.post");
		record.put("text", content);
		record.put("createdAt", clock.instant().toString());
		if (root != null && parent != null) {
			record.put("reply", Map.of("root", Map.of("uri", root.id(), "cid", root.cid()), "parent",
					Map.of("uri", parent.id(), "cid", parent.cid())));
		}
		return publishPost(record);
	}

	private PublishedPost publishPost(Map<String, Object> record) {
		JsonNode created = createRecord("app.bsky.feed.post", record);
		String uri = Json.text(created, "uri");
		return new PublishedPost(uri, Json.text(created, "cid"), postUrl(session().handle(), uri));
	}

	private Map<String, Object> postRecord(String text) {
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("$type", "app.bsky.feed.post");
		record.put("text", text);
		record.put("createdAt", clock.instant().toString());
		return record;
	}

	// --- Relationships (SPEC §5, Follow … Unmute) ---

	@Override
	public RelationshipResult setRelationship(String handle, AccountAction action) {
		JsonNode profile;
		try {
			profile = get("/app.bsky.actor.getProfile?actor={actor}", handle);
		}
		catch (HttpClientErrorException ex) {
			throw actorError(ex, handle);
		}
		String did = Json.text(profile, "did");
		if (did.equals(session().did())) {
			throw new IllegalArgumentException("You can't " + action.id() + " your own account");
		}
		JsonNode viewer = profile.path("viewer");
		AccountSummary account = toAccount(profile);
		Function<String, RelationshipResult> done = status -> new RelationshipResult(PLATFORM, action.id(), status,
				account, null);
		return switch (action) {
			case FOLLOW -> {
				if (Json.isPresent(viewer.get("blocking")) || Json.isPresent(viewer.get("blockingByList"))
						|| viewer.path("blockedBy").asBoolean(false)) {
					throw new IllegalArgumentException("Can't follow '" + handle + "' on " + PLATFORM + " because of a block");
				}
				if (Json.isPresent(viewer.get("following"))) {
					yield done.apply("already-following");
				}
				createRecord(FOLLOW_COLLECTION, subjectRecord(FOLLOW_COLLECTION, did));
				yield done.apply("following");
			}
			case UNFOLLOW -> {
				if (!Json.isPresent(viewer.get("following"))) {
					yield done.apply("not-following");
				}
				deleteRecord(FOLLOW_COLLECTION, ownRkey(Json.text(viewer, "following"), FOLLOW_COLLECTION, "follow"));
				yield done.apply("unfollowed");
			}
			case BLOCK -> {
				if (Json.isPresent(viewer.get("blocking"))) {
					yield done.apply("already-blocked");
				}
				createRecord(BLOCK_COLLECTION, subjectRecord(BLOCK_COLLECTION, did));
				yield done.apply("blocked");
			}
			case UNBLOCK -> {
				String listName = Json.text(viewer.get("blockingByList"), "name");
				if (!Json.isPresent(viewer.get("blocking"))) {
					if (Json.isPresent(viewer.get("blockingByList"))) {
						throw new IllegalArgumentException("'" + handle + "' is blocked through the moderation list '"
								+ listName + "' on bluesky. Remove them from the list or unsubscribe from it in the Bluesky app.");
					}
					yield done.apply("not-blocked");
				}
				deleteRecord(BLOCK_COLLECTION, ownRkey(Json.text(viewer, "blocking"), BLOCK_COLLECTION, "block"));
				String note = Json.isPresent(viewer.get("blockingByList"))
						? "Still blocked through the moderation list '" + listName + "'." : null;
				yield new RelationshipResult(PLATFORM, action.id(), "unblocked", account, note);
			}
			case MUTE -> {
				if (viewer.path("muted").asBoolean(false) && !Json.isPresent(viewer.get("mutedByList"))
						&& !viewer.path("mutedOnlyReposts").asBoolean(false)
						&& !viewer.path("mutedOnlyQuoteposts").asBoolean(false)) {
					yield done.apply("already-muted");
				}
				procedure("/app.bsky.graph.muteActor", Map.of("actor", did));
				yield done.apply("muted");
			}
			case UNMUTE -> {
				if (!viewer.path("muted").asBoolean(false)) {
					yield done.apply("not-muted");
				}
				procedure("/app.bsky.graph.unmuteActor", Map.of("actor", did));
				String note = Json.isPresent(viewer.get("mutedByList")) ? "Any direct mute was removed, but '" + handle
						+ "' is still muted through the mute list '" + Json.text(viewer.get("mutedByList"), "name")
						+ "'. Remove them from the list or unsubscribe from it in the Bluesky app." : null;
				yield new RelationshipResult(PLATFORM, action.id(), "unmuted", account, note);
			}
		};
	}

	private Map<String, Object> subjectRecord(String collection, Object subject) {
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("$type", collection);
		record.put("subject", subject);
		record.put("createdAt", clock.instant().toString());
		return record;
	}

	// --- Post actions and bookmarks (SPEC §5, Like … Bookmark / unbookmark) ---

	@Override
	public PostActionResult setPostAction(String postRef, PostAction action) {
		JsonNode postView = readPost(postRef);
		JsonNode viewer = postView.path("viewer");
		PostResult post = toPost(postView);
		Map<String, String> strongRef = Map.of("uri", Json.text(postView, "uri"), "cid", Json.text(postView, "cid"));
		BiFunction<String, PostResult, PostActionResult> done = (status, p) -> new PostActionResult(PLATFORM, action.id(),
				status, p);
		return switch (action) {
			case LIKE -> {
				if (Json.isPresent(viewer.get("like"))) {
					yield done.apply("already-liked", post);
				}
				createRecord(LIKE_COLLECTION, subjectRecord(LIKE_COLLECTION, strongRef));
				yield done.apply("liked", post.withLikeCount(post.likeCount() + 1));
			}
			case UNLIKE -> {
				if (!Json.isPresent(viewer.get("like"))) {
					yield done.apply("not-liked", post);
				}
				deleteRecord(LIKE_COLLECTION, ownRkey(Json.text(viewer, "like"), LIKE_COLLECTION, "like"));
				yield done.apply("unliked", post.withLikeCount(Math.max(0, post.likeCount() - 1)));
			}
			case REPOST -> {
				if (Json.isPresent(viewer.get("repost"))) {
					yield done.apply("already-reposted", post);
				}
				createRecord(REPOST_COLLECTION, subjectRecord(REPOST_COLLECTION, strongRef));
				yield done.apply("reposted", post.withRepostCount(post.repostCount() + 1));
			}
			case UNREPOST -> {
				if (!Json.isPresent(viewer.get("repost"))) {
					yield done.apply("not-reposted", post);
				}
				deleteRecord(REPOST_COLLECTION, ownRkey(Json.text(viewer, "repost"), REPOST_COLLECTION, "repost"));
				yield done.apply("unreposted", post.withRepostCount(Math.max(0, post.repostCount() - 1)));
			}
			case BOOKMARK -> {
				if (viewer.path("bookmarked").asBoolean(false)) {
					yield done.apply("already-bookmarked", post);
				}
				procedure("/app.bsky.bookmark.createBookmark", strongRef);
				yield done.apply("bookmarked", post);
			}
			case UNBOOKMARK -> {
				if (!viewer.path("bookmarked").asBoolean(false)) {
					yield done.apply("not-bookmarked", post);
				}
				procedure("/app.bsky.bookmark.deleteBookmark", Map.of("uri", Json.text(postView, "uri")));
				yield done.apply("unbookmarked", post);
			}
		};
	}

	@Override
	public List<PostResult> getBookmarks(int limit) {
		JsonNode body = get("/app.bsky.bookmark.getBookmarks?limit={limit}", Math.min(limit, PAGE_MAX));
		return Json.array(body, "bookmarks")
			.stream()
			.map(b -> b.get("item"))
			.filter(item -> POST_VIEW.equals(Json.text(item, "$type")))
			.map(this::toPost)
			.toList();
	}

	// --- Replies (SPEC §5, Reply) ---

	@Override
	public ReplyTarget replyTarget(String postRef) {
		JsonNode postView = readPost(postRef);
		if (postView.path("viewer").path("replyDisabled").asBoolean(false)) {
			throw new IllegalArgumentException("The author of this post on bluesky has restricted who can reply");
		}
		PostResult post = toPost(postView);
		PublishedPost parent = new PublishedPost(post.id(), Json.text(postView, "cid"), post.url());
		JsonNode root = postView.path("record").path("reply").get("root");
		PublishedPost rootPost = Json.isPresent(root)
				? new PublishedPost(Json.text(root, "uri"), Json.text(root, "cid"), "") : parent;
		return new ReplyTarget(post, parent, rootPost, null, null);
	}

	@Override
	public PublishedPost reply(ReplyTarget target, String text) {
		return createPost(text, target.root(), target.parent());
	}

	// --- Quotes, polls and votes (SPEC §5, Quote; polls unsupported) ---

	@Override
	public QuoteTarget quoteTarget(String postRef) {
		JsonNode postView = readPost(postRef);
		if (postView.path("viewer").path("embeddingDisabled").asBoolean(false)) {
			throw new IllegalArgumentException("You can't quote this post on bluesky (the author has disabled quoting)");
		}
		PostResult post = toPost(postView);
		return new QuoteTarget(new PublishedPost(post.id(), Json.text(postView, "cid"), post.url()), post.author(), null,
				null);
	}

	@Override
	public NewPost createTopLevelPost(String content, @Nullable QuoteTarget quote, @Nullable PollInput poll) {
		if (poll != null) {
			throw new IllegalArgumentException(NO_POLLS);
		}
		Map<String, Object> record = postRecord(content);
		if (quote != null) {
			record.put("embed", Map.of("$type", "app.bsky.embed.record", "record",
					Map.of("uri", quote.quoted().id(), "cid", String.valueOf(quote.quoted().cid()))));
		}
		return new NewPost(publishPost(record), null);
	}

	@Override
	public VoteResult vote(String postRef, List<Integer> choices) {
		throw new IllegalArgumentException(NO_POLLS);
	}

	// --- Repository writes ---

	private JsonNode createRecord(String collection, Map<String, Object> record) {
		return withSession(s -> Json.required(http.post()
			.uri("/com.atproto.repo.createRecord")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + s.accessJwt())
			.body(Map.of("repo", s.did(), "collection", collection, "record", record))
			.retrieve()
			.body(JsonNode.class), PLATFORM));
	}

	/** Deletes a record; {@code deleteRecord} is idempotent, so the session retry is always safe. */
	private void deleteRecord(String collection, String rkey) {
		withSession(s -> http.post()
			.uri("/com.atproto.repo.deleteRecord")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + s.accessJwt())
			.body(Map.of("repo", s.did(), "collection", collection, "rkey", rkey))
			.retrieve()
			.toBodilessEntity());
	}

	/** Calls an XRPC procedure whose response body is not needed (mutes, bookmarks). */
	private void procedure(String path, Map<String, ?> body) {
		withSession(s -> http.post()
			.uri(path)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + s.accessJwt())
			.body(body)
			.retrieve()
			.toBodilessEntity());
	}

	/**
	 * The rkey of a record the viewer owns, from its AT URI, e.g. {@code viewer.following}. The record must live in the
	 * session's own repo; anything else is refused without a write.
	 */
	private String ownRkey(String uri, String collection, String kind) {
		Matcher m = Pattern.compile("^at://([^/]+)/" + Pattern.quote(collection) + "/([^/?#]+)$").matcher(uri);
		if (!m.matches() || !m.group(1).equals(session().did())) {
			throw new IllegalStateException("Unexpected " + kind + " record '" + uri + "' on bluesky");
		}
		return m.group(2);
	}

	@Override
	public PostingRules postingRules() {
		int maxParts = properties.thread().maxParts();
		return new PostingRules(PLATFORM, MAX_GRAPHEMES, "graphemes", null, MAX_BYTES, maxParts, " (n/N)",
				(" (" + maxParts + "/" + maxParts + ")").length(), null,
				"Counts user-perceived characters (an emoji counts as 1). URLs count at their full length.", "fixed", true,
				null);
	}

	@Override
	public PartCheck checkPart(int index, String text) {
		int graphemes = TextLength.graphemes(text);
		int bytes = TextLength.utf8Bytes(text);
		String reason = null;
		if (text.isBlank()) {
			reason = "blank";
		}
		else if (graphemes > MAX_GRAPHEMES) {
			reason = TextLength.overReason(graphemes, MAX_GRAPHEMES, "graphemes");
		}
		else if (bytes > MAX_BYTES) {
			reason = TextLength.overReason(bytes, MAX_BYTES, "bytes");
		}
		return new PartCheck(index, text, graphemes, bytes, reason == null, reason);
	}

	// --- Session ---

	synchronized Session session() {
		Session current = session;
		if (current == null) {
			current = login();
			session = current;
		}
		return current;
	}

	private Session login() {
		JsonNode body;
		try {
			body = http.post()
				.uri("/com.atproto.server.createSession")
				.body(Map.of("identifier", properties.bluesky().handle(), "password", properties.bluesky().appPassword()))
				.retrieve()
				.body(JsonNode.class);
		}
		catch (HttpClientErrorException ex) {
			String error = errorName(ex);
			switch (error) {
				case "AuthFactorTokenRequired" -> throw new IllegalStateException(
						"Bluesky login needs a second factor: use an app password (Settings > Privacy and security > App passwords)");
				case "AccountTakedown" -> throw new IllegalStateException("Bluesky account is taken down");
				default -> throw new IllegalStateException(
						"Bluesky login failed: check BLUESKY_HANDLE and BLUESKY_APP_PASSWORD");
			}
		}
		return toSession(Json.required(body, PLATFORM));
	}

	private synchronized Session refresh(Session expired) {
		Session current = session;
		if (current != null && current != expired) {
			return current; // another thread already refreshed
		}
		Session refreshed;
		try {
			JsonNode body = http.post()
				.uri("/com.atproto.server.refreshSession")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + expired.refreshJwt())
				.retrieve()
				.body(JsonNode.class);
			refreshed = toSession(Json.required(body, PLATFORM));
		}
		catch (HttpClientErrorException ex) {
			refreshed = login();
		}
		session = refreshed;
		return refreshed;
	}

	private static Session toSession(JsonNode body) {
		return new Session(Json.text(body, "accessJwt"), Json.text(body, "refreshJwt"), Json.text(body, "did"),
				Json.text(body, "handle"));
	}

	/** Runs an authenticated call; on {@code ExpiredToken}, refreshes the session and retries once. */
	private <T> T withSession(Function<Session, T> call) {
		Session current = session();
		try {
			return call.apply(current);
		}
		catch (HttpClientErrorException ex) {
			if ((ex.getStatusCode().value() == 400 || ex.getStatusCode().value() == 401)
					&& "ExpiredToken".equals(errorName(ex))) {
				return call.apply(refresh(current));
			}
			throw ex;
		}
	}

	private JsonNode get(String uriTemplate, Object... variables) {
		return withSession(s -> Json.required(http.get()
			.uri(uriTemplate, variables)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + s.accessJwt())
			.retrieve()
			.body(JsonNode.class), PLATFORM));
	}

	// --- Errors and mapping ---

	private static RuntimeException actorError(HttpClientErrorException ex, String handle) {
		String error = errorName(ex);
		if ("BlockedActor".equals(error) || "BlockedByActor".equals(error)) {
			return new IllegalArgumentException(
					"Posts by '" + handle + "' on bluesky are unavailable because of a block");
		}
		if ("InvalidRequest".equals(error) || errorMessage(ex).contains("Profile not found")) {
			return new IllegalArgumentException("User '" + handle + "' not found on " + PLATFORM);
		}
		return ex;
	}

	private static String errorName(RestClientResponseException ex) {
		return Json.text(errorBody(ex), "error");
	}

	private static String errorMessage(RestClientResponseException ex) {
		return Json.text(errorBody(ex), "message");
	}

	private static JsonNode errorBody(RestClientResponseException ex) {
		try {
			return JSON.readTree(ex.getResponseBodyAsString());
		}
		catch (RuntimeException parseFailure) {
			return JSON.createObjectNode();
		}
	}

	private AccountSummary toAccount(JsonNode profile) {
		String handle = Json.text(profile, "handle");
		String displayName = Json.text(profile, "displayName");
		return new AccountSummary(PLATFORM, Json.text(profile, "did"), "@" + handle,
				displayName.isBlank() ? handle : displayName, Json.text(profile, "description"),
				"https://bsky.app/profile/" + handle);
	}

	private PostResult toPost(JsonNode post) {
		String uri = Json.text(post, "uri");
		String authorHandle = Json.text(post.get("author"), "handle");
		JsonNode record = post.get("record");
		return new PostResult(PLATFORM, uri, "@" + authorHandle, Json.text(record, "text"),
				Json.isoUtc(Json.text(record, "createdAt")), postUrl(authorHandle, uri), Json.number(post, "replyCount"),
				Json.number(post, "repostCount"), Json.number(post, "likeCount"), toQuote(post.get("embed")), null);
	}

	private static String postUrl(String authorHandle, String uri) {
		return "https://bsky.app/profile/" + authorHandle + "/post/" + uri.substring(uri.lastIndexOf('/') + 1);
	}

	/** The quoted post of a record embed, or null when the post quotes nothing (SPEC §5, Bluesky post mapping). */
	static @Nullable QuoteSummary toQuote(@Nullable JsonNode embed) {
		String embedType = Json.text(embed, "$type");
		JsonNode view;
		if (EMBED_RECORD_VIEW.equals(embedType)) {
			view = embed == null ? null : embed.get("record");
		}
		else if (EMBED_RECORD_WITH_MEDIA_VIEW.equals(embedType)) {
			view = embed == null ? null : embed.path("record").get("record");
		}
		else {
			return null;
		}
		return switch (Json.text(view, "$type")) {
			case "app.bsky.embed.record#viewRecord" -> {
				JsonNode value = view == null ? null : view.get("value");
				if (!"app.bsky.feed.post".equals(Json.text(value, "$type"))) {
					yield null; // an embedded feed, list, labeler or starter pack
				}
				String uri = Json.text(view, "uri");
				String handle = Json.text(view == null ? null : view.get("author"), "handle");
				yield new QuoteSummary("accepted", uri, "@" + handle, Json.text(value, "text"), postUrl(handle, uri));
			}
			case "app.bsky.embed.record#viewNotFound" -> new QuoteSummary("deleted", null, null, null, null);
			case "app.bsky.embed.record#viewBlocked" -> new QuoteSummary("blocked", null, null, null, null);
			case "app.bsky.embed.record#viewDetached" -> new QuoteSummary("detached", null, null, null, null);
			default -> null;
		};
	}

}
