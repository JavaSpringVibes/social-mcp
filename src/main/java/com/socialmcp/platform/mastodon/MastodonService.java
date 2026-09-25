package com.socialmcp.platform.mastodon;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.socialmcp.config.SocialProperties;
import com.socialmcp.model.AccountAction;
import com.socialmcp.model.AccountSummary;
import com.socialmcp.model.NewPost;
import com.socialmcp.model.PartCheck;
import com.socialmcp.model.PollInput;
import com.socialmcp.model.PollOption;
import com.socialmcp.model.PollRules;
import com.socialmcp.model.PollSummary;
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
import com.socialmcp.text.HtmlText;
import com.socialmcp.text.TextLength;

/** Mastodon via its REST API with a personal access token (SPEC §5, Mastodon). */
@Service
public class MastodonService implements SocialPlatformService {

	private static final Logger log = LoggerFactory.getLogger(MastodonService.class);

	static final String PLATFORM = "mastodon";

	private static final int PAGE_MAX = 40;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int ACCOUNT_LIST_MAX = 80;

	private static final int TREND_TAGS_MAX = 20;

	private static final int DEFAULT_URL_LENGTH = 23;

	private static final Duration INSTANCE_RETRY_BACKOFF = Duration.ofMinutes(10);

	/** {@code api_versions.mastodon} that introduced quote posts (Mastodon 4.5). */
	private static final int QUOTES_API_VERSION = 7;

	// Mastodon's own poll defaults (PollOptionsValidator, PollExpirationValidator).
	private static final int DEFAULT_POLL_MAX_OPTIONS = 4;

	private static final int DEFAULT_POLL_MAX_OPTION_CHARS = 50;

	private static final int DEFAULT_POLL_MIN_EXPIRATION = 300;

	private static final int DEFAULT_POLL_MAX_EXPIRATION = 2_629_746;

	private static final Pattern HASHTAG_QUERY = Pattern.compile("^#\\w+$", Pattern.UNICODE_CHARACTER_CLASS);

	private static final Pattern HANDLE = Pattern.compile("^[A-Za-z0-9_]+(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})?$");

	private static final Pattern STATUS_ID = Pattern.compile("^[0-9]+$");

	private final SocialProperties properties;

	private final RestClient http;

	private final Clock clock;

	private final AtomicReference<@Nullable String> ownAccountId = new AtomicReference<>();

	/** The cached instance limits and when they were fetched; null until first use. */
	private @Nullable InstanceState instanceState;

	@Autowired
	public MastodonService(SocialProperties properties, RestClient.Builder builder) {
		this(properties, builder, Clock.systemUTC());
	}

