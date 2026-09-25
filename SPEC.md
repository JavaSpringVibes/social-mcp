# Product Specification: SocialMCP

## 1. Objective and Scope

**Objective:** Build an MCP (Model Context Protocol) server in Java using Spring Boot and Spring AI that lets AI agents read and publish on **Mastodon** and **Bluesky**. Agents can:
* search posts, sorted by newest or by engagement;
* read the configured user's timelines and any public user's recent posts;
* inspect the replies, likes and reposts on a post;
* read trending tags, topics and posts;
* find accounts similar to a given user;
* read profile summaries with follower/following counts;
* read each platform's posting limits and check draft posts against them;
* create new text posts, including numbered self-threads for content that is too long for one post.

The tools are designed to be combined by the agent to answer questions such as:

| Example question | Tools the agent combines |
|---|---|
| "Summarize my timeline" | `getSocialTimeline(home, limit=40)` |
| "What are the {topic} folks I follow posting right now?" | `getSocialTimeline(home, limit=40)`, then the agent filters by topic |
| "Tell me about the interactions on my recent post" | `getSocialTimeline(own, limit=1)` → `getSocialPostInteractions` |
| "What is trending about {topic}?" | `getSocialTrends` + `searchSocialPosts(query, sort=top)` |
| "Find people who post content similar to {handle}" | `findSimilarAccounts` (optionally `getSocialUserPosts` / `getSocialProfile` on the results) |
| "Post this long write-up to Mastodon and Bluesky" | For each platform: `getSocialPostingRules` → the agent splits the text into parts → `checkSocialPost` (the agent rewrites any part that is too long) → `createSocialThread` |

**Scope Boundaries:**

* **Included:** Authenticating against each platform's REST API, normalizing posts and accounts into common shapes, search with `latest`/`top` sorting, the configured user's home feed and own posts, another user's public posts by handle, reading a post's replies, likes and reposts, trending tags/topics/posts, similar-account discovery, profile summaries, publishing each platform's posting limits, validating post length on the server, publishing single posts and **self-threads** (a chain of the configured user's own posts, each replying to the previous one), exposing MCP tools via `@McpTool` annotations, and running over the STDIO transport.
* **Not Included (Out of Scope):**
  * **X (Twitter):** X already has its own MCP server, and its API is pay-per-use only (no free tier since February 2026), so supporting it here would duplicate work.
  * **Write actions other than creating posts and self-threads:** replying to other people's posts, adding to an existing thread, liking, reposting, follow/unfollow, deleting posts. (Reading replies *is* in scope.)
  * **Automatic splitting on the server:** the agent decides where to split long content. The server only measures, numbers and publishes the parts.
  * Media attachments (images/video), other users' home feeds, full follower/following **lists** (only counts are provided), notifications, webhooks/streaming, Bluesky rich-text facets (links and mentions are posted as plain, non-clickable text), markdown rendering, and pagination beyond the first page of each API call.
  * No persistent database is required.

---

## 2. Technical Stack & Versions

These versions match the project `pom.xml` and are mutually compatible (Spring AI 2.0.x requires Spring Boot 4.x / Spring Framework 7).

* **Java:** JDK 25
* **Framework:** Spring Boot 4.1.x
* **AI/MCP Framework:** Spring AI 2.0.x (`spring-ai-starter-mcp-server`, STDIO transport)
* **HTTP Client:** Spring `RestClient` (blocking), via `spring-boot-starter-restclient`
* **Configuration validation:** `spring-boot-starter-validation` (Jakarta Bean Validation / Hibernate Validator).
* **Null safety:** JSpecify annotations, enforced at compile time by NullAway running on Error Prone.
  * Every main package is `@NullMarked` (`package-info.java`), so types are non-null unless annotated `@Nullable`.
  * NullAway runs in JSpecify mode as an error, so any null-safety violation fails the build. It checks only production code, because tests pass `null` on purpose.
  * Error Prone needs the javac `--add-exports`/`--add-opens` flags in `.mvn/jvm.config`.
  * Optional MCP tool parameters are `@Nullable`. Required ones are declared non-null, so the generated JSON schema keeps them in `required`, but they are still checked for null at runtime because MCP input is untrusted.
* **Testing:** `spring-boot-starter-restclient-test` (`MockRestServiceServer` bound to a `RestClient.Builder`). No WireMock.
* **Build Tool:** Maven (wrapper included)

---

## 3. Architecture & Components

The system uses the Strategy Pattern to normalize disparate APIs behind one interface, so another platform can be added later without changing the tools.

1. **`SocialMcpTools` (`@Component`):** Entry point. Exposes the eleven tools in §4 via `@McpTool`. Resolves and validates the platform, handle, `limit`, `sort`, `type`, `parts` and `numbered` arguments (§6), applies thread numbering (§6.10), selects the matching strategy from the injected `List<SocialPlatformService>`, and delegates.
2. **`SocialPlatformService` (interface):**
   * `String platform()` — canonical lowercase id (`"mastodon"`, `"bluesky"`).
   * `boolean isConfigured()` — whether the platform's credentials are present (§6.1).
   * `boolean isValidHandle(String handle)` — the platform's handle rule from §6.7. `SocialMcpTools` normalizes the handle and calls this before any network call, so each platform owns its own rule.
   * `List<PostResult> searchPosts(String query, SearchSort sort, int limit)`, where `SearchSort` is an enum with values `LATEST` and `TOP`.
   * `List<PostResult> getTimeline(TimelineType type, int limit)`, where `TimelineType` is an enum with values `HOME` and `OWN`.
   * `List<PostResult> getUserPosts(String handle, int limit)` — `handle` is already normalized (§6.7).
   * `ProfileResult getProfile(String handle)` — a normalized handle, or `null` for the configured account.
   * `PostInteractions getPostInteractions(String postRef, int limit)` — `postRef` is a post id or URL (§6.9).
   * `TrendsResult getTrends(int limit)`
   * `SimilarAccountsResult findSimilarAccounts(String handle, int limit)`
   * `PublishedPost createPost(String content, PublishedPost root, PublishedPost parent)` — publishes one post. `root` and `parent` are both `null` for a top-level post. For a thread part, `root` is the thread's first post and `parent` is the previous part. `PublishedPost` is a record `{id, cid, url}`, where `cid` is Bluesky-only and `null` on Mastodon.
   * `PostingRules postingRules()` — the platform's limits (§4, Tool 9). On Mastodon these come from the instance (§5).
   * `PartCheck checkPart(int index, String text)` — measures one final post text, including any numbering suffix, against `postingRules()` (§6.2).
   * The `limit` passed to the read methods has already had the default applied and been capped at `social.read.max-limit`. Each service then caps it further at the endpoint max from §5 (§6.8).
3. **Records** (all serialized to JSON by Spring AI):
   * **`PostResult`** — a normalized post (§4, Tool 1).
   * **`AccountSummary`** — a normalized account in a list (§4, Tool 5).
   * **`ProfileResult`** — a profile with counts (§4, Tool 4).
   * **`PostInteractions`** — a post with its replies, likers and reposters (§4, Tool 5).
   * **`TrendsResult`** — trending tags/topics and posts (§4, Tool 6).
   * **`SimilarAccountsResult`** — similar accounts plus how they were found (§4, Tool 7).
   * **`PostingRules`** — a platform's posting limits (§4, Tool 9).
   * **`PostCheckResult`** / **`PartCheck`** — the per-part measurement of a draft (§4, Tool 10).
   * **`ThreadResult`** — the URLs of a published thread (§4, Tool 11).
   * **`PublishedPost`** — internal only (not returned by any tool). It carries the identifiers needed to reply to a post just created.
4. **`MastodonService`:** Personal Access Token (Bearer auth).
5. **`BlueskyService`:** AT Protocol (XRPC). Holds a cached session (§5, Bluesky).
6. **`PlatformAwareToolDefinitions` (`BeanPostProcessor`):** Rewrites the tool definitions Spring AI generates from `@McpTool`, so that they advertise only the configured platforms (§4, "Platform-aware tool definitions").
7. **`HtmlText`:** Stateless helper that converts Mastodon HTML (`content`, `note`) to plain text (§5, Mastodon).
8. **`SocialProperties` (`@ConfigurationProperties("social")`, `@Validated`):** Typed, non-null binding of §7, with defaults and constraints.

