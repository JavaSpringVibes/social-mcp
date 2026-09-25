package com.socialmcp.platform.bluesky;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.socialmcp.model.AccountSummary;
import com.socialmcp.model.PartCheck;
import com.socialmcp.model.PostInteractions;
import com.socialmcp.model.PostResult;
import com.socialmcp.model.PostingRules;
import com.socialmcp.model.ProfileResult;
import com.socialmcp.model.PublishedPost;
import com.socialmcp.model.SearchSort;
import com.socialmcp.model.SimilarAccountsResult;
import com.socialmcp.model.TimelineType;
import com.socialmcp.model.TrendTag;
import com.socialmcp.model.TrendsResult;
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
		String ref = postRef.trim();
		Matcher m = AT_URI.matcher(ref);
		if (!m.matches()) {
			m = WEB_URL.matcher(ref);
			if (!m.matches()) {
				throw new IllegalArgumentException("Invalid bluesky post reference '" + postRef + "'");
			}
		}
		String authority = m.group(1);
		String rkey = m.group(2);
		String did = authority.startsWith("did:") ? authority : resolveHandle(authority, postRef);
		String uri = "at://" + did + "/app.bsky.feed.post/" + rkey;

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
		JsonNode created = withSession(s -> Json.required(http.post()
			.uri("/com.atproto.repo.createRecord")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + s.accessJwt())
			.body(Map.of("repo", s.did(), "collection", "app.bsky.feed.post", "record", record))
			.retrieve()
			.body(JsonNode.class), PLATFORM));
		String uri = Json.text(created, "uri");
		String rkey = uri.substring(uri.lastIndexOf('/') + 1);
		return new PublishedPost(uri, Json.text(created, "cid"),
				"https://bsky.app/profile/" + session().handle() + "/post/" + rkey);
	}

	@Override
	public PostingRules postingRules() {
		int maxParts = properties.thread().maxParts();
		return new PostingRules(PLATFORM, MAX_GRAPHEMES, "graphemes", null, MAX_BYTES, maxParts, " (n/N)",
				(" (" + maxParts + "/" + maxParts + ")").length(), null,
				"Counts user-perceived characters (an emoji counts as 1). URLs count at their full length.", "fixed");
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
				Json.isoUtc(Json.text(record, "createdAt")),
				"https://bsky.app/profile/" + authorHandle + "/post/" + uri.substring(uri.lastIndexOf('/') + 1),
				Json.number(post, "replyCount"), Json.number(post, "repostCount"), Json.number(post, "likeCount"));
	}

}