	MastodonService(SocialProperties properties, RestClient.Builder builder, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		this.http = builder.baseUrl(properties.mastodon().instanceUrl())
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.mastodon().accessToken())
			.build();
	}

	@Override
	public String platform() {
		return PLATFORM;
	}

	@Override
	public boolean isConfigured() {
		return properties.mastodon().isConfigured();
	}

	@Override
	public boolean isValidHandle(String handle) {
		return HANDLE.matcher(handle).matches();
	}

	@Override
	public boolean supportsPolls() {
		return true;
	}

	// --- Search, timelines, user posts ---

	@Override
	public List<PostResult> searchPosts(String query, SearchSort sort, int limit) {
		int n = Math.min(limit, PAGE_MAX);
		int fetch = sort == SearchSort.TOP ? PAGE_MAX : n;
		String trimmed = query.trim();
		List<JsonNode> statuses;
		if (HASHTAG_QUERY.matcher(trimmed).matches()) {
			statuses = Json.elements(get("/api/v1/timelines/tag/{tag}?limit={limit}", trimmed.substring(1), fetch));
		}
		else {
			statuses = Json.array(get("/api/v2/search?q={q}&type=statuses&limit={limit}", trimmed, fetch), "statuses");
		}
		List<PostResult> posts = new ArrayList<>(statuses.stream().map(this::toPost).toList());
		if (sort == SearchSort.TOP) {
			posts.sort(Comparator.comparingLong(MastodonService::engagementScore)
				.reversed()
				.thenComparing(p -> Json.instant(p.createdAt()), Comparator.reverseOrder()));
		}
		return posts.stream().limit(n).toList();
	}

	static long engagementScore(PostResult post) {
		return post.likeCount() + 2 * post.repostCount() + post.replyCount();
	}

	@Override
	public List<PostResult> getTimeline(TimelineType type, int limit) {
		int n = Math.min(limit, PAGE_MAX);
		JsonNode statuses = switch (type) {
			case HOME -> get("/api/v1/timelines/home?limit={limit}", n);
			case OWN -> authoredStatuses(ownAccountId(), n);
		};
		return Json.elements(statuses).stream().map(this::toPost).toList();
	}

	@Override
	public List<PostResult> getUserPosts(String handle, int limit) {
		JsonNode account = resolveAccount(handle);
		return Json.elements(authoredStatuses(Json.text(account, "id"), Math.min(limit, PAGE_MAX)))
			.stream()
			.map(this::toPost)
			.toList();
	}

	private JsonNode authoredStatuses(String accountId, int limit) {
		return get("/api/v1/accounts/{id}/statuses?limit={limit}&exclude_replies=true&exclude_reblogs=true",
				accountId, limit);
	}

	// --- Profiles ---

	@Override
	public ProfileResult getProfile(@Nullable String handle) {
		JsonNode account;
		if (handle == null) {
			account = get("/api/v1/accounts/verify_credentials");
			ownAccountId.set(Json.text(account, "id"));
		}
		else {
			account = resolveAccount(handle);
		}
		AccountSummary summary = toAccount(account);
		return new ProfileResult(PLATFORM, summary.id(), summary.handle(), summary.displayName(), summary.bio(),
				Json.number(account, "followers_count"), Json.number(account, "following_count"),
				Json.number(account, "statuses_count"), account.path("locked").asBoolean(false),
				Json.isoUtc(Json.text(account, "created_at")), summary.url());
	}

	// --- Post interactions ---

	@Override
	public PostInteractions getPostInteractions(String postRef, int limit) {
		String id = resolveStatusId(postRef);
		JsonNode status;
		try {
			status = get("/api/v1/statuses/{id}", id);
		}
		catch (HttpClientErrorException.NotFound ex) {
			throw postNotFound(postRef);
		}
		int n = Math.min(limit, PAGE_MAX);
		int accounts = Math.min(limit, ACCOUNT_LIST_MAX);
		List<PostResult> replies = Json.array(get("/api/v1/statuses/{id}/context", id), "descendants")
			.stream()
			.filter(s -> id.equals(Json.text(s, "in_reply_to_id")))
			.limit(n)
			.map(this::toPost)
			.toList();
		List<AccountSummary> likedBy = accountList(get("/api/v1/statuses/{id}/favourited_by?limit={limit}", id, accounts));
		List<AccountSummary> repostedBy = accountList(
				get("/api/v1/statuses/{id}/reblogged_by?limit={limit}", id, accounts));
		return new PostInteractions(toPost(status), replies, likedBy, repostedBy);
	}

	/** Resolves a post reference to a local status id (SPEC §6.9). An invalid reference makes no HTTP call. */
	private String resolveStatusId(String postRef) {
		String ref = postRef.trim();
		boolean isId = STATUS_ID.matcher(ref).matches();
		if (!isId && !ref.startsWith("https://")) {
			throw new IllegalArgumentException("Invalid mastodon post reference '" + postRef + "'");
		}
		return isId ? ref : resolveStatusUrl(ref, postRef);
	}

	/** Reads a post for an action and unwraps a boost (SPEC §5, "Reading a post for an action"). */
	private JsonNode readPost(String postRef) {
		String id = resolveStatusId(postRef);
		try {
			return unwrap(get("/api/v1/statuses/{id}", id));
		}
		catch (HttpClientErrorException.NotFound ex) {
			throw postNotFound(postRef);
		}
	}

	private String resolveStatusUrl(String url, String original) {
		List<JsonNode> found = Json
			.array(get("/api/v2/search?q={q}&type=statuses&resolve=true&limit=1", url), "statuses");
		if (found.isEmpty()) {
			throw postNotFound(original);
		}
		return Json.text(found.get(0), "id");
	}

	private static IllegalArgumentException postNotFound(String postRef) {
		return new IllegalArgumentException("Post '" + postRef + "' not found on " + PLATFORM);
	}

	// --- Trends ---

	@Override
	public TrendsResult getTrends(int limit) {
		List<TrendTag> tags = Json.elements(get("/api/v1/trends/tags?limit={limit}", Math.min(limit, TREND_TAGS_MAX)))
			.stream()
			.map(t -> new TrendTag("#" + Json.text(t, "name"), Json.text(t, "url"), recentUses(t)))
			.toList();
		List<PostResult> posts = Json.elements(get("/api/v1/trends/statuses?limit={limit}", Math.min(limit, PAGE_MAX)))
			.stream()
			.map(this::toPost)
			.toList();
		String notes = "Trends reflect the " + instanceDomain() + " instance only.";
		if (tags.isEmpty() && posts.isEmpty()) {
			notes += " This instance may have trends disabled.";
		}
		return new TrendsResult(PLATFORM, tags, posts, notes);
	}

	private static long recentUses(JsonNode tag) {
		long total = 0;
		for (JsonNode day : Json.array(tag, "history")) {
			try {
				total += Long.parseLong(Json.text(day, "uses"));
			}
			catch (NumberFormatException ignored) {
				// Skip malformed entries rather than failing the whole trends call.
			}
		}
		return total;
	}

	// --- Similar accounts ---

	@Override
	public SimilarAccountsResult findSimilarAccounts(String handle, int limit) {
		JsonNode target = resolveAccount(handle);
		String targetId = Json.text(target, "id");
		List<JsonNode> ownPosts = Json.elements(authoredStatuses(targetId, PAGE_MAX));

		Map<String, Integer> tagCounts = new HashMap<>();
		for (JsonNode status : ownPosts) {
			for (JsonNode tag : Json.array(status, "tags")) {
				tagCounts.merge(Json.text(tag, "name").toLowerCase(), 1, Integer::sum);
			}
		}
		tagCounts.remove("");
		List<String> topTags = tagCounts.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue()
				.reversed()
				.thenComparing(Map.Entry.comparingByKey()))
			.limit(3)
			.map(Map.Entry::getKey)
			.toList();
		List<String> basedOn = topTags.stream().map(t -> "#" + t).toList();
		if (topTags.isEmpty()) {
			return new SimilarAccountsResult(PLATFORM, "hashtag-overlap", basedOn, List.of());
		}

		String ownId = ownAccountId();
		Map<String, Candidate> candidates = new LinkedHashMap<>();
		for (String tag : topTags) {
			for (JsonNode status : Json.elements(get("/api/v1/timelines/tag/{tag}?limit={limit}", tag, PAGE_MAX))) {
				JsonNode original = Json.isPresent(status.get("reblog")) ? status.get("reblog") : status;
				JsonNode author = original.get("account");
				String authorId = Json.text(author, "id");
				if (authorId.isEmpty() || authorId.equals(targetId) || authorId.equals(ownId)) {
					continue;
				}
				candidates.computeIfAbsent(authorId, k -> new Candidate(author)).record(tag);
			}
		}
		List<AccountSummary> accounts = candidates.values()
			.stream()
			.sorted(Comparator.comparingInt((Candidate c) -> c.tags.size())
				.reversed()
				.thenComparing(Comparator.comparingInt((Candidate c) -> c.posts).reversed())
				.thenComparing(c -> Json.text(c.account, "acct")))
			.limit(Math.min(limit, PAGE_MAX))
			.map(c -> toAccount(c.account))
			.toList();
		return new SimilarAccountsResult(PLATFORM, "hashtag-overlap", basedOn, accounts);
	}

	private static final class Candidate {

		final JsonNode account;

		final Set<String> tags = new HashSet<>();

		int posts;

		Candidate(JsonNode account) {
			this.account = account;
		}

		void record(String tag) {
			tags.add(tag);
			posts++;
		}

	}

	// --- Posting ---

	@Override
	public PublishedPost createPost(String content, @Nullable PublishedPost root, @Nullable PublishedPost parent) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", content);
		if (parent == null) {
			body.put("visibility", "public");
		}
		else {
			body.put("in_reply_to_id", parent.id());
			body.put("visibility", properties.mastodon().threadVisibility());
		}
		JsonNode created = publish(body);
		return new PublishedPost(Json.text(created, "id"), null, Json.text(created, "url"));
	}

	/** Posts a status, retrying once with the same Idempotency-Key on a network error or 5xx (SPEC §5, Post). */
	private JsonNode publish(Map<String, Object> body) {
		String idempotencyKey = UUID.randomUUID().toString();
		try {
			return postStatus(body, idempotencyKey);
		}
		catch (HttpServerErrorException | ResourceAccessException ex) {
			// Safe to retry: Mastodon returns the already-created status for a repeated Idempotency-Key.
			return postStatus(body, idempotencyKey);
		}
	}

	private JsonNode postStatus(Map<String, Object> body, String idempotencyKey) {
		JsonNode created = http.post()
			.uri("/api/v1/statuses")
			.header("Idempotency-Key", idempotencyKey)
			.body(body)
			.retrieve()
			.body(JsonNode.class);
		return Json.required(created, PLATFORM);
	}

	@Override
	public PostingRules postingRules() {
		InstanceInfo info = instanceInfo();
		int maxParts = properties.thread().maxParts();
		return new PostingRules(PLATFORM, info.maxLength(), "graphemes", info.urlLength(), null, maxParts, " (n/N)",
				(" (" + maxParts + "/" + maxParts + ")").length(), properties.mastodon().threadVisibility(),
				"Counts user-perceived characters (an emoji counts as 1). Every http(s) URL counts as "
						+ info.urlLength() + ". A mention @user@domain counts only as @user.",
				info.source(), info.quotes(), info.polls());
	}

	@Override
	public PartCheck checkPart(int index, String text) {
		InstanceInfo info = instanceInfo();
		int length = TextLength.mastodon(text, info.urlLength());
		if (text.isBlank()) {
			return new PartCheck(index, text, length, null, false, "blank");
		}
		boolean ok = length <= info.maxLength();
		return new PartCheck(index, text, length, null, ok,
				ok ? null : TextLength.overReason(length, info.maxLength(), "graphemes"));
	}

	// --- Relationships (SPEC §5, Follow … Unmute) ---

	@Override
	public RelationshipResult setRelationship(String handle, AccountAction action) {
		String scopes = switch (action) {
			case FOLLOW, UNFOLLOW -> "read:follows, write:follows";
			case BLOCK, UNBLOCK -> "read:follows, write:blocks";
			case MUTE, UNMUTE -> "read:follows, write:mutes";
		};
		return withScopes(scopes, "setAccountRelationship (" + action.id() + ")", () -> {
			JsonNode account = resolveAccount(handle);
			String id = Json.text(account, "id");
			if (id.equals(ownAccountId())) {
				throw new IllegalArgumentException("You can't " + action.id() + " your own account");
			}
			JsonNode relationship = relationship(id);
			Relationship r = new Relationship(action, toAccount(account), id, handle);
			return switch (action) {
				case FOLLOW -> follow(r, relationship, account);
				case UNFOLLOW -> {
					String status = flag(relationship, "following") ? "unfollowed"
							: flag(relationship, "requested") ? "request-cancelled" : null;
					if (status == null) {
						yield r.result("not-following", null);
					}
					JsonNode after = post("/api/v1/accounts/{id}/unfollow", id);
					r.confirm(!flag(after, "following") && !flag(after, "requested"));
					yield r.result(status, null);
				}
				case BLOCK -> {
					if (flag(relationship, "blocking")) {
						yield r.result("already-blocked", null);
					}
					r.confirm(flag(post("/api/v1/accounts/{id}/block", id), "blocking"));
					yield r.result("blocked", null);
				}
				case UNBLOCK -> {
					if (!flag(relationship, "blocking")) {
						yield r.result("not-blocked", null);
					}
					r.confirm(!flag(post("/api/v1/accounts/{id}/unblock", id), "blocking"));
					String note = flag(relationship, "domain_blocking") ? "Their server " + domainOf(r.account())
							+ " is also blocked; manage domain blocks in Mastodon's settings." : null;
					yield r.result("unblocked", note);
				}
				case MUTE -> {
					if (flag(relationship, "muting") && flag(relationship, "muting_notifications")
							&& !Json.isPresent(relationship.get("muting_expires_at"))) {
						yield r.result("already-muted", null);
					}
					r.confirm(flag(post("/api/v1/accounts/{id}/mute", id), "muting"));
					yield r.result("muted", null);
				}
				case UNMUTE -> {
					if (!flag(relationship, "muting")) {
						yield r.result("not-muted", null);
					}
					r.confirm(!flag(post("/api/v1/accounts/{id}/unmute", id), "muting"));
					yield r.result("unmuted", null);
				}
			};
		});
	}

	private RelationshipResult follow(Relationship r, JsonNode relationship, JsonNode account) {
		if (flag(relationship, "blocking") || flag(relationship, "blocked_by")) {
			throw new IllegalArgumentException("Can't follow '" + r.handle() + "' on " + PLATFORM + " because of a block");
		}
		if (flag(relationship, "following")) {
			return r.result("already-following", null);
		}
		if (flag(relationship, "requested")) {
			return r.result("already-requested", null);
		}
		JsonNode after;
		try {
			after = post("/api/v1/accounts/{id}/follow", r.id());
		}
		catch (HttpClientErrorException.Forbidden ex) {
			if (isScopeError(ex)) {
				throw ex;
			}
			throw new IllegalArgumentException("Mastodon doesn't allow following '" + r.handle()
					+ "' (the account may have moved, or its server may be blocked)");
		}
		if (flag(after, "following")) {
			return r.result("following", null);
		}
		if (flag(after, "requested")) {
			String note = account.path("locked").asBoolean(false) ? "Waiting for the account to approve the request."
					: "Waiting for " + domainOf(r.account()) + " to confirm; this usually takes a few seconds.";
			return r.result("requested", note);
		}
		throw r.unconfirmed();
	}

	/** One relationship change in progress: the target and how to report the outcome. */
	private record Relationship(AccountAction action, AccountSummary account, String id, String handle) {

		RelationshipResult result(String status, @Nullable String note) {
			return new RelationshipResult(PLATFORM, action.id(), status, account, note);
		}

		void confirm(boolean confirmed) {
			if (!confirmed) {
				throw unconfirmed();
			}
		}

		IllegalStateException unconfirmed() {
			return new IllegalStateException("Mastodon did not confirm the " + action.id() + " of '" + handle + "'");
		}

	}

	private JsonNode relationship(String accountId) {
		List<JsonNode> relationships = Json.elements(get("/api/v1/accounts/relationships?id[]={id}", accountId));
		if (relationships.isEmpty()) {
			throw new IllegalStateException("Mastodon returned no relationship for account " + accountId);
		}
		return relationships.get(0);
	}

	private static boolean flag(JsonNode node, String field) {
		return node.path(field).asBoolean(false);
	}

	/** The domain of a fully qualified handle such as {@code @bob@example.social}. */
	private static String domainOf(AccountSummary account) {
		return account.handle().substring(account.handle().lastIndexOf('@') + 1);
	}

	// --- Post actions and bookmarks (SPEC §5, Like … Bookmark / unbookmark) ---

	@Override
	public PostActionResult setPostAction(String postRef, PostAction action) {
		String scopes = switch (action) {
			case LIKE, UNLIKE -> "write:favourites";
			case REPOST, UNREPOST -> "write:statuses";
			case BOOKMARK, UNBOOKMARK -> "write:bookmarks";
		};
		return withScopes(scopes, "setPostAction (" + action.id() + ")", () -> {
			JsonNode status = readPost(postRef);
			return switch (action) {
				case LIKE -> toggle(action, postRef, status, "favourited", true, "favourite", "liked", "already-liked");
				case UNLIKE -> toggle(action, postRef, status, "favourited", false, "unfavourite", "unliked", "not-liked");
				case REPOST -> repost(postRef, status);
				case UNREPOST -> toggle(action, postRef, status, "reblogged", false, "unreblog", "unreposted",
						"not-reposted");
				case BOOKMARK -> toggle(action, postRef, status, "bookmarked", true, "bookmark", "bookmarked",
						"already-bookmarked");
				case UNBOOKMARK -> toggle(action, postRef, status, "bookmarked", false, "unbookmark", "unbookmarked",
						"not-bookmarked");
			};
		});
	}

	/**
	 * Sets a boolean on a status through {@code POST /api/v1/statuses/{id}/{endpoint}}, unless it already has the wanted
	 * value. The response is the updated status and must show the wanted value.
	 */
	private PostActionResult toggle(PostAction action, String postRef, JsonNode status, String field, boolean wanted,
			String endpoint, String doneStatus, String noopStatus) {
		if (flag(status, field) == wanted) {
			return new PostActionResult(PLATFORM, action.id(), noopStatus, toPost(status));
		}
		JsonNode after = unwrap(postToStatus(postRef, Json.text(status, "id"), endpoint));
		if (flag(after, field) != wanted) {
			throw new IllegalStateException(
					"Mastodon did not confirm the " + action.id() + " of '" + postRef + "'");
		}
		return new PostActionResult(PLATFORM, action.id(), doneStatus, toPost(after));
	}

	private PostActionResult repost(String postRef, JsonNode status) {
		if (flag(status, "reblogged")) {
			return new PostActionResult(PLATFORM, "repost", "already-reposted", toPost(status));
		}
		String visibility = Json.text(status, "visibility");
		boolean own = Json.text(status.get("account"), "id").equals(ownAccountId());
		if ("direct".equals(visibility) || ("private".equals(visibility) && !own)) {
			throw new IllegalArgumentException("This post can't be reposted on mastodon (it is "
					+ ("direct".equals(visibility) ? "a direct message" : "followers-only") + ")");
		}
		try {
			return toggle(PostAction.REPOST, postRef, status, "reblogged", true, "reblog", "reposted",
					"already-reposted");
		}
		catch (HttpClientErrorException.Forbidden ex) {
			if (isScopeError(ex)) {
				throw ex;
			}
			throw new IllegalArgumentException("This post can't be reposted on mastodon (not allowed)");
		}
	}

	private JsonNode postToStatus(String postRef, String statusId, String endpoint) {
		try {
			return post("/api/v1/statuses/{id}/" + endpoint, statusId);
		}
		catch (HttpClientErrorException.NotFound ex) {
			throw postNotFound(postRef);
		}
	}

	@Override
	public List<PostResult> getBookmarks(int limit) {
		return withScopes("read:bookmarks", "getSocialBookmarks",
				() -> Json.elements(get("/api/v1/bookmarks?limit={limit}", Math.min(limit, PAGE_MAX)))
					.stream()
					.map(this::toPost)
					.toList());
	}

	// --- Replies (SPEC §5, Reply) ---

	@Override
	public ReplyTarget replyTarget(String postRef) {
		JsonNode status = readPost(postRef);
		JsonNode author = status.get("account");
		String mention = Json.text(author, "id").equals(ownAccountId()) ? null : Json.text(author, "acct");
		String visibility = Json.text(status, "visibility");
		PublishedPost parent = new PublishedPost(Json.text(status, "id"), null, Json.text(status, "url"));
		return new ReplyTarget(toPost(status), parent, parent, visibility.isEmpty() ? "public" : visibility, mention);
	}

	@Override
	public PublishedPost reply(ReplyTarget target, String text) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", text);
		body.put("in_reply_to_id", target.parent().id());
		body.put("visibility", target.visibility() == null ? "public" : target.visibility());
		JsonNode created = publish(body);
		return new PublishedPost(Json.text(created, "id"), null, Json.text(created, "url"));
	}

	// --- Quotes, polls and votes (SPEC §5, Quote, Poll, Vote) ---

	@Override
	public QuoteTarget quoteTarget(String postRef) {
		if (!instanceInfo().quotes()) {
			throw cannotQuote("this server doesn't support quote posts; it needs Mastodon 4.5 or later");
		}
		JsonNode status = readPost(postRef);
		String visibility = Json.text(status, "visibility");
		if ("direct".equals(visibility)) {
			throw cannotQuote("it is a direct message");
		}
		String author = handleOf(status.get("account"));
		String approval = Json.text(status.path("quote_approval"), "current_user");
		List<String> caveats = new ArrayList<>();
		switch (approval) {
			case "automatic" -> {
			}
			case "manual" -> caveats.add(pendingCaveat(author));
			default -> throw cannotQuote("the author doesn't allow you to quote it");
		}
		String quoteVisibility = "public";
		if ("private".equals(visibility)) {
			quoteVisibility = "private";
			caveats.add("posted as followers-only, because the quoted post is followers-only");
		}
		PublishedPost quoted = new PublishedPost(Json.text(status, "id"), null, Json.text(status, "url"));
		return new QuoteTarget(quoted, author, quoteVisibility, caveats.isEmpty() ? null : String.join("; ", caveats));
	}

	@Override
	public NewPost createTopLevelPost(String content, @Nullable QuoteTarget quote, @Nullable PollInput poll) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", content);
		body.put("visibility", quote != null && quote.visibility() != null ? quote.visibility() : "public");
		if (quote != null) {
			body.put("quoted_status_id", quote.quoted().id());
		}
		if (poll != null) {
			Map<String, Object> pollBody = new LinkedHashMap<>();
			pollBody.put("options", poll.options() == null ? List.of() : poll.options().stream().map(String::trim).toList());
			pollBody.put("expires_in", poll.expiresInMinutesOrDefault() * 60);
			pollBody.put("multiple", poll.multipleOrDefault());
			pollBody.put("hide_totals", poll.hideTotalsOrDefault());
			body.put("poll", pollBody);
		}
		JsonNode created;
		try {
			created = publish(body);
		}
		catch (HttpClientErrorException ex) {
			if (quote == null || !isUnprocessable(ex)) {
				throw ex;
			}
			throw cannotQuote(serverMessage(ex));
		}
		PublishedPost post = new PublishedPost(Json.text(created, "id"), null, Json.text(created, "url"));
		String caveat = quote == null ? null : quote.caveat();
		if (quote != null) {
			String state = Json.text(created.path("quote"), "state");
			String extra = switch (state) {
				case "", "accepted" -> null;
				case "pending" -> caveat != null && caveat.contains("waiting") ? null : pendingCaveat(quote.author());
				default -> "the quote is " + state;
			};
			if (extra != null) {
				caveat = caveat == null ? extra : caveat + "; " + extra;
			}
		}
		return new NewPost(post, caveat);
	}

	private static String pendingCaveat(String author) {
		return "the quote is waiting for " + author + " to approve it";
	}

	private static IllegalArgumentException cannotQuote(String reason) {
		return new IllegalArgumentException("You can't quote this post on " + PLATFORM + " (" + reason + ")");
	}

	@Override
	public VoteResult vote(String postRef, List<Integer> choices) {
		JsonNode status = readPost(postRef);
		JsonNode poll = status.get("poll");
		if (!Json.isPresent(poll)) {
			throw new IllegalArgumentException("Post '" + postRef + "' has no poll");
		}
		if (poll.path("voted").asBoolean(false)) {
			return new VoteResult(PLATFORM, "already-voted", toPost(status));
		}
		if (poll.path("expired").asBoolean(false)) {
			throw new IllegalArgumentException("The poll has ended");
		}
		if (Json.text(status.get("account"), "id").equals(ownAccountId())) {
			throw new IllegalArgumentException("You can't vote in your own poll");
		}
		int options = Json.array(poll, "options").size();
		boolean multiple = poll.path("multiple").asBoolean(false);
		if (choices.stream().anyMatch(c -> c > options) || (!multiple && choices.size() > 1)) {
			throw new IllegalArgumentException(
					"This poll has " + options + " options" + (multiple ? "" : ", and allows only one choice"));
		}
		JsonNode updated;
		try {
			updated = http.post()
				.uri("/api/v1/polls/{id}/votes", Json.text(poll, "id"))
				.body(Map.of("choices", choices.stream().map(c -> c - 1).toList()))
				.retrieve()
				.body(JsonNode.class);
		}
		catch (HttpClientErrorException.NotFound ex) {
			throw postNotFound(postRef);
		}
		catch (HttpClientErrorException ex) {
			if (!isUnprocessable(ex)) {
				throw ex;
			}
			String message = serverMessage(ex);
			if (message.toLowerCase(Locale.ROOT).contains("already voted")) {
				return new VoteResult(PLATFORM, "already-voted", toPost(readPost(postRef)));
			}
			throw new IllegalArgumentException(message);
		}
		return new VoteResult(PLATFORM, "voted", toPost(status).withPoll(toPoll(Json.required(updated, PLATFORM))));
	}

	// --- Write helpers ---

	/** {@code POST} with an empty body, returning the JSON response. */
	private JsonNode post(String uriTemplate, Object... variables) {
		return Json.required(http.post().uri(uriTemplate, variables).retrieve().body(JsonNode.class), PLATFORM);
	}

	/** Maps a missing-scope 403 to a message naming the scopes to add (SPEC §5, Follow errors). */
	private static <T> T withScopes(String scopes, String tool, Supplier<T> call) {
		try {
			return call.get();
		}
		catch (HttpClientErrorException.Forbidden ex) {
			if (isScopeError(ex)) {
				throw new IllegalStateException("The Mastodon access token lacks the " + scopes + " scope(s) needed by "
						+ tool + ". Add them to the application and regenerate the token.");
			}
			throw ex;
		}
	}

	/** HTTP 422, matched by status because Spring 7 renamed the exception subclass ({@code UnprocessableContent}). */
	private static boolean isUnprocessable(RestClientResponseException ex) {
		return ex.getStatusCode().value() == 422;
	}

	private static boolean isScopeError(RestClientResponseException ex) {
		return ex.getResponseBodyAsString().contains("outside the authorized scopes");
	}

	/** The {@code error} text of a Mastodon error response, or the raw body. */
	private static String serverMessage(RestClientResponseException ex) {
		try {
			String error = Json.text(JSON.readTree(ex.getResponseBodyAsString()), "error");
			return error.isEmpty() ? ex.getResponseBodyAsString() : error;
		}
		catch (RuntimeException parseFailure) {
			return ex.getResponseBodyAsString();
		}
	}

	// --- Instance limits and domain ---

	record InstanceInfo(int maxLength, int urlLength, String domain, String source, boolean quotes, PollRules polls) {
	}

	private record InstanceState(InstanceInfo info, Instant attemptedAt) {
	}

	synchronized InstanceInfo instanceInfo() {
		Instant now = clock.instant();
		InstanceState state = instanceState;
		boolean retryDue = state == null || ("fallback".equals(state.info().source())
				&& Duration.between(state.attemptedAt(), now).compareTo(INSTANCE_RETRY_BACKOFF) >= 0);
		if (state == null || retryDue) {
			state = new InstanceState(fetchInstanceInfo(), now);
			instanceState = state;
		}
		return state.info();
	}

	private InstanceInfo fetchInstanceInfo() {
		try {
			JsonNode body = get("/api/v2/instance");
			JsonNode statuses = body.path("configuration").path("statuses");
			if (!statuses.has("max_characters") || !statuses.has("characters_reserved_per_url")) {
				throw new IllegalStateException("instance response lacks status limits");
			}
			String domain = Json.text(body, "domain");
			boolean quotes = body.path("api_versions").path("mastodon").asInt(0) >= QUOTES_API_VERSION;
			return new InstanceInfo(statuses.get("max_characters").asInt(), statuses.get("characters_reserved_per_url").asInt(),
					domain.isEmpty() ? configuredHost() : domain, "instance", quotes,
					pollRules(body.path("configuration").path("polls")));
		}
		catch (RestClientException | IllegalStateException ex) {
			log.warn("Could not read Mastodon instance limits, using configured fallback: {}", ex.getMessage());
			return new InstanceInfo(properties.mastodon().maxLength(), DEFAULT_URL_LENGTH, configuredHost(), "fallback",
					false, pollRules(null));
		}
	}

	/** Poll limits from {@code configuration.polls}, each missing value falling back to Mastodon's own default. */
	static PollRules pollRules(@Nullable JsonNode polls) {
		int maxOptions = intOr(polls, "max_options", DEFAULT_POLL_MAX_OPTIONS);
		int maxChars = intOr(polls, "max_characters_per_option", DEFAULT_POLL_MAX_OPTION_CHARS);
		int minSeconds = intOr(polls, "min_expiration", DEFAULT_POLL_MIN_EXPIRATION);
		int maxSeconds = intOr(polls, "max_expiration", DEFAULT_POLL_MAX_EXPIRATION);
		return new PollRules(maxOptions, maxChars, (minSeconds + 59) / 60, maxSeconds / 60);
	}

	private static int intOr(@Nullable JsonNode node, String field, int fallback) {
		JsonNode value = node == null ? null : node.get(field);
		return value != null && value.isNumber() ? value.asInt() : fallback;
	}

	private String instanceDomain() {
		return instanceInfo().domain();
	}

	private String configuredHost() {
		String host = URI.create(properties.mastodon().instanceUrl()).getHost();
		return host != null ? host : properties.mastodon().instanceUrl();
	}

	// --- Accounts ---

	private String ownAccountId() {
		String id = ownAccountId.get();
		if (id == null) {
			id = Json.text(get("/api/v1/accounts/verify_credentials"), "id");
			ownAccountId.set(id);
		}
		return id;
	}

	private JsonNode resolveAccount(String handle) {
		try {
			return get("/api/v1/accounts/lookup?acct={acct}", handle);
		}
		catch (HttpClientErrorException.NotFound ex) {
			List<JsonNode> found = Json.array(
					get("/api/v2/search?q={q}&type=accounts&resolve=true&limit=1", "@" + handle), "accounts");
			if (!found.isEmpty() && qualify(Json.text(found.get(0), "acct")).equalsIgnoreCase(qualify(handle))) {
				return found.get(0);
			}
			throw new IllegalArgumentException("User '" + handle + "' not found on " + PLATFORM);
		}
	}

	/** Fully qualifies an {@code acct} (no leading {@code @}): a local account gets the instance domain. */
	private String qualify(String acct) {
		return acct.contains("@") ? acct : acct + "@" + instanceDomain();
	}

	private String handleOf(JsonNode account) {
		return "@" + qualify(Json.text(account, "acct"));
	}

	private AccountSummary toAccount(JsonNode account) {
		String handle = handleOf(account);
		String displayName = Json.text(account, "display_name");
		return new AccountSummary(PLATFORM, Json.text(account, "id"), handle,
				displayName.isBlank() ? handle.substring(1) : displayName, HtmlText.toPlainText(Json.text(account, "note")),
				Json.text(account, "url"));
	}

	private List<AccountSummary> accountList(JsonNode accounts) {
		return Json.elements(accounts).stream().map(this::toAccount).toList();
	}

	private PostResult toPost(JsonNode status) {
		JsonNode s = unwrap(status);
		boolean hasQuote = Json.isPresent(s.get("quote"));
		return new PostResult(PLATFORM, Json.text(s, "id"), handleOf(s.get("account")),
				HtmlText.toPlainText(Json.text(s, "content"), hasQuote), Json.isoUtc(Json.text(s, "created_at")),
				Json.text(s, "url"), Json.number(s, "replies_count"), Json.number(s, "reblogs_count"),
				Json.number(s, "favourites_count"), hasQuote ? toQuote(s.get("quote")) : null, toPoll(s.get("poll")));
	}

	/** A boost is represented by the boosted status (SPEC §5, post mapping). */
	private static JsonNode unwrap(JsonNode status) {
		return Json.isPresent(status.get("reblog")) ? status.get("reblog") : status;
	}

	private QuoteSummary toQuote(JsonNode quote) {
		String state = switch (Json.text(quote, "state")) {
			case "blocked_account", "blocked_domain" -> "blocked";
			case "muted_account" -> "muted";
			case "" -> "unknown";
			default -> Json.text(quote, "state");
		};
		JsonNode quoted = quote.get("quoted_status");
		if (Json.isPresent(quoted)) {
			PostResult q = toPost(quoted);
			return new QuoteSummary(state, q.id(), q.author(), q.text(), q.url());
		}
		String shallowId = Json.text(quote, "quoted_status_id");
		return new QuoteSummary(state, shallowId.isEmpty() ? null : shallowId, null, null, null);
	}

	static @Nullable PollSummary toPoll(@Nullable JsonNode poll) {
		if (poll == null || !Json.isPresent(poll)) {
			return null;
		}
		List<JsonNode> options = Json.array(poll, "options");
		List<PollOption> mapped = new ArrayList<>();
		for (int i = 0; i < options.size(); i++) {
			mapped.add(new PollOption(i + 1, Json.text(options.get(i), "title"), nullableNumber(options.get(i), "votes_count")));
		}
		List<Integer> ownVotes = Json.array(poll, "own_votes").stream().map(v -> v.asInt() + 1).toList();
		return new PollSummary(List.copyOf(mapped), poll.path("multiple").asBoolean(false),
				poll.path("expired").asBoolean(false), Json.isoUtc(Json.text(poll, "expires_at")),
				Json.number(poll, "votes_count"), nullableNumber(poll, "voters_count"), poll.path("voted").asBoolean(false),
				ownVotes);
	}

	private static @Nullable Long nullableNumber(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() || value.isMissingNode() ? null : value.asLong();
	}

	private JsonNode get(String uriTemplate, Object... variables) {
		return Json.required(http.get().uri(uriTemplate, variables).retrieve().body(JsonNode.class), PLATFORM);
	}

}