---

## 4. MCP Interface Contracts

JSON Schema is generated automatically from `@McpTool` / `@McpToolParam`. Every tool description must name the platforms it works on (through the `{platforms}` placeholder, below) and summarize its return shape, so the calling model can choose and chain tools without trial calls.

**Platform-aware tool definitions.** A deployment may configure only one platform (e.g. Mastodon credentials only), and the tool definitions must not advertise a platform the server can't use. At startup, `PlatformAwareToolDefinitions` rewrites every tool definition that has a `platform` parameter:
* **Tool description:** the `{platforms}` placeholder in the `@McpTool` description is replaced with the configured platforms' display names, e.g. `Mastodon` or `Mastodon or Bluesky`. Platform-specific notes in a description (e.g. "On Mastodon, …") stay as written.
* **`platform` parameter:** its `description` becomes `Platform: one of "mastodon".` (listing only configured platforms, in a fixed order: mastodon, bluesky), and an `enum` of exactly those values is added to its JSON schema.
* **No platform configured:** the placeholder becomes `a social platform (none is configured on this server)`. The parameter description says that no platform is configured and every call will fail until credentials are set, and no `enum` is added (an empty `enum` is invalid JSON Schema).
* **Schema enforcement:** the MCP SDK validates every call's arguments against the tool's input schema before the tool method runs. A `platform` value outside the `enum` is rejected with the SDK's own `isError` result, e.g. `Tool (getSocialTrends) input validation failed: … /platform: does not have a value in the enumeration ["mastodon"]`. That covers an unconfigured platform, an unknown one, and a different case such as `"Mastodon"`. The message lists the allowed values, so the calling model can correct itself.
* **What doesn't change:** tool names, the set of tools, the other parameters and the call handlers. The server's own checks in §6.1 (case-insensitive, trimmed matching, and the "not configured" / "unknown platform" messages) remain as a backstop. They are reached only when the schema has no `enum` (no platform configured) or when the tools are called directly rather than through MCP.
* **How it works:** it post-processes the `List<SyncToolSpecification>` bean that Spring AI builds from the annotations, matched by element type rather than bean name. Configuration is fixed at startup, so the definitions never need to change while the server runs, and no `tools/list_changed` notification is involved.

**Common parameters** (used by several tools, validated per §6):
* `platform` (String, required): One of the configured platforms, `"mastodon"` and/or `"bluesky"`, as advertised in the parameter's `enum` (see above). The value must match exactly, in lowercase, because the MCP SDK enforces the `enum`. (The server's own matching is case-insensitive after trimming, but that only matters for the backstop cases above.)
* `limit` (Integer, optional): Maximum number of items per returned list. Default `social.read.default-limit` (10); clamped per §6.8.
* `handle` (String): A user handle, with or without a leading `@` (§6.7). The `@McpToolParam` description must give an example for each platform (`user@mastodon.social`, `alice.bsky.social`).

### Tool 1: `searchSocialPosts`

* **Description:** Searches posts on a social media platform by keyword or hashtag, sorted by newest or by engagement.
* **Parameters:**
  * `platform`, `limit`: common.
  * `query` (String, required, non-blank): The search string.
  * `sort` (String, optional): `"latest"` (default, newest first) or `"top"` (highest engagement first). Matched case-insensitively. Any other value → `IllegalArgumentException("Unknown sort '<value>'. Use one of: latest, top")`.
* **Returns:** JSON array of `PostResult`:
  ```json
  [
    {
      "platform": "mastodon",
      "id": "113245...",
      "author": "@user@mastodon.social",
      "text": "plain text content",
      "createdAt": "2026-09-25T10:15:30Z",
      "url": "https://mastodon.social/@user/113245...",
      "replyCount": 4,
      "repostCount": 12,
      "likeCount": 57
    }
  ]
  ```
  * `text` is always plain text (Mastodon HTML stripped, see §5).
  * `createdAt` is ISO-8601 UTC.
  * `id` can be passed directly to `getSocialPostInteractions`.
  * The counts are whatever the platform reports at read time (see Mastodon caveat in §5). A missing count is `0`.
  * An empty result is `[]`, not an error.

### Tool 2: `getSocialTimeline`

* **Description:** Reads one of the configured user's timelines, newest first: either their home feed (posts from accounts they follow) or the posts they wrote themselves.
* **Parameters:**
  * `platform`, `limit`: common.
  * `type` (String, required): `"home"` for the home feed or `"own"` for the user's own posts. Matched case-insensitively after trimming. Any other value → `IllegalArgumentException("Unknown timeline type '<value>'. Use one of: home, own")`.
* **Returns:** JSON array of `PostResult`.
  * `home`: reposts/boosts are included as the original post: `author`, `text`, `url` and counts come from the original, not the reposting account.
  * `own`: only posts the user wrote. Their replies and their reposts/boosts of other people's posts are excluded (same rule as Tool 3), so `limit=1` always returns the user's latest original post.
  * On Bluesky, reposts are filtered out after the API call, so `own` may return fewer than `limit` items (§5).

### Tool 3: `getSocialUserPosts`

* **Description:** Reads the most recent public posts written by a given user, identified by handle, newest first.
* **Parameters:**
  * `platform`, `limit`: common.
  * `handle` (String, required): common.
* **Returns:** JSON array of `PostResult`.
  * Only posts the user wrote: replies and reposts/boosts are excluded (same rule as Tool 2 `own`). On Bluesky this may return fewer than `limit` items (§5).
  * A user who exists but has no visible posts → `[]`. This includes a locked Mastodon account whose posts are visible only to followers.
  * A user who can't be found → `IllegalArgumentException("User '<handle>' not found on <platform>")`.

### Tool 4: `getSocialProfile`

* **Description:** Returns a profile summary for a user, including follower, following and post counts. With no handle, it returns the configured account's own profile.
* **Parameters:**
  * `platform`: common.
  * `handle` (String, optional): common. If it is omitted, `null` or blank, the configured account is used.
* **Returns:** A JSON object (`ProfileResult`):
  ```json
  {
    "platform": "bluesky",
    "id": "did:plc:abc123",
    "handle": "@alice.bsky.social",
    "displayName": "Alice",
    "bio": "plain text bio",
    "followersCount": 1204,
    "followingCount": 310,
    "postsCount": 5821,
    "isPrivate": false,
    "createdAt": "2023-04-01T12:00:00Z",
    "url": "https://bsky.app/profile/alice.bsky.social"
  }
  ```
  * `bio` is plain text (Mastodon HTML stripped with the §5 rule); an empty bio is `""`.
  * `displayName` falls back to the handle (without `@`) when it is empty.
  * `createdAt` is `null` if the platform doesn't return it.
  * Counts are whatever the platform reports. They may be approximate or stale (see Mastodon remote accounts in §5).
  * `isPrivate` is `true` for a locked Mastodon account (new followers need approval). This is not an error.
  * A user who can't be found → `IllegalArgumentException("User '<handle>' not found on <platform>")`.
  * Profiles are fetched fresh on every call and are not cached, because counts change.

### Tool 5: `getSocialPostInteractions`

* **Description:** Shows how people interacted with a post: the post itself with its reply, repost and like counts, its direct replies, and the accounts that liked and reposted it. Works on any visible post, not only the user's own. To inspect "my latest post", first call `getSocialTimeline` with `type=own` and `limit=1`.
* **Parameters:**
  * `platform`, `limit`: common. `limit` applies to each of the three lists separately.
  * `post` (String, required): The post's `id` as returned in `PostResult`, or the post's public URL (§6.9).
