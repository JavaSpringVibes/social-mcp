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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
import com.socialmcp.text.HtmlText;
import com.socialmcp.text.TextLength;

/** Mastodon via its REST API with a personal access token (SPEC §5, Mastodon). */
@Service
public class MastodonService implements SocialPlatformService {

	private static final Logger log = LoggerFactory.getLogger(MastodonService.class);

	static final String PLATFORM = "mastodon";

	private static final int PAGE_MAX = 40;

	private static final int ACCOUNT_LIST_MAX = 80;

	private static final int TREND_TAGS_MAX = 20;

	private static final int DEFAULT_URL_LENGTH = 23;

	private static final Duration INSTANCE_RETRY_BACKOFF = Duration.ofMinutes(10);

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
		String ref = postRef.trim();
		boolean isId = STATUS_ID.matcher(ref).matches();
		if (!isId && !ref.startsWith("https://")) {
			throw new IllegalArgumentException("Invalid mastodon post reference '" + postRef + "'");
		}
		String id = isId ? ref : resolveStatusUrl(ref, postRef);
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
		String idempotencyKey = UUID.randomUUID().toString();
		JsonNode created;
		try {
			created = postStatus(body, idempotencyKey);
		}
		catch (HttpServerErrorException | ResourceAccessException ex) {
			// Safe to retry: Mastodon returns the already-created status for a repeated Idempotency-Key.
			created = postStatus(body, idempotencyKey);
		}
		return new PublishedPost(Json.text(created, "id"), null, Json.text(created, "url"));
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
				info.source());
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

	// --- Instance limits and domain ---

	record InstanceInfo(int maxLength, int urlLength, String domain, String source) {
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
			return new InstanceInfo(statuses.get("max_characters").asInt(), statuses.get("characters_reserved_per_url").asInt(),
					domain.isEmpty() ? configuredHost() : domain, "instance");
		}
		catch (RestClientException | IllegalStateException ex) {
			log.warn("Could not read Mastodon instance limits, using configured fallback: {}", ex.getMessage());
			return new InstanceInfo(properties.mastodon().maxLength(), DEFAULT_URL_LENGTH, configuredHost(), "fallback");
		}
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
		JsonNode s = Json.isPresent(status.get("reblog")) ? status.get("reblog") : status;
		return new PostResult(PLATFORM, Json.text(s, "id"), handleOf(s.get("account")),
				HtmlText.toPlainText(Json.text(s, "content")), Json.isoUtc(Json.text(s, "created_at")),
				Json.text(s, "url"), Json.number(s, "replies_count"), Json.number(s, "reblogs_count"),
				Json.number(s, "favourites_count"));
	}

	private JsonNode get(String uriTemplate, Object... variables) {
		return Json.required(http.get().uri(uriTemplate, variables).retrieve().body(JsonNode.class), PLATFORM);
	}

}