* **Returns:** A JSON object (`PostInteractions`):
  ```json
  {
    "post": { "...": "PostResult, including the three counts" },
    "replies": [ { "...": "PostResult" } ],
    "likedBy": [
      {
        "platform": "bluesky",
        "id": "did:plc:xyz789",
        "handle": "@bob.bsky.social",
        "displayName": "Bob",
        "bio": "plain text bio",
        "url": "https://bsky.app/profile/bob.bsky.social"
      }
    ],
    "repostedBy": [ { "...": "AccountSummary" } ]
  }
  ```
  * `replies` are **direct** replies only (not nested replies), in the order the platform returns them (Mastodon: thread order; Bluesky: the AppView's ranking). Replies the platform marks as blocked or deleted are skipped.
  * Each list holds at most `limit` items. The totals are in `post.replyCount`, `post.repostCount` and `post.likeCount`, so the agent can say "showing 10 of 57 likes".
  * A post that can't be found → `IllegalArgumentException("Post '<post>' not found on <platform>")`.

### Tool 6: `getSocialTrends`

* **Description:** Returns what is currently trending on the platform: trending hashtags/topics and, where the platform provides them, trending posts. Trends are platform-wide (Mastodon: the configured instance's view). For "what is trending about {topic}", combine this with `searchSocialPosts` using `sort=top`.
* **Parameters:**
  * `platform`, `limit`: common. `limit` applies to `tags` and `posts` separately.
* **Returns:** A JSON object (`TrendsResult`):
  ```json
  {
    "platform": "mastodon",
    "tags": [
      { "name": "#opensource", "url": "https://mastodon.social/tags/opensource", "recentUses": 1834 }
    ],
    "posts": [ { "...": "PostResult" } ],
    "notes": "Trends reflect the mastodon.social instance only."
  }
  ```
  * `tags[].name` is the display name. Mastodon names carry a `#` prefix; Bluesky topics are free text.
  * `recentUses` is the post count the platform reports for the tag, or `null` if it reports none. What it covers differs by platform: on Mastodon it is posts over the last 7 days; on Bluesky it is posts since the trend started (`startedAt`).
  * `posts` is `[]` on platforms without a trending-posts API (Bluesky).
  * `notes` is a short plain-text caveat the agent should pass on to the user (§5 defines the text per platform).

### Tool 7: `findSimilarAccounts`

* **Description:** Finds accounts that post content similar to a given user. Bluesky uses the platform's own "similar accounts" suggestions; Mastodon uses a heuristic based on shared hashtags (see `method` in the result).
* **Parameters:**
  * `platform`, `limit`: common.
  * `handle` (String, required): common.
* **Returns:** A JSON object (`SimilarAccountsResult`):
  ```json
  {
    "platform": "mastodon",
    "method": "hashtag-overlap",
    "basedOn": ["#rustlang", "#embedded", "#opensource"],
    "accounts": [ { "...": "AccountSummary" } ]
  }
  ```
  * `method` is `"platform-suggestions"` (Bluesky) or `"hashtag-overlap"` (Mastodon).
  * `basedOn` lists the hashtags used (Mastodon), and is `[]` for Bluesky.
  * `accounts` never includes the given user or the configured account.
  * No similar accounts found → `accounts: []`, not an error.
  * A user who can't be found → `IllegalArgumentException("User '<handle>' not found on <platform>")`.

### Tool 8: `createSocialPost`

* **Description:** Publishes a single new public text post on behalf of the configured account. For content longer than the platform limit, use `createSocialThread` instead.
* **Parameters:**
  * `platform`: common.
  * `content` (String, required, non-blank): Plain text. It is posted verbatim; no markdown is rendered on either platform.
* **Returns:** String confirmation, e.g. `"Posted to bluesky: https://bsky.app/profile/alice.bsky.social/post/3k..."`.
* **Preconditions (checked in order, all before any posting call):**
  1. `social.posting-enabled` is `true`.
  2. The platform is configured.
  3. `content` is non-blank and within the platform limits (§6.2). If it is too long, the error message suggests `createSocialThread`, e.g. `"Content is 812/500 graphemes (312 over) on mastodon. Split it into parts and use createSocialThread."` (The middle uses the same `reason` text as `checkSocialPost`.)

### Tool 9: `getSocialPostingRules`

* **Description:** Returns the platform's posting limits and how length is counted, so the agent can plan how to split long content into thread parts. Agents should not count characters themselves: they should use `checkSocialPost` to measure drafts.
* **Parameters:**
  * `platform`: common.
* **Returns:** A JSON object (`PostingRules`):
  ```json
  {
    "platform": "mastodon",
    "maxLength": 500,
    "unit": "graphemes",
    "urlLength": 23,
    "maxBytes": null,
    "maxThreadParts": 10,
    "numberingFormat": " (n/N)",
    "numberingReserve": 8,
    "followUpVisibility": "unlisted",
    "countingNotes": "Counts user-perceived characters (an emoji counts as 1). Every http(s) URL counts as 23. A mention @user@domain counts only as @user.",
    "source": "instance"
  }
  ```
  ```json
  {
    "platform": "bluesky",
    "maxLength": 300,
    "unit": "graphemes",
    "urlLength": null,
    "maxBytes": 3000,
    "maxThreadParts": 10,
    "numberingFormat": " (n/N)",
    "numberingReserve": 8,
    "followUpVisibility": null,
    "countingNotes": "Counts user-perceived characters (an emoji counts as 1). URLs count at their full length.",
    "source": "fixed"
  }
  ```
  * `unit`: always `"graphemes"` (user-perceived characters) on both current platforms. The field exists so that a future platform with a different unit can report it.
  * `urlLength`: the fixed length each URL counts as, or `null` if URLs count at their full length.
  * `maxBytes`: an additional UTF-8 byte limit, or `null` if there is none.
  * `maxThreadParts`: from `social.thread.max-parts`.
  * `numberingReserve`: the length of the longest possible numbering suffix, `" (N/N)"` with N = `maxThreadParts`, e.g. `" (10/10)"` = 8. When `numbered=true`, each part's own text should stay within `maxLength - numberingReserve`.
  * `followUpVisibility`: the visibility of thread parts after the first (Mastodon, from `social.mastodon.thread-visibility`), or `null` where the platform has no visibility setting (Bluesky).
  * `source`: `"instance"` if the limits were read from the Mastodon instance, `"fallback"` if that failed and configured defaults are used, or `"fixed"` for platform-defined limits (Bluesky).
  * Requires the platform to be configured. It does **not** require `social.posting-enabled`.

### Tool 10: `checkSocialPost`

* **Description:** Measures draft post texts against the platform's limits without posting anything. Use it to check how long content was split before calling `createSocialThread`, and rewrite only the parts it reports as too long.
* **Parameters:**
  * `platform`: common.
  * `parts` (array of String, required, at least 1 item): The draft texts in order. A single-item array checks one standalone post.
  * `numbered` (Boolean, optional, default `true`): Whether to measure each part with the numbering suffix `createSocialThread` would add (§6.10). This is ignored when `parts` has one item.
* **Returns:** A JSON object (`PostCheckResult`):
  ```json
  {
    "platform": "bluesky",
    "valid": false,
    "maxLength": 300,
    "unit": "graphemes",
    "problems": ["Part 2 is 327/300 graphemes (27 over)"],
    "parts": [
      { "index": 1, "text": "First part… (1/3)", "length": 281, "bytes": 290, "ok": true, "reason": null },
      { "index": 2, "text": "Second part… (2/3)", "length": 327, "bytes": 335, "ok": false, "reason": "327/300 graphemes (27 over)" },
      { "index": 3, "text": "Third part… (3/3)", "length": 118, "bytes": 120, "ok": true, "reason": null }
    ]
  }
  ```
  * (Texts in the example are shortened with `…`.) `parts[].text` is the exact final text that would be posted, including the numbering suffix.
  * `parts[].length` is measured in `unit` using the §6.2 rules, including the suffix.
  * `parts[].bytes` is the UTF-8 byte count on platforms with `maxBytes`, otherwise `null`.
  * A blank part → `ok: false`, `reason: "blank"`.
  * More parts than `maxThreadParts` → `valid: false`, with a `problems` entry such as `"12 parts, but the maximum is 10"`. Each part is still measured.
  * `problems` entries have no trailing period. Per-part entries use the format `"Part <index> is <reason>"`, e.g. `"Part 4 is blank"`.
  * `valid` is `true` only when every part is `ok` and the part count is within the maximum.
  * Invalid drafts are **not** an error: the tool returns `valid: false` so the agent can fix them. Only argument errors throw, e.g. a missing or empty `parts` → `IllegalArgumentException("parts must contain at least one item")`.
  * Requires the platform to be configured. It does **not** require `social.posting-enabled`.

### Tool 11: `createSocialThread`

* **Description:** Publishes a self-thread: the first part is a normal public post, and each following part replies to the previous one. The parts are posted in order. Every part is checked before anything is posted, so an over-long thread is rejected as a whole. On Mastodon, parts after the first are posted as unlisted so they don't clutter followers' timelines (§5).
* **Parameters:**
  * `platform`: common.
  * `parts` (array of String, required): 1 to `maxThreadParts` texts, in order.
  * `numbered` (Boolean, optional, default `true`): Adds the ` (n/N)` suffix to each part (§6.10). This is ignored when `parts` has one item.
* **Returns:** A JSON object (`ThreadResult`):
  ```json
  {
    "platform": "mastodon",
    "partsPosted": 3,
    "urls": [
      "https://mastodon.social/@me/1132450001",
      "https://mastodon.social/@me/1132450002",
      "https://mastodon.social/@me/1132450003"
    ]
  }
  ```
  `urls[0]` is the thread's first post, which is the link to share.
* **Preconditions (checked in order, all before any posting call):**
  1. `social.posting-enabled` is `true`.
  2. The platform is configured.
  3. `parts` passes `checkSocialPost` with the same `numbered` value. Otherwise → `IllegalArgumentException("Thread rejected, nothing was posted: <problems joined with '; '>.")`, e.g. `"Thread rejected, nothing was posted: Part 2 is 327/300 graphemes (27 over); Part 4 is blank."`
* **Publishing:** Parts are posted one at a time, in order, never in parallel. Each part waits for the previous part's response, because it needs that post's identifiers (§5).
* **Partial failure:** If a part fails after earlier parts were posted, stop immediately and don't delete the posted parts. Throw `IllegalStateException("Posted <k> of <N> parts: <url1>, <url2>, .... Part <k+1> failed: <reason>. Already-posted parts were not deleted.")`, so the agent can report the partial thread to the user.
* A one-item `parts` array publishes a single top-level post with no numbering, like `createSocialPost`.

---

## 5. Platform API Specifications

**Verified 2026-09-25** against docs.joinmastodon.org, the Mastodon source (`StatusLengthValidator`), and the Bluesky lexicons in `bluesky-social/atproto` (`lexicons/`). The old docs.bsky.app API pages now redirect to endpoints.bsky.app, so the lexicon JSON is the reference. Re-check the Bluesky `unspecced` endpoints before each release.

In this section, `{n}` is the effective limit after clamping (§6.8). The **max** noted for each read endpoint is its per-request page size limit, which the service applies.

### Mastodon

* **Base URL:** `social.mastodon.instance-url` (e.g. `https://mastodon.social`).
* **Auth:** Personal Access Token (non-expiring by default). Create it under Preferences > Development > New Application with scopes `read:search read:statuses read:accounts write:statuses`.
* **Auth Header:** `Authorization: Bearer <access-token>` on every request, including endpoints that are also public.
* **HTML to text (`HtmlText`):** `<br>` and `</p><p>` become newlines, and all other tags are removed. The named entities `&amp; &lt; &gt; &quot; &apos; &nbsp;` and all numeric entities (`&#39;`, `&#x27;`) are decoded. The result is trimmed.
* **Instance domain:** `domain` from `GET /api/v2/instance`, fetched together with the posting rules and cached with them. If that fetch fails, use the host of `social.mastodon.instance-url`.
* **Fully qualified handles:** a local account's `acct` has no domain (e.g. `alice`). Every Mastodon handle this server returns (`author`, `handle`) is fully qualified: if `acct` contains no `@`, append `@<instance domain>`. The result is then prefixed with `@`, e.g. `@alice@mastodon.social`. Returned handles can therefore be passed back to any tool unchanged.
* **Post mapping (`PostResult`, used by every tool):** If the status has a non-null `reblog` (a boost), map the `reblog` object instead. `id` → `id`; `account.acct` → `author` (fully qualified); `content` → `text` (via `HtmlText`); `created_at` → `createdAt`; `url` → `url`; `replies_count` → `replyCount`; `reblogs_count` → `repostCount`; `favourites_count` → `likeCount`.
  * Caveat: for posts from other servers, the counts reflect only the interactions this instance knows about, so they can be lower than the true totals.
* **Account mapping (`AccountSummary`):** `id` → `id`; `acct` → `handle` (fully qualified); `display_name` → `displayName` (falls back to the handle without `@`); `note` → `bio` (via `HtmlText`); `url` → `url`.
* **Account resolution (by handle):** `GET /api/v1/accounts/lookup?acct={handle}`. On 404, fall back to `GET /api/v2/search?q=@{handle}&type=accounts&resolve=true&limit=1`, which fetches a remote account the instance hasn't seen yet. Use the result only if its fully qualified `acct` matches the fully qualified handle (case-insensitive). Otherwise → not found.
* **Own account id:** from `GET /api/v1/accounts/verify_credentials`, fetched once and cached for the life of the process (refreshed by every own-profile call).
* **Search** (max 40):
  * If `query` is a single hashtag (matches `^#\w+$`, compiled with `Pattern.UNICODE_CHARACTER_CLASS` so non-Latin tags such as `#日本語` match): `GET /api/v1/timelines/tag/{tag}?limit={fetch}` (tag without `#`, URL-encoded). This is more reliable than full-text search.
  * Otherwise: `GET /api/v2/search?q={query}&type=statuses&limit={fetch}`.
  * `sort=latest`: `{fetch}` = `{n}`. Results are already newest first.
  * `sort=top`: Mastodon has no engagement sort, so fetch the endpoint maximum (`{fetch}` = 40) and re-rank in the server. Order by `likeCount + 2 × repostCount + replyCount` descending, break ties by `createdAt` descending, then keep the first `{n}`.
  * Note: full-text status search only returns posts whose authors opted into search indexing (or posts the account interacted with), and it depends on how the instance is configured.
* **Timeline** (max 40):
  * `home`: `GET /api/v1/timelines/home?limit={n}`.
  * `own`: `GET /api/v1/accounts/{own id}/statuses?limit={n}&exclude_replies=true&exclude_reblogs=true`.
* **User posts** (max 40): resolve the account, then `GET /api/v1/accounts/{id}/statuses?limit={n}&exclude_replies=true&exclude_reblogs=true`.
  * Note: for a remote account, the instance only has the posts that have reached it through federation, so recent history may be incomplete.
* **Profile:**
  * Own: `GET /api/v1/accounts/verify_credentials`. Call it every time for fresh counts.
  * By handle: account resolution. The account object already has the counts, so no further call is needed.
  * Mapping: as `AccountSummary`, plus `followers_count`, `following_count`, `statuses_count` → the three counts; `locked` → `isPrivate`; `created_at` → `createdAt`.
  * Note: for a remote account, the counts are copied from its home server and can be stale. Users who hide their follow lists may still show counts.
* **Post interactions:**
  1. Resolve the post to a local status id (§6.9).
  2. `GET /api/v1/statuses/{id}` → `post`.
  3. `GET /api/v1/statuses/{id}/context` → from `descendants[]`, keep those with `in_reply_to_id == {id}` (direct replies), in the order returned, and take the first `{n}`.
  4. `GET /api/v1/statuses/{id}/favourited_by?limit={n}` (max 80) → `likedBy`.
  5. `GET /api/v1/statuses/{id}/reblogged_by?limit={n}` (max 80) → `repostedBy`.
  * A 404 on step 2 → not found. Steps 3–5 may run in any order.
  * Note: for posts from other servers, `context` only has replies this instance knows about.
* **Trends:**
  * Tags: `GET /api/v1/trends/tags?limit={n}` (max 20). Map `name` → `"#" + name`; `url` → `url`; `recentUses` = sum of `history[].uses` (the API returns these as strings covering the last 7 days).
  * Posts: `GET /api/v1/trends/statuses?limit={n}` (max 40) → `posts`.
  * `notes`: `"Trends reflect the <instance domain> instance only."`
  * If the instance has trends disabled, the endpoints return `[]`. In that case append `" This instance may have trends disabled."` to `notes` when both lists are empty.
* **Similar accounts** (`method = "hashtag-overlap"`):
  1. Resolve the account and fetch its recent posts: `GET /api/v1/accounts/{id}/statuses?limit=40&exclude_replies=true&exclude_reblogs=true`.
  2. Count hashtag use across those posts using each status's `tags[].name` (lowercased). Take the top 3 tags by count, breaking ties alphabetically. These become `basedOn` (each prefixed `#`). No hashtags → return `accounts: []`, `basedOn: []`.
  3. For each tag: `GET /api/v1/timelines/tag/{tag}?limit=40`. Collect the author of each status (the `reblog` author for boosts).
  4. Drop the target user and the configured account. Rank the remaining authors by the number of distinct `basedOn` tags they posted under, then by total matching posts, then by `acct` alphabetically. Return the first `{n}` as `AccountSummary`.
  * Note: this finds accounts that post about the same hashtags, not accounts with a similar style. It works poorly for users who rarely use hashtags.
* **Posting rules (instance limits):**
  * `GET /api/v2/instance`. Read `configuration.statuses.max_characters` → `maxLength` and `configuration.statuses.characters_reserved_per_url` → `urlLength`. Many instances allow more than 500 characters.
  * Fetch lazily on first use and cache for the life of the process (`source: "instance"`). The same response provides the instance domain (above).
  * If the request fails or either field is missing, use `social.mastodon.max-length` (default 500) and 23 (`source: "fallback"`), and log a warning. Retry the fetch on the next use, at most once per 10 minutes.
  * `unit` = `"graphemes"`, `maxBytes` = `null`, `followUpVisibility` = `social.mastodon.thread-visibility`.
  * All three endpoint fields are confirmed in the Mastodon API docs; `/api/v2/instance` needs no authentication.
* **Post:** `POST /api/v1/statuses` with header `Idempotency-Key: <random UUID>`, a new UUID per post. If the request fails with a network error or HTTP 5xx, retry it **once with the same key**. Mastodon then returns the already-created status instead of publishing a duplicate. Map the response `id` → `PublishedPost.id` and `url` → `PublishedPost.url` (`cid` = `null`).
  * Top-level post (and the first part of a thread): body `{"status": "<text>", "visibility": "public"}`.
  * Thread part 2 onward: body `{"status": "<text>", "in_reply_to_id": "<parent.id>", "visibility": "<social.mastodon.thread-visibility>"}`. The default visibility is `unlisted`: the post appears in the thread and on the profile, but not in followers' home timelines or public timelines, so a long thread doesn't clutter them. Allowed values are `unlisted` and `public`.
* **Max length:** from the posting rules above. See §6.2 for how length is counted.

### Bluesky (AT Protocol)

* **Base URL:** `social.bluesky.pds-url` (default `https://bsky.social`); XRPC paths are under `/xrpc`. The default only works for accounts hosted by Bluesky; self-hosted PDS users set their own URL. All `app.bsky.*` reads go through the PDS, which proxies them to the Bluesky AppView.
* **Session management:**
  1. Login: `POST /xrpc/com.atproto.server.createSession` with body `{"identifier": "<handle>", "password": "<app-password>"}`. Response includes `accessJwt`, `refreshJwt`, `did`, `handle`.
  2. Cache the session in memory and log in lazily on the first request. Do **not** log in per call: `createSession` is heavily rate-limited (about 30 per 5 minutes, 300 per day).
  3. When a request returns HTTP 400/401 with error `ExpiredToken`, call `POST /xrpc/com.atproto.server.refreshSession` (with `Authorization: Bearer <refreshJwt>`), replace the cached tokens, and retry the original request once. If the refresh fails, do one fresh `createSession`, then retry once.
  4. Session access must be thread-safe.
  5. Login errors: HTTP 401 `AuthenticationRequired` (wrong handle or password) → `IllegalStateException("Bluesky login failed: check BLUESKY_HANDLE and BLUESKY_APP_PASSWORD")`. `AuthFactorTokenRequired` means the account password was used on an account with email 2FA → `IllegalStateException("Bluesky login needs a second factor: use an app password (Settings > Privacy and security > App passwords)")`. `AccountTakedown` → `IllegalStateException("Bluesky account is taken down")`. Never retry a failed login automatically, because of the rate limit.
  6. Every request below (reads and `createRecord`) sends `Authorization: Bearer <accessJwt>`.
* **Post mapping (`PostResult`, from a `postView`):** `uri` → `id`; `author.handle` → `author` (prefixed `@`); `record.text` → `text`; `record.createdAt` → `createdAt`; `url` = `https://bsky.app/profile/{author.handle}/post/{rkey}`, where `rkey` is the last path segment of `uri`; `replyCount`, `repostCount`, `likeCount` → the same names.
* **Account mapping (`AccountSummary`, from a `profileView`):** `did` → `id`; `handle` → `handle` (prefixed `@`); `displayName` → `displayName` (falls back to the handle without `@`); `description` → `bio` (`""` if absent); `url` = `https://bsky.app/profile/{handle}`.
* **Not found (by handle):** an error response with message `Profile not found`, or error `InvalidRequest` on the `actor` parameter.
* **Blocks:** `getAuthorFeed` can return error `BlockedActor` (you block them) or `BlockedByActor` (they block you). Map both to `IllegalArgumentException("Posts by '<handle>' on bluesky are unavailable because of a block")`.
* **Search** (max 100): `GET /xrpc/app.bsky.feed.searchPosts?q={query}&limit={n}&sort={latest|top}`. The platform sorts natively, so no re-ranking is needed.
* **Timeline** (max 100):
  * `home`: `GET /xrpc/app.bsky.feed.getTimeline?limit={n}`.
  * `own`: `GET /xrpc/app.bsky.feed.getAuthorFeed?actor={session did}&limit={n}&filter=posts_no_replies`.
  * Both return `feed[]`. For `home`, map each `feed[].post`, which is always the original post even when the item is a repost.
  * For `own`, skip items whose `reason.$type` is `app.bsky.feed.defs#reasonRepost`. `posts_no_replies` still includes the user's reposts, and there is no filter that removes them on the server. The result may therefore hold fewer than `{n}` items; don't make a second request to fill the gap.
* **User posts** (max 100): `GET /xrpc/app.bsky.feed.getAuthorFeed?actor={handle}&limit={n}&filter=posts_no_replies`. `actor` accepts a handle or a DID directly, so no separate lookup is needed. Skip repost items and map `feed[].post`, as for `own`.
* **Profile:** `GET /xrpc/app.bsky.actor.getProfile?actor={handle | session did}`.
  * Mapping: as `AccountSummary`, plus `followersCount`, `followsCount`, `postsCount` → the three counts; `isPrivate` is always `false`; `createdAt` → `createdAt`.
* **Post interactions:**
  1. Resolve the post to an AT URI (§6.9).
  2. `GET /xrpc/app.bsky.feed.getPostThread?uri={uri}&depth=1&parentHeight=0`. `thread.post` → `post`. From `thread.replies[]`, keep items whose `$type` is `app.bsky.feed.defs#threadViewPost`, map their `.post`, and take the first `{n}`. A `thread.$type` of `app.bsky.feed.defs#notFoundPost` or `#blockedPost`, or error `NotFound`, → not found.
  3. `GET /xrpc/app.bsky.feed.getLikes?uri={uri}&limit={n}` (max 100) → `likes[].actor` → `likedBy`.
  4. `GET /xrpc/app.bsky.feed.getRepostedBy?uri={uri}&limit={n}` (max 100) → `repostedBy[]` → `repostedBy`.
* **Trends:**
  * **Primary:** `GET /xrpc/app.bsky.unspecced.getTrends?limit={n}` (max 25). Map each `trends[]` item (`trendView`): `displayName` → `name`; `url` = `https://bsky.app` + `link`; `postCount` → `recentUses`.
    * `notes`: `"Bluesky trends come from an unstable (unspecced) API; post counts cover the time since each trend started."`
  * **Fallback:** if `getTrends` returns an error or a body without `trends`, call `GET /xrpc/app.bsky.unspecced.getTrendingTopics?limit={n}` (max 25). Map each `topics[]` item (`trendingTopic`): `displayName` (or `topic` if absent) → `name`; `url` = `https://bsky.app` + `link`; `recentUses` = `null`.
    * `notes`: `"Bluesky trending topics come from an unstable (unspecced) API and have no usage counts."`
  * `posts` = `[]` in both cases, because Bluesky has no trending-posts endpoint.
  * Both endpoints are **unspecced**: they are published in the lexicons but may change or disappear without notice. If both fail (an error, including `MethodNotImplemented` or HTTP 404/501, or a body without the expected array), throw `IllegalStateException("Bluesky trending topics are currently unavailable")`. The agent can still answer topic questions with `searchSocialPosts(sort=top)`.
* **Similar accounts** (`method = "platform-suggestions"`):
  * `GET /xrpc/app.bsky.graph.getSuggestedFollowsByActor?actor={handle}` → `suggestions[]` → `AccountSummary`. The endpoint has no `limit` parameter, so drop the target user and the configured account, then keep the first `{n}`.
  * `basedOn` = `[]`. The platform may return an empty list for small or new accounts; return `accounts: []` in that case.
* **Post:** `POST /xrpc/com.atproto.repo.createRecord`:
  ```json
  {
    "repo": "<did>",
    "collection": "app.bsky.feed.post",
    "record": {
      "$type": "app.bsky.feed.post",
      "text": "<content>",
      "createdAt": "<ISO-8601 UTC timestamp>"
    }
  }
  ```
  The response contains `uri` (`at://<did>/app.bsky.feed.post/<rkey>`) and `cid`. Map `uri` → `PublishedPost.id` and `cid` → `PublishedPost.cid`. `PublishedPost.url` = `https://bsky.app/profile/{handle}/post/{rkey}`.
  * **Thread part 2 onward:** add a `reply` field to the record, referencing the thread's first post as `root` and the previous part as `parent`:
    ```json
    "record": {
      "$type": "app.bsky.feed.post",
      "text": "<text>",
      "createdAt": "<ISO-8601 UTC timestamp>",
      "reply": {
        "root":   { "uri": "<root.id>",   "cid": "<root.cid>" },
        "parent": { "uri": "<parent.id>", "cid": "<parent.cid>" }
      }
    }
    ```
  * Bluesky has no per-post visibility setting, so every part is public and `followUpVisibility` is `null`.
  * If the session expires mid-thread, the §5 session refresh-and-retry applies to the failed part only. A failed `createRecord` creates nothing, so the retry can't duplicate a part.
* **Posting rules:** fixed by the `app.bsky.feed.post` lexicon: `maxLength` = 300, `unit` = `"graphemes"`, `maxBytes` = 3000 (UTF-8), `urlLength` = `null` (URLs count at their full length, because this server doesn't generate link facets), `source` = `"fixed"`.
* **Max length:** 300 graphemes **and** 3000 UTF-8 bytes (§6.2).

---

## 6. Business Rules & Error Handling

1. **Configuration detection:**
   * Every credential in §7 has an empty default, so the app always starts even when environment variables are missing. (`@ConditionalOnProperty` is **not** used: it treats an empty value as present.)
   * A platform is *configured* when all of its credentials are non-blank. All eleven tools need the same credentials:

     | Platform | Required |
     |---|---|
     | Mastodon | `access-token` |
     | Bluesky | `handle`, `app-password` |

   * Service beans are always registered. Each service checks `isConfigured()` at call time.
   * Unknown platform string → `IllegalArgumentException("Unknown platform '<value>'. Use one of: mastodon, bluesky")`.
   * Any tool on an unconfigured platform → `IllegalArgumentException("Platform <name> is not configured")`.
2. **Length measurement (`checkPart`)** — shared by `createSocialPost`, `checkSocialPost` and `createSocialThread`, so all three always agree. It is applied to the final text, including any numbering suffix. Rejections happen before any posting call. The only network call that may happen first is the Mastodon instance-limits fetch (§5).
   * **Mastodon (`maxLength` from the instance, fallback 500):** This mirrors Mastodon's own `StatusLengthValidator`, which counts grapheme clusters.
     1. Rewrite the text: replace each URL that has an `http://` or `https://` scheme (`https?://\S+`) with `urlLength` placeholder characters (usually 23). Replace each remote mention `@user@domain` with `@user`. URLs without a scheme are not shortened.
     2. Count extended grapheme clusters in the rewritten text with `java.text.BreakIterator.getCharacterInstance()`, the same method as Bluesky.
   * **Bluesky (300 graphemes and 3000 bytes):** Count extended grapheme clusters with `java.text.BreakIterator.getCharacterInstance()`, **and** count the UTF-8 byte length. A part passes only if it is within both limits. The `reason` names whichever limit failed, e.g. `"327/300 graphemes (27 over)"` or `"3104/3000 bytes (104 over)"`.
   * Never use `String.length()` directly. It counts UTF-16 code units, which over-counts emoji.
   * A blank text (empty or only whitespace) always fails with `reason: "blank"`.
3. **Posting kill switch:** When `social.posting-enabled=false`, `createSocialPost` and `createSocialThread` throw `IllegalStateException("Posting is disabled")`. This lets an operator run the server read-only. The seven read tools, `getSocialPostingRules` and `checkSocialPost` still work.
4. **Transport / stdout hygiene:** STDIO transport. Nothing other than JSON-RPC may be written to stdout. Required properties are in §7. Logs go to a file.
5. **Error handling:** Tool methods throw exceptions without catching them. Spring AI converts an exception thrown by a tool into a `CallToolResult` with `isError: true` and the exception message as text. (Spring AI 2.0.1 appends the root cause's message on a second line, so an exception with no cause shows its message twice. This is cosmetic and comes from the framework.) That is *not* a JSON-RPC protocol error, and it lets the calling model see and react to the failure.
   * Upstream HTTP errors (`RestClientResponseException`) that aren't mapped to a specific message elsewhere in this spec → `IllegalStateException("<platform> API error <status>: <short body excerpt>")`.
   * Network failures (`ResourceAccessException`) → `IllegalStateException("<platform> is unreachable: <cause message>")`.
   * Credentials are never included in any message.
   * Apart from the Mastodon post retry and the Bluesky session refresh-and-retry (§5), requests are not retried automatically.
6. **Secrets:** Tokens and passwords must never be logged or appear in exception messages.
7. **Handle normalization and validation** (in `SocialMcpTools`, before any network call). Trim the handle and strip one leading `@`, then validate it with the platform's `isValidHandle`:

   | Platform | Accepted form | Rule |
   |---|---|---|
   | Mastodon | `user` (local to the configured instance) or `user@domain` | `^[A-Za-z0-9_]+(@[A-Za-z0-9.-]+\.[A-Za-z]{2,})?$` |
   | Bluesky | full handle `name.domain` or a `did:plc:` / `did:web:` DID | handle: `^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,}$`; or starts with `did:` |

   * A handle that fails validation → `IllegalArgumentException("Invalid <platform> handle '<value>'")`, with no HTTP call made.
   * A bare Bluesky name without a dot (e.g. `alice`) is rejected rather than guessed as `alice.bsky.social`.
   * Handles are passed to APIs URL-encoded.
8. **`limit` handling** (in `SocialMcpTools` and the services):
   * `null` → `social.read.default-limit` (default 10).
   * Less than 1 → `IllegalArgumentException("limit must be at least 1")`.
   * Otherwise the effective limit is `min(limit, social.read.max-limit, endpoint max)`. `social.read.max-limit` defaults to 40, and the endpoint max is listed in §5. A value above the cap is clamped silently, not rejected.
   * The default and cap must be stated in the `@McpToolParam` description, so the model knows it can ask for more (e.g. `limit=40` to summarize a timeline).
9. **Post reference resolution** (`getSocialPostInteractions.post`). Trim the value, then:
   * **Mastodon:**
     * All digits → a local status id, used directly.
     * An `https://` URL → `GET /api/v2/search?q={url}&type=statuses&resolve=true&limit=1`. This works for posts on any server, and the result's `id` is the local id. No result → not found.
     * Anything else → `IllegalArgumentException("Invalid mastodon post reference '<value>'")`.
   * **Bluesky:**
     * Starts with `at://` and has the form `at://<did or handle>/app.bsky.feed.post/<rkey>` → an AT URI.
     * A `https://bsky.app/profile/<handle or did>/post/<rkey>` URL → converted to `at://<authority>/app.bsky.feed.post/<rkey>`.
     * In both forms, if the authority is a handle rather than a DID, resolve it first with `GET /xrpc/com.atproto.identity.resolveHandle?handle={handle}` and use the returned `did`. A handle that can't be resolved → not found.
     * Anything else → `IllegalArgumentException("Invalid bluesky post reference '<value>'")`.
   * Validation failures make no HTTP call.
10. **Thread parts and numbering** (in `SocialMcpTools`, identical for `checkSocialPost` and `createSocialThread`):
    * `parts` must be a non-null array with at least 1 item. Otherwise → `IllegalArgumentException("parts must contain at least one item")`.
    * Part count limit: `social.thread.max-parts` (default 10, allowed range 2–25). `checkSocialPost` reports excess parts as a problem. `createSocialThread` rejects them before posting.
    * Each part is trimmed of leading and trailing whitespace. Internal line breaks are kept.
    * If `numbered` is `true` (the default) and there are 2 or more parts, append the suffix `" (i/N)"` to each part after trimming, where `i` is the 1-based position and `N` is the part count, e.g. `"…end of paragraph. (2/5)"`. The suffix counts toward the length limit.
    * If `numbered` is `false`, or there is only one part, the text is posted exactly as trimmed.
    * The server never splits, merges or rewrites parts. If a part is too long, the agent must shorten or re-split it.

---

## 7. Configuration

`src/main/resources/application.properties`:

```properties
spring.application.name=social-mcp-server

# --- STDIO / stdout hygiene ---
spring.main.web-application-type=none
spring.main.banner-mode=off
spring.ai.mcp.server.stdio=true
spring.ai.mcp.server.name=social-mcp-server
spring.ai.mcp.server.version=0.0.1
logging.console.enabled=false
logging.file.name=${SOCIAL_MCP_LOG_FILE:${java.io.tmpdir}/social-mcp-server.log}

# --- General ---
social.posting-enabled=${SOCIAL_POSTING_ENABLED:true}
social.read.default-limit=${SOCIAL_READ_DEFAULT_LIMIT:10}
social.read.max-limit=${SOCIAL_READ_MAX_LIMIT:40}
# Maximum parts in one thread (allowed range 2-25)
social.thread.max-parts=${SOCIAL_THREAD_MAX_PARTS:10}

# --- Mastodon ---
social.mastodon.instance-url=${MASTODON_INSTANCE_URL:https://mastodon.social}
social.mastodon.access-token=${MASTODON_ACCESS_TOKEN:}
# Fallback only; the real limit is read from the instance (§5)
social.mastodon.max-length=${MASTODON_MAX_LENGTH:500}
# Visibility of thread parts after the first: unlisted | public
social.mastodon.thread-visibility=${MASTODON_THREAD_VISIBILITY:unlisted}

# --- Bluesky ---
social.bluesky.pds-url=${BLUESKY_PDS_URL:https://bsky.social}
social.bluesky.handle=${BLUESKY_HANDLE:}
social.bluesky.app-password=${BLUESKY_APP_PASSWORD:}
```

`logging.console.enabled=false` is the Spring Boot 4 switch that removes the console appender. (An empty `logging.pattern.console` also keeps stdout clean, but Logback then prints an "Empty or null pattern" error to stderr.)

Every property has an in-code default (`@DefaultValue` on the `SocialProperties` record components), so no component is ever null and the app starts even without this file.

Startup validation (Bean Validation via `spring-boot-starter-validation`: `@Validated` on `SocialProperties`, `@Valid` on nested records, and `@Min`/`@Max`/`@Pattern`/`@AssertTrue` constraints). Boot reports failures as "APPLICATION FAILED TO START" with the property, value and reason. Because console logging is disabled, that report goes to the log file, and the process exits with code 1:
* `social.mastodon.thread-visibility` must be `unlisted` or `public`.
* `social.thread.max-parts` must be between 2 and 25.
* `social.read.max-limit` must be between 1 and 100, and `social.read.default-limit` must be between 1 and `social.read.max-limit`.

Any other value fails startup with a clear message. This is the only case where configuration stops the app from starting; missing credentials never do (§6.1).

Test configuration (`src/test/resources/application.properties`) sets `spring.ai.mcp.server.stdio=false` so tests don't block on stdin.

---

## 8. Testing & Acceptance Criteria

* **Routing and argument validation:** Unit tests of `SocialMcpTools` with stub strategies check that:
  * each platform string (including mixed case and surrounding whitespace, on direct calls, i.e. the backstop) routes to the correct service;
  * an unknown platform (including `x`) throws the §6.1 message;
  * `type` (`home`/`own`) and `sort` (`latest`/`top`) accept any case, and unknown values throw the §4 messages;
  * `limit`: `null` becomes 10, `0` throws, `500` is clamped to 40, and with `social.read.max-limit=100` a Bluesky search gets 100 while a Mastodon search gets 40.
* **Platform clients:** `@RestClientTest` + `MockRestServiceServer` per service check the exact request path, query parameters, headers and body, and the mapping of canned responses into each record. This includes Mastodon HTML stripping and the three counts on both platforms (Mastodon `favourites_count` → `likeCount`, etc.).
* **Search:**
  * A single-hashtag query on Mastodon uses `timelines/tag/{tag}`, including non-Latin tags such as `#日本語`. Any other query (e.g. `#rust lang`) uses `/api/v2/search`.
  * Bluesky passes `sort` through.
  * Mastodon `sort=top` requests 40 items and returns them re-ranked by the §5 score and truncated to `limit`. A canned response with known counts produces the expected order, and ties are broken by `createdAt`.
* **Timeline:**
  * On each platform, `home` and `own` call the endpoints in §5 with the correct parameters, including the reply and reblog exclusion on Mastodon `own`.
  * The Mastodon own account id lookup (`verify_credentials`) happens once across repeated `own` calls.
  * On `home`, boosts and reposts are mapped to the original post, including its counts (Mastodon `reblog`, Bluesky `feed[].post`).
  * On Bluesky `own`, a feed of 3 items where the first is a repost (`reasonRepost`) returns only the 2 authored posts, so `limit=1` returns the latest authored post.
* **User posts:**
  * The handle is normalized: `@alice.bsky.social` and `alice.bsky.social` send the same request.
  * An invalid handle on each platform fails with no HTTP call, including the Bluesky bare name `alice`.
  * Mastodon uses `accounts/lookup` first, falls back to `search?resolve=true` on 404, and rejects a search result whose `acct` doesn't match.
  * Reposts/boosts are excluded on both platforms (Mastodon `exclude_reblogs=true`, Bluesky `reasonRepost` items skipped).
  * Not found produces the §4 message on each platform.
* **Mastodon handles:** a local account (`acct: "alice"`) is returned as `@alice@<instance domain>` in `author` and `handle`, using `domain` from `/api/v2/instance` (or the `instance-url` host if that fetch fails). Passing that value back to `getSocialUserPosts` resolves the same account.
* **Profile:**
  * A missing, `null` or blank `handle` returns the configured account's profile (Mastodon `verify_credentials`, Bluesky `getProfile` with the session DID).
  * Mapping includes Mastodon bio HTML stripping (including `&nbsp;` and numeric entities), fully qualified handles, and the `displayName` fallback.
  * A locked Mastodon account returns `isPrivate: true` and does not throw.
  * Each profile call makes a fresh profile request (`verify_credentials` or `getProfile`); profiles are never cached.
* **Post interactions:**
  * Mastodon: a numeric id is used directly, and a URL goes through `search?resolve=true` first. `context` descendants are filtered to direct replies (`in_reply_to_id` matches). `favourited_by` and `reblogged_by` are called with the effective limit, which is capped at 80 by the endpoint and at `max-limit` by config.
  * Bluesky: an `at://` URI with a DID is used directly. A `bsky.app` URL and an `at://` URI with a handle are both resolved via `resolveHandle`. Non-`threadViewPost` replies (blocked or deleted) are skipped. A `notFoundPost` or `blockedPost` thread produces the §4 message.
  * An invalid reference fails with no HTTP call. Examples: `hello` on either platform, `bsky.app/profile/a.b/post/x` without a scheme on Mastodon, and a numeric id on Bluesky.
  * Each list is capped at `limit`, and `post` carries the full counts.
* **Trends:**
  * Mastodon: tags get the `#` prefix and `recentUses` summed from the string `history[].uses`. Posts come from `trends/statuses`. The tags request is capped at 20. `notes` names the instance domain, and gets the "trends disabled" sentence when both lists are empty.
  * Bluesky:
    * `getTrends` items map to `name`/`url` with `recentUses` = `postCount`, and `posts: []`.
    * If `getTrends` fails (HTTP 501, or a body without `trends`), `getTrendingTopics` is called and its topics map with `recentUses: null`. Each case gets its own `notes` text.
    * If both fail, the result is the "currently unavailable" message.
* **Similar accounts:**
  * Mastodon: with canned statuses whose top tags are known, `basedOn` holds the top 3 (ties broken alphabetically), exactly those tag timelines are requested, and authors are ranked by distinct tags, then post count, then `acct`. The target user and the configured account are excluded, and boosts count the original author. A user with no hashtags returns `accounts: []` with no tag timeline calls.
  * Bluesky: suggestions exclude the target and configured accounts and are truncated to `limit`. An empty `suggestions` list returns `accounts: []`.
* **Bluesky session:**
  * Two consecutive calls trigger exactly one `createSession`.
  * An `ExpiredToken` response triggers `refreshSession` followed by one retry.
  * `AuthenticationRequired`, `AuthFactorTokenRequired` and `AccountTakedown` on login produce the §5 messages, with no retry.
* **Bluesky blocks:** `BlockedActor` and `BlockedByActor` from `getAuthorFeed` produce the §5 block message.
* **Length measurement:**
  * Bluesky:
    * A 301-grapheme post fails with no HTTP request made (`MockRestServiceServer` verifies zero interactions).
    * 300 single-code-point emoji pass (300 graphemes, 1,200 bytes).
    * 300 family emoji (multi-code-point graphemes of about 25 bytes each) fail on the byte limit, with a `bytes` reason.
  * Mastodon, with the instance reporting `max_characters: 500` and `characters_reserved_per_url: 23`:
    * 501 graphemes fail with no `POST /statuses`.
    * 500 family emoji (500 graphemes, but about 12,500 bytes and 3,500 code points) pass, because Mastodon counts graphemes and has no byte limit.
    * 476 characters of text, a space and a 100-character URL count as 477 + 23 = 500 and pass. With 477 characters of text (501), the post fails.
    * `example.com/very/long/path` without a scheme counts at its full length, not 23.
    * `@bob@example.social` counts as 4 (`@bob`).
  * `createSocialPost`, `checkSocialPost` and `createSocialThread` give the same length for the same text (they share `checkPart`).
* **Mastodon posting rules:**
  * An instance reporting `max_characters: 1000` raises the limit to 1000 (`source: "instance"`), and a 900-character post passes.
  * `/api/v2/instance` is called once across repeated calls.
  * An instance fetch failure falls back to `social.mastodon.max-length` and 23 (`source: "fallback"`), and a later call retries the fetch after the 10-minute backoff.
* **Posting rules tool:**
  * `numberingReserve` is 8 for `max-parts=10` and 6 for `max-parts=9` (`" (9/9)"`).
  * `followUpVisibility` is `"unlisted"` by default on Mastodon and `null` on Bluesky.
  * The tool works with `posting-enabled=false`.
* **checkSocialPost:**
  * With `numbered=true` and 3 parts, each `parts[].text` ends in `" (i/3)"` and `length` includes the suffix. With `numbered=false`, there is no suffix. A single part is never numbered.
  * A part that fits only without its suffix is reported as over the limit when `numbered=true`.
  * Blank parts, over-long parts and too many parts produce `valid: false` with the right `problems`. None of these throw, and no posting call is made.
  * Empty or missing `parts` throws the §6.10 message.
  * The tool works with `posting-enabled=false`.
* **createSocialThread:**
  * Mastodon, 3 parts: three sequential `POST /api/v1/statuses` calls. Part 1 has `visibility: public` and no `in_reply_to_id`. Parts 2 and 3 have `visibility: unlisted` and `in_reply_to_id` set to the previous part's `id`. Each request has a distinct `Idempotency-Key`. With `social.mastodon.thread-visibility=public`, parts 2 and 3 are `public`.
  * Bluesky, 3 parts: three sequential `createRecord` calls. Part 1 has no `reply`. Part 2 has `root` = `parent` = part 1's `{uri, cid}`. Part 3 has `root` = part 1 and `parent` = part 2.
  * `urls` lists the three URLs in order, and `partsPosted` = 3.
  * An over-long part anywhere in the thread rejects it with the "nothing was posted" message and zero posting calls.
  * Mastodon: if part 2 of 3 gets HTTP 500 and then succeeds on the retry, both requests carry the same `Idempotency-Key`, and the thread completes.
  * If part 2 of 3 keeps failing (Mastodon: HTTP 500 on both the request and its retry; Bluesky: HTTP 500), part 3 is not attempted, nothing is deleted, and the error lists part 1's URL and part 2's failure reason.
  * The "nothing was posted" message joins problems with `"; "` and has no doubled punctuation, e.g. `"…: Part 2 is 327/300 graphemes (27 over); Part 4 is blank."`
  * If part 2 of 3 hits Bluesky `ExpiredToken`, the session is refreshed, part 2 is retried once, and the thread completes with exactly 3 `createRecord` calls that succeed.
  * A one-part thread posts a single top-level post with no suffix.
  * With `posting-enabled=false`, the tool throws and makes no HTTP call.
* **Platform-aware tool definitions:**
  * With only Mastodon configured, every tool with a `platform` parameter has `enum: ["mastodon"]`, a parameter description naming only `"mastodon"`, and `Mastodon` in place of `{platforms}`. Only-Bluesky mirrors this.
  * With both configured, the enum is `["mastodon", "bluesky"]` and the description says `Mastodon or Bluesky`.
  * With none configured, there is no `enum`, and both the tool and parameter descriptions say none is configured.
  * No tool description or schema contains a literal `{platforms}` after startup. Names, the other parameters, `required` and call handlers are unchanged. Tools without a `platform` parameter are left untouched.
  * End to end: the jar started with only `MASTODON_ACCESS_TOKEN` set lists all eleven tools in `tools/list` with `enum: ["mastodon"]` and no leftover `{platforms}`. A call with `platform: "bluesky"` or `platform: "Mastodon"` returns the SDK's `isError` enum-validation result naming `["mastodon"]`.
  * Direct (non-MCP) calls to `SocialMcpTools` still reject an unconfigured platform with the §6.1 "not configured" message.
* **Configuration:**
  * With no credentials set, the context starts, and every tool on each platform throws the §6.1 "not configured" message.
  * With `social.posting-enabled=false`, `createSocialPost` and `createSocialThread` throw, while the seven read tools, `getSocialPostingRules` and `checkSocialPost` still work.
  * Each of these fails startup with a clear message: `social.mastodon.thread-visibility=private`, `social.thread.max-parts=1`, `social.read.max-limit=0`, and `social.read.default-limit=50` with `social.read.max-limit=40`.
  * An unreachable host produces the §6.5 "is unreachable" message rather than a raw stack trace.
* **Boot check:** `java -jar target/social-mcp-server-0.0.1-SNAPSHOT.jar` waits on stdin. Sending `initialize`, then the `notifications/initialized` notification, then `tools/list` yields valid JSON-RPC responses listing all eleven tools, each with a description that names the supported platforms. Every line on stdout parses as JSON (no banner or log output).
