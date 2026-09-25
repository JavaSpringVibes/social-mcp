# Product Specification: SocialMCP

## 1. Objective and Scope

**Objective:** Build an MCP (Model Context Protocol) server in Java using Spring Boot and Spring AI that lets AI agents read, publish and interact on **Mastodon** and **Bluesky**. Agents can:
* search posts, sorted by newest or by engagement;
* read the configured user's timelines and any public user's recent posts;
* inspect the replies, likes and reposts on a post;
* read trending tags, topics and posts;
* find accounts similar to a given user;
* read profile summaries with follower/following counts;
* read each platform's posting limits and check draft posts against them;
* create new text posts, including numbered self-threads for content that is too long for one post, posts that quote another post, and (on Mastodon) posts with a poll;
* reply to any post, including other people's;
* follow, unfollow, block, unblock, mute and unmute accounts on behalf of the configured user;
* like, repost and bookmark posts, undo each of those, and read the configured account's bookmarks;
* vote in polls (Mastodon).

The tools are designed to be combined by the agent to answer questions such as:

| Example question | Tools the agent combines |
|---|---|
| "Summarize my timeline" | `getSocialTimeline(home, limit=40)` |
| "What are the {topic} folks I follow posting right now?" | `getSocialTimeline(home, limit=40)`, then the agent filters by topic |
| "Tell me about the interactions on my recent post" | `getSocialTimeline(own, limit=1)` → `getSocialPostInteractions` |
| "What is trending about {topic}?" | `getSocialTrends` + `searchSocialPosts(query, sort=top)` |
| "Find people who post content similar to {handle}" | `findSimilarAccounts` (optionally `getSocialUserPosts` / `getSocialProfile` on the results) |
| "Post this long write-up to Mastodon and Bluesky" | For each platform: `getSocialPostingRules` → the agent splits the text into parts → `checkSocialPost` (the agent rewrites any part that is too long) → `createSocialThread` |
| "Find these people on Bluesky and follow them" | `getSocialProfile` for each candidate handle (the agent checks it is the right person) → `setAccountRelationship(action=follow)` for each one the user confirmed |
| "Unfollow / block / mute {handle}" (and the reverse) | `setAccountRelationship` with the matching `action` |
| "Like Venkat's latest post" | `getSocialUserPosts(handle, limit=1)` → `setPostAction(post=<id>, action=like)` |
| "Boost that post" / "Bookmark it for later" / "Remove my like" | `setPostAction` with `repost`, `bookmark` or `unlike` |
| "What did I bookmark about Spring AI?" | `getSocialBookmarks(limit=40)`, then the agent filters by topic |
| "Quote Craig's latest post and add my take" | `getSocialUserPosts(handle, limit=1)` → `createSocialPost(content, quote=<id>)` |
| "Ask my Mastodon followers: Java or Kotlin?" | `getSocialPostingRules` (poll limits) → `createSocialPost(content, poll={options: ["Java", "Kotlin"]})` |
| "Vote Java in that poll" / "How is my poll doing?" | read the post (its `poll`) → `voteInSocialPoll(post, choices=[1])`; for results, `getSocialTimeline(own)` or `getSocialPostInteractions` |
| "Reply to the top reply on my latest post and thank them" | `getSocialTimeline(own, limit=1)` → `getSocialPostInteractions` → the agent drafts, `checkSocialPost` → `replyToSocialPost` |

**Scope Boundaries:**

* **Included:** Authenticating against each platform's REST API, normalizing posts and accounts into common shapes, search with `latest`/`top` sorting, the configured user's home feed and own posts, another user's public posts by handle, reading a post's replies, likes and reposts, trending tags/topics/posts, similar-account discovery, profile summaries, publishing each platform's posting limits, validating post length on the server, publishing single posts and **self-threads** (a chain of the configured user's own posts, each replying to the previous one), quote posts, polls and voting (Mastodon), replying to any post, following, unfollowing, blocking, unblocking, muting and unmuting an account by handle (unfollowing also cancels a pending Mastodon follow request), liking, reposting and bookmarking a post and undoing each, reading the configured account's bookmarks, exposing MCP tools via `@McpTool` annotations, and running over the STDIO transport.
* **Not Included (Out of Scope):**
  * **X (Twitter):** X already has its own MCP server, and its API is pay-per-use only (no free tier since February 2026), so supporting it here would duplicate work.
  * **Quote and poll management:** changing who may quote your posts (Mastodon `interaction_policy`, Bluesky postgates), revoking or detaching someone else's quote, setting a quote policy on new posts (the account's default applies), editing or ending a poll early, polls with media, and polls on Bluesky (the platform has none). Quotes and polls are created only by `createSocialPost`, not in threads or replies.
  * **Other write actions:** deleting or editing posts, direct messages as a separate feature, pinning posts, approving or rejecting follow requests *from* other accounts, and managing lists. Replies are single posts: replying with a multi-part thread is not supported (the agent can reply, then continue with more replies to its own reply).
  * **Dislikes / downvotes:** neither Mastodon nor Bluesky has them. The opposite of a like in this server is `setPostAction(action=unlike)`, which removes the configured account's own like.
  * **Other kinds of block:** Mastodon domain blocks (blocking a whole server) and Bluesky moderation-list blocks (subscribing to or editing block lists). `block` and `unblock` act only on direct, one-account blocks. Reading the lists of accounts you block or mute is also out of scope. (Bookmarks *can* be read, with `getSocialBookmarks`.)
  * **Mute options:** mutes are always full and indefinite. That means no Mastodon `duration` and no `notifications=false`, and no Bluesky reposts-only or quote-posts-only mutes. Muting a whole Mastodon server, muting words, and Bluesky mute lists are also out of scope.
  * **Follow options and bulk actions:** `setAccountRelationship` acts on one account per call, and `setPostAction` on one post per call. A follow uses the platform defaults (on Mastodon: boosts shown, no post notifications, all languages) and never changes the options of an existing follow. Removing followers (as opposed to unfollowing) is not supported.
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

1. **`SocialMcpTools` (`@Component`):** Entry point. Exposes the sixteen tools in §4 via `@McpTool`. Resolves and validates the platform, handle, `limit`, `sort`, `type`, `parts`, `numbered`, `action`, `poll` and `choices` arguments (§6), applies thread numbering (§6.10) and the reply mention prefix, selects the matching strategy from the injected `List<SocialPlatformService>`, and delegates.
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
   * `PublishedPost createPost(String content, PublishedPost root, PublishedPost parent)` — publishes one plain post. `root` and `parent` are both `null` for a top-level post (including a thread's first part). For a later thread part, `root` is the thread's first post and `parent` is the previous part. `PublishedPost` is a record `{id, cid, url}`, where `cid` is Bluesky-only and `null` on Mastodon.
   * `PostingRules postingRules()` — the platform's limits (§4, Tool 9). On Mastodon these come from the instance (§5).
   * `PartCheck checkPart(int index, String text)` — measures one final post text, including any numbering suffix, against `postingRules()` (§6.2).
   * `RelationshipResult setRelationship(String handle, AccountAction action)` — performs one Tool 12 action, where `AccountAction` is an enum with values `FOLLOW`, `UNFOLLOW`, `BLOCK`, `UNBLOCK`, `MUTE`, `UNMUTE`. `handle` is already normalized (§6.7). Each action reads the current relationship first and makes no write when there is nothing to change (§5).
   * `PostActionResult setPostAction(String postRef, PostAction action)` — performs one Tool 13 action, where `PostAction` is an enum with values `LIKE`, `UNLIKE`, `REPOST`, `UNREPOST`, `BOOKMARK`, `UNBOOKMARK`. `postRef` is a post id or URL (§6.9). Each action reads the post first and makes no write when there is nothing to change (§5).
   * Both implementations dispatch on these enums with an exhaustive `switch`, so adding an action is a compile error until every platform handles it.
   * `List<PostResult> getBookmarks(int limit)` — the configured account's bookmarks (§4, Tool 15).
   * `QuoteTarget quoteTarget(String postRef)` — reads the post to quote, checks that the configured account may quote it, and returns what the new post needs (Mastodon: the status id, and the visibility the quote must use; Bluesky: the strong ref) plus an optional caveat for the confirmation (§5, **Quote**). Called before any posting call.
   * `PublishedPost createPost(String content, @Nullable QuoteTarget quote, @Nullable PollInput poll)` — publishes a top-level post with an optional quote or poll (at most one of them). With both `null` it behaves like `createPost(content, null, null)` above. A thread part never has a quote or a poll. The Bluesky implementation throws the "doesn't support polls" message for a poll.
   * `VoteResult vote(String postRef, List<Integer> choices)` — votes with 1-based choices (§4, Tool 16). The Bluesky implementation throws the "doesn't support polls" message.
   * `ReplyTarget replyTarget(String postRef)` — reads the post being replied to and returns what the reply needs: the parent post (as a `PostResult`, for `ReplyResult.inReplyTo`), the ids of the parent and the thread root, the visibility to use, and `mention`, the author acct to mention (`null` when no mention is needed: always on Bluesky, and for the configured account's own posts) (§5). `SocialMcpTools` then adds the prefix `"@" + mention + " "` unless `content` already mentions that account (Tool 14), and measures the final text with `checkPart` **before** any posting call.
   * `PublishedPost reply(ReplyTarget target, String text)` — publishes `text` (already prefixed and measured) as a reply to `target`.
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
   * **`RelationshipResult`** — the outcome of a `setAccountRelationship` action, and the account it applied to (§4, Tool 12).
   * **`PostActionResult`** — the outcome of a `setPostAction` action, and the post it applied to (§4, Tool 13).
   * **`ReplyTarget`** — internal only. What a reply needs to know about the post it answers (§5).
   * **`ReplyResult`** — the published reply and the post it answers (§4, Tool 14).
   * **`QuoteSummary`** / **`PollSummary`** (with **`PollOption`**) — the optional `quote` and `poll` fields of `PostResult` (§4, Tool 1).
   * **`PollInput`** — the `poll` parameter of `createSocialPost` (§4, Tool 8). Spring AI generates its JSON schema from the record.
   * **`VoteResult`** — the outcome of a vote and the post with its updated poll (§4, Tool 16).
   * **`QuoteTarget`** — internal only. What a quoting post needs to know about the quoted post (§5).
   * **`PollRules`** — a platform's poll limits, the `polls` field of `PostingRules` (§4, Tool 9).
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
* `post` (String): A post's `id` as returned in `PostResult`, or its public URL (§6.9).

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
      "likeCount": 57,
      "quote": null,
      "poll": null
    }
  ]
  ```
  * `quote` (`QuoteSummary`, or `null` when the post quotes nothing): the post this one quotes.
    ```json
    { "state": "accepted", "id": "113240...", "author": "@bob@example.social", "text": "the quoted text", "url": "https://..." }
    ```
    `state` is `accepted` when the quoted post can be shown. Otherwise it is one of:
    * `pending`, `rejected`, `revoked` and `unauthorized` (Mastodon only): the quoted author hasn't approved it yet, refused it, or withdrew it, or the configured account may not see the quoted post;
    * `deleted`: the quoted post no longer exists;
    * `blocked` or `muted`: the configured account blocks or mutes the quoted author (or, on Mastodon, blocks their server), or on Bluesky a block in either direction hides the post;
    * `detached` (Bluesky only): the quoted author detached their post from the quote.

    When the state isn't `accepted`, `id`, `author`, `text` and `url` may be `null`. The quoted post's own quote and poll are not included, so there is no nesting.
  * `poll` (`PollSummary`, or `null` when the post has no poll; always `null` on Bluesky, which has no polls):
    ```json
    {
      "options": [ { "number": 1, "title": "Java", "votesCount": 12 }, { "number": 2, "title": "Kotlin", "votesCount": 7 } ],
      "multiple": false,
      "expired": false,
      "expiresAt": "2026-09-26T10:15:30Z",
      "votesCount": 19,
      "votersCount": null,
      "voted": false,
      "ownVotes": []
    }
    ```
    * `number` is 1-based, and is what `voteInSocialPoll` takes.
    * `options[].votesCount` is `null` while the results aren't published yet, because the author chose to hide totals until the poll ends. The total `votesCount` is always reported.
    * `votersCount` counts unique voters, and Mastodon reports it only for multiple-choice polls. It is `null` when `multiple` is `false`, where it would equal `votesCount`.
    * `expiresAt` is `null` for a poll with no end.
    * `voted` and `ownVotes` (1-based numbers) describe the configured account's vote.
  * `text` is always plain text (Mastodon HTML stripped, see §5).
  * `createdAt` is ISO-8601 UTC.
  * `id` can be passed directly as the `post` parameter of `getSocialPostInteractions`, `setPostAction`, `replyToSocialPost` and `voteInSocialPoll`, and as the `quote` parameter of `createSocialPost`.
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

* **Description:** Publishes a single new public text post on behalf of the configured account (the one exception: quoting a Mastodon followers-only post makes the new post followers-only too, §5). It can optionally **quote** another post, or (on Mastodon only) carry a **poll**. For content longer than the platform limit, use `createSocialThread` instead. The `@McpTool` description must tell the model:
  * that `quote` and `poll` can't be combined;
  * that polls are Mastodon-only;
  * to check the limits in `getSocialPostingRules` (`polls`) before drafting a poll.
* **Parameters:**
  * `platform`: common.
  * `content` (String, required, non-blank): Plain text. It is posted verbatim; no markdown is rendered on either platform.
  * `quote` (String, optional): the post to quote, in the common `post` format. The quoted post is attached as an embedded quote, not as a link in the text.
  * `poll` (object, optional): `{options, expiresInMinutes, multiple, hideTotals}` (`PollInput`).
    * `options` (array of String, required): the answers, in order.
    * `expiresInMinutes` (Integer, optional, default 1440 = 1 day): how long the poll stays open.
    * `multiple` (Boolean, optional, default `false`): whether voters may pick more than one answer.
    * `hideTotals` (Boolean, optional, default `false`): whether to hide the counts until the poll ends.
    * The limits come from the instance (§6.12).
* **Returns:** String confirmation, e.g. `"Posted to bluesky: https://bsky.app/profile/alice.bsky.social/post/3k..."`. When the result needs a caveat, it is appended in parentheses, e.g. `"Posted to mastodon: https://… (the quote is waiting for @bob@example.social to approve it)"`.
* **Preconditions (checked in order, all before any posting call):**
  1. `social.posting-enabled` is `true`.
  2. The platform is configured.
  3. `content` is non-blank and within the platform limits (§6.2). If it is too long, the error message suggests `createSocialThread`, e.g. `"Content is 812/500 graphemes (312 over) on mastodon. Split it into parts and use createSocialThread."` (The middle uses the same `reason` text as `checkSocialPost`.) An embedded quote and a poll don't count toward the text length on either platform.
  4. `quote` and `poll` are not both given. Otherwise → `IllegalArgumentException("A post can have a quote or a poll, not both")`.
  5. With `poll`: the platform supports polls, and the poll passes §6.12. On Bluesky → `IllegalArgumentException("Bluesky doesn't support polls")`.
  6. With `quote`: the quoted post exists, is visible, and the configured account may quote it (§5, **Quote**). Otherwise → `IllegalArgumentException("Post '<quote>' not found on <platform>")` or `IllegalArgumentException("You can't quote this post on <platform> (<reason>)")`.
* `createSocialThread` and `replyToSocialPost` don't take `quote` or `poll`. To quote or poll inside a thread, the agent posts the first part with `createSocialPost` and continues with replies.

### Tool 9: `getSocialPostingRules`

* **Description:** Returns the platform's posting limits and how length is counted, so the agent can plan how to split long content into thread parts. It also says whether the platform supports quote posts, and what its poll limits are. Agents should not count characters themselves: they should use `checkSocialPost` to measure drafts.
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
    "source": "instance",
    "quotes": true,
    "polls": { "maxOptions": 4, "maxOptionLength": 50, "minExpiresInMinutes": 5, "maxExpiresInMinutes": 43829 }
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
    "source": "fixed",
    "quotes": true,
    "polls": null
  }
  ```
  * `quotes`: whether `createSocialPost` can quote on this platform. It is `true` on Bluesky. On Mastodon it is `true` when the instance reports `api_versions.mastodon` ≥ 7 (Mastodon 4.5, which added quote posts), and `false` otherwise, including when the instance fetch fell back.
  * `polls`: the poll limits (`maxOptionLength` in graphemes, expirations in whole minutes, rounded inward), or `null` where the platform has no polls (Bluesky).
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

### Tool 12: `setAccountRelationship`

* **Description:** Changes the configured account's relationship with one other account: follow, unfollow, block, unblock, mute or unmute. Each action first reads the current relationship and makes no write when the relationship is already as requested, so repeating a call is safe. The `@McpTool` description must tell the model to:
  * call it only when the user has asked for that action on that specific account, and to confirm the right person first (e.g. with `getSocialProfile`) when a handle was guessed or found by search;
  * **never block on its own judgement** (for example to deal with a rude reply), and before blocking, tell the user the side effects below unless they already made clear they know them;
  * suggest `mute` rather than `block` when the user only wants to stop seeing an account.
* **Parameters:**
  * `platform`: common.
  * `handle` (String, required): common.
  * `action` (String, required): one of `follow`, `unfollow`, `block`, `unblock`, `mute`, `unmute`. Matched case-insensitively after trimming (§6.11). The `@McpToolParam` description lists the values with a few words each.
* **Actions and side effects** (the `@McpTool` description must state them in short form):

  | Action | What it does | Side effects to mention |
  |---|---|---|
  | `follow` | Follows the account with the platform defaults (on Mastodon: boosts shown, no post notifications, all languages). | On Mastodon, a locked account or an account on another server gets a follow **request** first. |
  | `unfollow` | Removes the follow. On Mastodon it also withdraws a pending follow request. | none |
  | `block` | Blocks the account. | **Mastodon:** removes follows **in both directions** and rejects their pending follow request; unblocking later does **not** restore them; a remote server is told about the block. **Bluesky:** blocks are **public**; neither side can see or interact with the other's posts. |
  | `unblock` | Removes a **direct** block. | On Mastodon, follows removed by the block are not restored (offer `follow` afterwards). |
  | `mute` | Hides the account's posts (and, on Mastodon, its notifications) from the configured account. Full and indefinite. | Mutes are **private**: the account isn't told and can still follow, see and reply. |
  | `unmute` | Removes a **direct** mute. | none |

* **Returns:** A JSON object (`RelationshipResult`):
  ```json
  {
    "platform": "bluesky",
    "action": "follow",
    "status": "following",
    "account": { "...": "AccountSummary" },
    "note": null
  }
  ```
  * `action` echoes the normalized action (lowercase).
  * `status`:

    | Action | Status after a write | Status with no write (already so) |
    |---|---|---|
    | `follow` | `following`, or `requested` (Mastodon only) | `already-following`, `already-requested` (Mastodon only) |
    | `unfollow` | `unfollowed`, or `request-cancelled` (Mastodon only) | `not-following` |
    | `block` | `blocked` | `already-blocked` |
    | `unblock` | `unblocked` | `not-blocked` |
    | `mute` | `muted` (created, or a partial/timed mute widened to a full one) | `already-muted` |
    | `unmute` | `unmuted` | `not-muted` |

  * `account` is the account the action applied to, as an `AccountSummary` (§5 mapping), so the agent can report it with a link.
  * `note` is a short remark for the user when the status alone could mislead, and otherwise `null`. It is used for:
    * Mastodon `requested`: `"Waiting for the account to approve the request."` (locked account) or `"Waiting for <domain> to confirm; this usually takes a few seconds."` (unlocked account on another server);
    * Mastodon `unblock` when the account's whole server is also blocked;
    * Bluesky `unblock` / `unmute` when a moderation list still blocks or mutes the account.
* **Preconditions (checked in order, all before any write):**
  1. `action` is valid. Otherwise → `IllegalArgumentException("Unknown action '<value>'. Use one of: follow, unfollow, block, unblock, mute, unmute")`, with no HTTP call.
  2. `social.posting-enabled` is `true` (§6.3). Otherwise → `IllegalStateException("<Action> is disabled")`, e.g. `"Following is disabled"`, with no HTTP call.
  3. The platform is configured.
  4. `handle` is valid for the platform (§6.7).
  5. The account exists. Otherwise → `IllegalArgumentException("User '<handle>' not found on <platform>")`.
  6. The account is not the configured account itself. Otherwise → `IllegalArgumentException("You can't <action> your own account")`.
  7. `follow` only: neither account blocks the other. Otherwise → `IllegalArgumentException("Can't follow '<handle>' on <platform> because of a block")`. Other actions aren't restricted by blocks. For example, you can block someone who already blocks you, and `unfollow` goes by whatever relationship the platform reports.
  8. `unblock` on Bluesky only: an account blocked **only** through a moderation list → `IllegalArgumentException("'<handle>' is blocked through the moderation list '<list name>' on bluesky. Remove them from the list or unsubscribe from it in the Bluesky app.")`, with no write. (Editing lists is out of scope.)
* The per-action API calls, and how each status is decided, are in §5 (Mastodon and Bluesky: **Follow**, **Unfollow**, **Block**, **Unblock**, **Mute**, **Unmute**).
* One account per call. To act on several accounts, the agent calls the tool once per handle and reports each result. A failure for one handle doesn't affect the others.

### Tool 13: `setPostAction`

* **Description:** Changes the configured account's interaction with one post: like, unlike, repost, unrepost, bookmark or unbookmark. Each action first reads the post and makes no write when the post is already in the requested state, so repeating a call is safe. The `@McpTool` description must tell the model to:
  * call it only when the user has asked for that action on that specific post;
  * find post ids with `searchSocialPosts`, `getSocialTimeline`, `getSocialUserPosts`, `getSocialPostInteractions` or `getSocialBookmarks`;
  * understand that there is no dislike or downvote on either platform: `unlike` only removes the user's own like.
* **Parameters:**
  * `platform`: common.
  * `post` (String, required): the post's `id` as returned in `PostResult`, or its public URL (§6.9), as for Tool 5.
  * `action` (String, required): one of `like`, `unlike`, `repost`, `unrepost`, `bookmark`, `unbookmark`. Matched as in §6.11.
* **Actions:**

  | Action | What it does | Visible to others? |
  |---|---|---|
  | `like` / `unlike` | Likes the post ("favourite" on Mastodon) / removes the configured account's like. | Yes: the author is notified of a like. |
  | `repost` / `unrepost` | Reposts the post to the configured account's followers ("boost" on Mastodon) / removes that repost. | Yes: the repost is public and the author is notified. |
  | `bookmark` / `unbookmark` | Saves the post to the configured account's bookmarks / removes it. | No: bookmarks are private on both platforms. |

* **Returns:** A JSON object (`PostActionResult`):
  ```json
  {
    "platform": "mastodon",
    "action": "like",
    "status": "liked",
    "post": { "...": "PostResult" }
  }
  ```
  * `status`:

    | Action | Status after a write | Status with no write |
    |---|---|---|
    | `like` | `liked` | `already-liked` |
    | `unlike` | `unliked` | `not-liked` |
    | `repost` | `reposted` | `already-reposted` |
    | `unrepost` | `unreposted` | `not-reposted` |
    | `bookmark` | `bookmarked` | `already-bookmarked` |
    | `unbookmark` | `unbookmarked` | `not-bookmarked` |

  * `post` is always the **original** post: acting on a boost or repost acts on the post that was boosted. `likeCount` and `repostCount` reflect the action, per §5.
* **Preconditions (checked in order, all before any write):**
  1. `action` is valid. Otherwise → `IllegalArgumentException("Unknown action '<value>'. Use one of: like, unlike, repost, unrepost, bookmark, unbookmark")`, with no HTTP call.
  2. `social.posting-enabled` is `true` (§6.3). Otherwise → `IllegalStateException("<Action> is disabled")`, e.g. `"Liking is disabled"`, with no HTTP call.
  3. The platform is configured.
  4. `post` is a valid reference for the platform (§6.9). Otherwise → the §6.9 invalid-reference message, with no HTTP call.
  5. The post exists and is visible to the configured account. Otherwise → `IllegalArgumentException("Post '<post>' not found on <platform>")`, the same message as Tool 5.
  6. `repost` only: the post can be reposted. Otherwise → `IllegalArgumentException("This post can't be reposted on <platform> (<reason>)")`. On Mastodon, direct messages can never be boosted, and followers-only posts can be boosted only by their author (§5).
* Acting on the configured account's own posts is allowed for every action.
* The per-action API calls are in §5 (Mastodon and Bluesky: **Like**, **Unlike**, **Repost**, **Unrepost**, **Bookmark / unbookmark**).
* One post per call.

### Tool 14: `replyToSocialPost`

* **Description:** Publishes a text reply to any post, including other people's posts and the configured account's own posts, as the configured account. The `@McpTool` description must tell the model to:
  * call it only when the user has asked to reply, and to show the user the exact reply text first unless the user dictated it;
  * measure drafts with `checkSocialPost`, including the `@mention` in the measured text on Mastodon. The server adds the mention automatically (see below), and it counts toward the limit, but `checkSocialPost` doesn't know about it.

  Plain text only, as for `createSocialPost`.
* **Parameters:**
  * `platform`: common.
  * `post` (String, required): the post being replied to, as an `id` or public URL (§6.9).
  * `content` (String, required, non-blank): the reply text. It is trimmed and otherwise posted verbatim, apart from the Mastodon mention prefix below.
* **Returns:** A JSON object (`ReplyResult`):
  ```json
  {
    "platform": "mastodon",
    "url": "https://mastodon.social/@me/1132460001",
    "inReplyTo": { "...": "PostResult" },
    "text": "@alice@example.social Thanks, that fixed it!"
  }
  ```
  `url` is the new reply. `inReplyTo` is the post it answers. `text` is exactly what was published, including any prefix.
* **Mastodon mention prefix:** Mastodon notifies the author of a post about a reply only if the reply mentions them. Official clients add the mention automatically, and so does this server. If the author of the post being replied to isn't the configured account, and `content` doesn't already mention them (case-insensitive match on `@user@domain`, or `@user` for a local account), the text becomes `"@<author acct> " + content`. The author acct is fully qualified for remote accounts and bare for local ones, so it renders like it does in Mastodon's own clients. Other people mentioned in the parent post are **not** added, unlike some clients, to keep the reply from pulling in people the user didn't choose. Bluesky notifies the parent's author of every reply, so no prefix is added there.
* **Visibility (Mastodon):** the reply uses the parent post's visibility: `public` → `public`, `unlisted` → `unlisted`, `private` (followers-only) → `private`, `direct` → `direct`. This matches Mastodon's clients: a reply is never more visible than the post it answers, and a reply to a direct message stays direct. Bluesky has no visibility setting.
* **Preconditions (checked in order, all before the posting call):**
  1. `social.posting-enabled` is `true`. Otherwise → `IllegalStateException("Posting is disabled")` (a reply is a post).
  2. The platform is configured.
  3. `post` is a valid reference (§6.9), and `content` is non-blank. Otherwise → the §6.9 invalid-reference message, or `IllegalArgumentException("content must not be blank")`, with no HTTP call.
  4. The parent post exists and is visible. Otherwise → the Tool 5 not-found message.
  5. Replies are allowed. On Bluesky, the author can restrict who may reply with a threadgate. If the parent's `viewer.replyDisabled` is `true` → `IllegalArgumentException("The author of this post on bluesky has restricted who can reply")`. Mastodon has no equivalent.
  6. The final text (prefix + trimmed `content`) is within the platform limits (§6.2, measured with `checkPart`). Otherwise → `IllegalArgumentException("Reply is <reason> on <platform>. Shorten it; replies are single posts.")`. When a prefix was added, the message ends with `" The automatic mention '@<acct> ' counts toward the limit."`.
* No thread splitting: a reply is one post. For a longer answer, the agent replies, then replies again to its own reply (using `ReplyResult.url` as `post`).

### Tool 15: `getSocialBookmarks`

* **Description:** Reads the configured account's bookmarked posts, most recently bookmarked first. Bookmarks are private, so only the configured account's own bookmarks can be read. Returns a JSON array of posts (`PostResult`, as for Tool 1). The `id` of any result can be passed to `setPostAction` (for example `unbookmark`) or `replyToSocialPost`.
* **Parameters:**
  * `platform`, `limit`: common.
* **Returns:** `List<PostResult>`. The order is by **when the post was bookmarked**, not when it was written, so `createdAt` isn't necessarily descending.
* A read tool: it works with `social.posting-enabled=false`. On Mastodon it needs the `read:bookmarks` token scope (§5).
* **Mastodon** (max 40): `GET /api/v1/bookmarks?limit={n}` (scope `read:bookmarks`) → an array of Status. Map each one as a post (§5, which unwraps boosts). The API pages through a `Link` header, whose `max_id` is the bookmark id, so the order is by bookmark. Only the first page is read (§1 scope).
* **Bluesky** (max 100): `GET /xrpc/app.bsky.bookmark.getBookmarks?limit={n}` → `bookmarks[]` (`bookmarkView`). Keep only items whose `item.$type` is `app.bsky.feed.defs#postView` and map them as posts. Items that are `#notFoundPost` (deleted since it was bookmarked) or `#blockedPost` are skipped, so the result may hold fewer than `{n}` posts. No second request is made to fill the gap, as for `own` timelines. `cursor` is not used.
* No bookmarks → `[]`, not an error.

### Tool 16: `voteInSocialPoll`

* **Description:** Votes in the poll attached to a post, as the configured account. Mastodon only, because Bluesky has no polls. The `@McpTool` description must tell the model to:
  * read the poll first (any tool returning the post shows `poll` with numbered options) and vote only for the options the user chose;
  * note that a vote **can't be changed or withdrawn** on Mastodon, and that you can't vote in your own poll.
* **Parameters:**
  * `platform`: common.
  * `post` (String, required): the post carrying the poll, as an `id` or public URL (§6.9).
  * `choices` (array of Integer, required): the 1-based option `number`s from `PollSummary`. Exactly one number for a single-choice poll, and one or more distinct numbers for a multiple-choice poll.
* **Returns:** A JSON object (`VoteResult`):
  ```json
  {
    "platform": "mastodon",
    "status": "voted",
    "post": { "...": "PostResult, with the updated poll" }
  }
  ```
  `status` is `voted` (a write call was made) or `already-voted` (the account had already voted, and nothing was changed; `post.poll.ownVotes` shows the earlier vote). Retrying after a vote therefore reports `already-voted`, not an error.
* **Preconditions (checked in order, all before the vote call):**
  1. `social.posting-enabled` is `true` (§6.3). Otherwise → `IllegalStateException("Voting is disabled")`.
  2. The platform is configured. On Bluesky → `IllegalArgumentException("Bluesky doesn't support polls")`, with no HTTP call.
  3. `post` is a valid reference (§6.9). `choices` is non-empty, has no duplicates, and every number is ≥ 1 (§6.13). Otherwise → the §6.9 invalid-reference message or `IllegalArgumentException("choices must be distinct option numbers starting at 1")`, with no HTTP call.
  4. The post exists and is visible (the Tool 5 not-found message) and has a poll. Otherwise → `IllegalArgumentException("Post '<post>' has no poll")`.
  5. If the account has already voted → `already-voted`, and the checks below are skipped.
  6. The poll hasn't ended. Otherwise → `IllegalArgumentException("The poll has ended")`.
  7. The poll isn't the configured account's own. Otherwise → `IllegalArgumentException("You can't vote in your own poll")` (Mastodon's `VoteValidator` forbids it).
  8. Every choice is ≤ the number of options, and a single-choice poll gets exactly one choice. Otherwise → `IllegalArgumentException("This poll has <k> options<, and allows only one choice>")`.

---

## 5. Platform API Specifications

**Verified 2026-09-25** against docs.joinmastodon.org, the Mastodon source (`StatusLengthValidator`, `StatusPolicy`, `FollowService`, `FollowLimitValidator`, `UnfollowService`, `BlockService`, `FavouriteService`, `PollOptionsValidator`, `PollExpirationValidator`, `VoteValidator`), and the Bluesky lexicons in `bluesky-social/atproto` (`lexicons/`). The old docs.bsky.app API pages now redirect to endpoints.bsky.app, so the lexicon JSON is the reference. Re-check the Bluesky `unspecced` endpoints before each release.

In this section, `{n}` is the effective limit after clamping (§6.8). The **max** noted for each read endpoint is its per-request page size limit, which the service applies.

### Mastodon

* **Base URL:** `social.mastodon.instance-url` (e.g. `https://mastodon.social`).
* **Auth:** Personal Access Token (non-expiring by default). Create it under Preferences > Development > New Application with scopes `read:search read:statuses read:accounts write:statuses read:follows write:follows write:blocks write:mutes write:favourites write:bookmarks read:bookmarks`. `write:statuses` covers posts (including quotes and polls), threads, replies, reposts and votes. The rest are needed only by the tools that use them: `read:follows` by `setAccountRelationship` (it covers `GET /api/v1/accounts/relationships`), `write:follows` by follow and unfollow, `write:blocks` by block and unblock, `write:mutes` by mute and unmute, `write:favourites` by like and unlike, `write:bookmarks` by bookmark and unbookmark, and `read:bookmarks` by `getSocialBookmarks`. A token created without them keeps working for every other tool. To add them later, tick the scopes on the application, save, then use **Regenerate access token** (Mastodon doesn't widen an existing token) and update `MASTODON_ACCESS_TOKEN`.
* **Auth Header:** `Authorization: Bearer <access-token>` on every request, including endpoints that are also public.
* **HTML to text (`HtmlText`):** first, when the status has a `quote`, remove every element with the CSS class `quote-inline`, including its content. Mastodon prepends a `<p class="quote-inline">RE: <a …>…</a></p>` fallback link to quote posts for older clients, and its docs tell clients to hide it, because the quote is shown separately. Then `<br>` and `</p><p>` become newlines, and all other tags are removed. The named entities `&amp; &lt; &gt; &quot; &apos; &nbsp;` and all numeric entities (`&#39;`, `&#x27;`) are decoded. The result is trimmed.
* **Instance domain:** `domain` from `GET /api/v2/instance`, fetched together with the posting rules and cached with them. If that fetch fails, use the host of `social.mastodon.instance-url`.
* **Fully qualified handles:** a local account's `acct` has no domain (e.g. `alice`). Every Mastodon handle this server returns (`author`, `handle`) is fully qualified: if `acct` contains no `@`, append `@<instance domain>`. The result is then prefixed with `@`, e.g. `@alice@mastodon.social`. Returned handles can therefore be passed back to any tool unchanged.
* **Post mapping (`PostResult`, used by every tool):** If the status has a non-null `reblog` (a boost), map the `reblog` object instead. `id` → `id`; `account.acct` → `author` (fully qualified); `content` → `text` (via `HtmlText`); `created_at` → `createdAt`; `url` → `url`; `replies_count` → `replyCount`; `reblogs_count` → `repostCount`; `favourites_count` → `likeCount`; `quote` and `poll` as below.
  * **`quote`** ← `status.quote` (Mastodon 4.4+; absent or `null` → `null`). `quote.state` maps unchanged for `pending`, `accepted`, `rejected`, `revoked`, `deleted` and `unauthorized`. `blocked_account` and `blocked_domain` map to `blocked`, and `muted_account` to `muted`. An unrecognized future state is passed through unchanged. When `quote.quoted_status` is present, it fills `id`, `author`, `text` and `url` with the post mapping, without its own `quote` or `poll`. A `ShallowQuote` carries only `quoted_status_id`, so it fills just `id`, and the other fields are `null`.
  * **`poll`** ← `status.poll` (`null` → `null`). `options[i]` → `{number: i + 1, title, votesCount: options[i].votes_count}` (the count is `null` when hidden). `multiple`, `expired`, `expires_at` → `expiresAt`, `votes_count` → `votesCount`, `voters_count` → `votersCount`, `voted` (`false` when absent) and `own_votes` (0-based) → `ownVotes` (+1 each, `[]` when absent).
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
  * The same response also provides:
    * `api_versions.mastodon` (Mastodon 4.3+) → `quotes` = the value ≥ 7. Quote posts arrived with API version 7 (Mastodon 4.5). If the field is missing, or the fetch fell back, `quotes` = `false`.
    * `configuration.polls.max_options`, `max_characters_per_option`, `min_expiration` and `max_expiration` (seconds) → `polls`. If any is missing, use Mastodon's own defaults from `PollOptionsValidator` and `PollExpirationValidator`: 4 options, 50 graphemes per option, 5 minutes, 1 month (2,629,746 seconds). The minute values are `ceil(min / 60)` and `floor(max / 60)`.
  * All of these fields are documented in the Mastodon API docs, and `/api/v2/instance` needs no authentication.
* **Post:** `POST /api/v1/statuses` with header `Idempotency-Key: <random UUID>`, a new UUID per post. If the request fails with a network error or HTTP 5xx, retry it **once with the same key**. Mastodon then returns the already-created status instead of publishing a duplicate. Map the response `id` → `PublishedPost.id` and `url` → `PublishedPost.url` (`cid` = `null`).
  * Top-level post (and the first part of a thread): body `{"status": "<text>", "visibility": "public"}`.
  * Thread part 2 onward: body `{"status": "<text>", "in_reply_to_id": "<parent.id>", "visibility": "<social.mastodon.thread-visibility>"}`. The default visibility is `unlisted`: the post appears in the thread and on the profile, but not in followers' home timelines or public timelines, so a long thread doesn't clutter them. Allowed values are `unlisted` and `public`.
* **Max length:** from the posting rules above. See §6.2 for how length is counted.
* **Reading a post for an action** (used by quote, vote, reply and every `setPostAction` action): resolve the reference to a local status id (§6.9), `GET /api/v1/statuses/{id}` (404 → the Tool 5 not-found message; Mastodon also returns 404 for a post the account isn't allowed to see), and continue with `status.reblog` if it is non-null. The API doesn't unwrap boosts itself: acting on a boost's id would act on the boost wrapper, not on the post the user saw.
* **Quote** (`quoteTarget`, then the post):
  1. If `quotes` is `false` (see posting rules) → `IllegalArgumentException("You can't quote this post on mastodon (this server doesn't support quote posts; it needs Mastodon 4.5 or later)")`, with no further call.
  2. Read the post (above).
  3. Check `status.visibility`. `direct` → the "can't quote" message with reason `it is a direct message`. Mastodon never allows quoting direct posts.
  4. Check `status.quote_approval.current_user`:
     * `automatic` → allowed;
     * `manual` → allowed, but the quote starts as `pending`, so the caveat is `"the quote is waiting for @<author> to approve it"`;
     * `denied` or `unknown` → the "can't quote" message with reason `the author doesn't allow you to quote it`. Mastodon's docs say to treat `unknown` as denied.
     * If `quote_approval` is absent, the author's server predates quote policies → treat as `unknown`.
  5. The new post's visibility is `public`, except when the quoted post is `private` (followers-only). Then Mastodon restricts the quote to `private`/`direct`, so the post is sent as `private`, and the caveat is `"posted as followers-only, because the quoted post is followers-only"`.
  6. `POST /api/v1/statuses` with `{"status": "<content>", "visibility": "<above>", "quoted_status_id": "<status.id>"}` plus the usual `Idempotency-Key` and single retry. `quote_approval_policy` isn't sent, so the account's default applies.
  7. If the response's `quote.state` is `pending` and no caveat was set yet, the caveat becomes `"the quote is waiting for @<author> to approve it"`. Any other state apart from `accepted` → the caveat is `"the quote is <state>"`. The post exists either way, so this is never an error.
  * A 422 on step 6 (for example, the policy changed after step 4) → the "can't quote" message with the server's explanation.
  * Length: only `content` is measured (§6.2). If the text doesn't already link to the quoted post, Mastodon adds its `quote-inline` fallback on its own side (see `HtmlText`). If an instance ever counts that fallback and rejects the post, the 422 surfaces through §6.5.
* **Poll** (create):
  * `POST /api/v1/statuses` with `{"status": "<content>", "visibility": "public", "poll": {"options": [...], "expires_in": <expiresInMinutes × 60>, "multiple": <bool>, "hide_totals": <bool>}}` plus the usual `Idempotency-Key` and single retry.
  * All §6.12 checks run first, so a 422 from the server means the instance's limits differ from what it reported. Map it through §6.5.
* **Vote:**
  1. Read the post (above). `status.poll` null → the "has no poll" message.
  2. `poll.voted` → `already-voted`, with `post` mapped from `status` and no write.
  3. `poll.expired` → "The poll has ended". A `status.account.id` equal to the own account id → "You can't vote in your own poll". Choice range and single-choice checks per Tool 16.
  4. `POST /api/v1/polls/{poll.id}/votes` with `{"choices": [<choice − 1>, ...]}` (0-based; scope `write:statuses`). The response is the updated `Poll`. Put it into the post mapped in step 1 → `voted`.
  * A 422 whose body says the account has already voted (a race with another client) → re-read the status and return `already-voted`. Other 422s (for example the poll expired in between) → `IllegalArgumentException` with the server's message. A 404 → not found.
* **Follow:**
  1. Resolve the account (account resolution, above) → `account`. Not found → the §4 not-found message.
  2. If `account.id` equals the own account id → the §4 "own account" message.
  3. `GET /api/v1/accounts/relationships?id[]={account.id}` (scope `read:follows`). Read the first element of the returned array:
     * `blocking` or `blocked_by` is `true` → the §4 block message.
     * `following` is `true` → `already-following`.
     * `requested` is `true` → `already-requested`.
     * In both "already" cases, return without calling the follow endpoint. Mastodon's follow is idempotent, but repeating it **resets** the follow options (`reblogs`, `notify`, `languages`) to the values sent, which would silently undo choices the user made in the Mastodon app.
  4. Otherwise `POST /api/v1/accounts/{account.id}/follow` with an empty body (scope `write:follows`). The platform defaults apply: `reblogs=true`, `notify=false`, all languages. The response is a `Relationship`: `following: true` → `following`; `requested: true` → `requested`, with `note` set per Tool 12 (`account.locked` → the approval note; otherwise the account is remote, and the note names the domain from its fully qualified handle). Any other response → `IllegalStateException("Mastodon did not confirm the follow of '<handle>'")`.
  * **Pending requests:** Mastodon's `FollowService` creates a follow *request* instead of a follow when the target is locked, **or** when the target is on another server (a remote ActivityPub account), even if it is unlocked. A remote request normally turns into a follow within seconds, once the other server sends its `Accept`. The tool reports the state at the time of the call and does not wait.
  * **Errors:**
    * HTTP 403 whose body contains `outside the authorized scopes` → `IllegalStateException("The Mastodon access token lacks the <scopes> scope(s) needed by <tool>. Add them to the application and regenerate the token.")`, where `<scopes>` is `read:follows, write:follows` for follow and unfollow, `read:follows, write:blocks` for block and unblock, `read:follows, write:mutes` for mute and unmute, `write:favourites` for like and unlike, `write:bookmarks` for bookmark and unbookmark, and `read:bookmarks` for `getSocialBookmarks`. `<tool>` names the tool and action, e.g. `setAccountRelationship (block)`. This can happen on the relationships call or on the write call. The same mapping applies to every tool that needs one of these scopes.
    * Any other HTTP 403 from step 4 → `IllegalArgumentException("Mastodon doesn't allow following '<handle>' (the account may have moved, or its server may be blocked)")`. The `FollowService` raises `NotPermittedError` for blocks, a moved target and domain blocks, and the block case was already caught in step 3.
    * HTTP 422 (for example the follow limit: local accounts can follow at most 7,500 accounts, or 1.1 × their follower count if that is higher; the limits are configurable per server) → the §6.5 generic `API error` message, which includes the server's explanation.
  * The follow call is **not** retried automatically (§6.5). Re-running the tool is safe, because step 3 detects a follow that was already created.
* **Unfollow:**
  1. Resolve the account → `account`, and reject the own account, exactly as for follow (steps 1–2), using the Tool 12 messages.
  2. `GET /api/v1/accounts/relationships?id[]={account.id}`:
     * `following` is `true` → the result will be `unfollowed`.
     * Otherwise `requested` is `true` → the result will be `request-cancelled`.
     * Neither → `not-following`, with no unfollow call.
  3. `POST /api/v1/accounts/{account.id}/unfollow` with an empty body (scope `write:follows`). Mastodon's `UnfollowService` removes the follow, or if there is none, withdraws the pending follow request. For a remote account it also sends an ActivityPub `Undo` to the other server in the background. The response `Relationship` must have `following: false` and `requested: false`. Otherwise → `IllegalStateException("Mastodon did not confirm the unfollow of '<handle>'")`.
  * **Errors:** the same missing-scopes 403 mapping as follow. Other errors → §6.5.
  * The endpoint succeeds even when nothing is followed, so a repeated call is harmless. Step 2 exists to report `not-following` accurately, not to protect against a failure.
* **Block:**
  1. Resolve the account → `account`, and reject the own account, as for follow (steps 1–2), using the Tool 12 messages.
  2. `GET /api/v1/accounts/relationships?id[]={account.id}`: `blocking` is `true` → `already-blocked`, with no block call. `blocked_by` doesn't matter.
  3. `POST /api/v1/accounts/{account.id}/block` with an empty body (scope `write:blocks`). The response `Relationship` must have `blocking: true` → `blocked`. Otherwise → `IllegalStateException("Mastodon did not confirm the block of '<handle>'")`.
  * **What Mastodon does** (`BlockService`): it unfollows in both directions, rejects the target's pending follow request, removes the accounts from each other's collections, deletes notification permissions between them, and for a remote account delivers an ActivityPub `Block` to the target's server. The docs warn that the call "may return before the block has been applied to the user's timelines", so posts from the account can briefly remain in the home timeline. The endpoint is idempotent.
  * **Errors:** the missing-scopes 403 mapping (above). Other errors → §6.5.
* **Unblock:**
  1. Resolve the account and reject the own account, using the Tool 12 messages.
  2. `GET /api/v1/accounts/relationships?id[]={account.id}`: `blocking` is `false` → `not-blocked`, with no unblock call. A `domain_blocking: true` in the same response means the account's whole server is blocked. That is out of scope, so the account stays hidden even after `unblocked`. When it is `true`, set `note` to `"Their server <domain> is also blocked; manage domain blocks in Mastodon's settings."`.
  3. `POST /api/v1/accounts/{account.id}/unblock` with an empty body (scope `write:blocks`). The response must have `blocking: false` → `unblocked`. Otherwise → `IllegalStateException("Mastodon did not confirm the unblock of '<handle>'")`. Follows removed by the block are not restored.
* **Like:**
  1. Read the post (above) → `status`.
  2. `status.favourited` is `true` → `already-liked`, with `post` mapped from `status` and no write.
  3. Otherwise `POST /api/v1/statuses/{status.id}/favourite` with an empty body (scope `write:favourites`). The response is the updated Status: `favourited: true` → `liked`, with `post` mapped from the response, so `likeCount` already includes the new like. Otherwise → `IllegalStateException("Mastodon did not confirm the like of '<post>'")`.
  * **What Mastodon does** (`FavouriteService`): it checks that the account may favourite the post, notifies a local author, sends an ActivityPub `Like` to a remote author's server, and counts the post toward trends. The endpoint is idempotent (a second favourite returns the existing one).
  * **Errors:** the missing-scopes 403 mapping (above). A 404 on step 3 (the post was deleted in between) → the not-found message. Other errors → §6.5.
* **Unlike:**
  1. Read the post (above).
  2. `status.favourited` is `false` → `not-liked`, with no write.
  3. `POST /api/v1/statuses/{status.id}/unfavourite` with an empty body. The response must have `favourited: false` → `unliked`, with `post` mapped from the response. Otherwise → `IllegalStateException("Mastodon did not confirm the unlike of '<post>'")`. For a remote post, Mastodon sends an `Undo` of the `Like` in the background.
  * The endpoint is idempotent too: if there is no favourite, it returns the status with `favourited: false`.
* **Reply:**
  * `replyTarget`: read the post (above). The parent is `status.id`. The visibility is `status.visibility`. `mention` is `status.account.acct` exactly as the API returns it (bare for local accounts, `user@domain` for remote ones), or `null` when the author is the configured account. `SocialMcpTools` adds the prefix (Tool 14).
  * `reply`: `POST /api/v1/statuses` with body `{"status": "<text>", "in_reply_to_id": "<parent id>", "visibility": "<parent visibility>"}`. Use the same `Idempotency-Key` and single retry on network errors or 5xx as for posts (above). Map the response as for posts (`url`).
  * Length: the mention prefix counts as `@user` only (§6.2), so a long remote domain costs nothing extra.
* **Repost (boost):**
  1. Read the post. `status.reblogged` is `true` → `already-reposted`, with no write.
  2. `status.visibility` is `direct`, or it is `private` and the author isn't the configured account → `IllegalArgumentException("This post can't be reposted on mastodon (it is <a direct message | followers-only>)")`, with no write. This mirrors Mastodon's `StatusPolicy#reblog?`: `!requires_mention? && (!private? || owned?) && show? && !blocking_author?`. Direct messages can never be boosted, not even your own, and a self-boost of a followers-only post is allowed.
  3. `POST /api/v1/statuses/{status.id}/reblog` with an empty body (scope `write:statuses`; no `visibility`, so the boost is public). The response is the **boost wrapper**: map `post` from its `reblog` field, and require `reblog.reblogged: true` → `reposted`. Otherwise → `IllegalStateException("Mastodon did not confirm the repost of '<post>'")`.
  * A 403 on step 3 (for example, the configured account blocks the author) → `IllegalArgumentException("This post can't be reposted on mastodon (not allowed)")`, unless it is the missing-scopes 403.
* **Unrepost:** read the post. `reblogged` is `false` → `not-reposted`. Otherwise `POST /api/v1/statuses/{status.id}/unreblog` (scope `write:statuses`). The response is the original status, and it must have `reblogged: false` → `unreposted`. Idempotent.
* **Bookmark / unbookmark:** read the post, then check `status.bookmarked`: `true` → `already-bookmarked`, `false` → `not-bookmarked`, with no write in either case. Otherwise `POST /api/v1/statuses/{status.id}/bookmark` or `/unbookmark` (scope `write:bookmarks`). The response must have the new `bookmarked` value → `bookmarked` / `unbookmarked`. Both endpoints are idempotent. Bookmarks are private.
* **Mute:**
  1. Resolve the account and reject the own account (as for block, with the Tool 12 messages).
  2. `GET /api/v1/accounts/relationships?id[]={account.id}`: if `muting` is `true`, `muting_notifications` is `true` and `muting_expires_at` is `null`, the account is already fully and indefinitely muted → `already-muted`, with no write. A partial mute (statuses only) or a timed mute is widened by step 3, because calling mute again replaces the stored options.
  3. `POST /api/v1/accounts/{account.id}/mute` with an empty body (scope `write:mutes`; defaults `notifications=true`, `duration=0` meaning indefinite). The response must have `muting: true` → `muted`. Otherwise → `IllegalStateException("Mastodon did not confirm the mute of '<handle>'")`.
* **Unmute:** resolve the account and reject the own account (Tool 12 messages). Relationship `muting: false` → `not-muted`. Otherwise `POST /api/v1/accounts/{account.id}/unmute` (scope `write:mutes`). The response must have `muting: false` → `unmuted`. Idempotent.

### Bluesky (AT Protocol)

* **Base URL:** `social.bluesky.pds-url` (default `https://bsky.social`); XRPC paths are under `/xrpc`. The default only works for accounts hosted by Bluesky; self-hosted PDS users set their own URL. All `app.bsky.*` reads go through the PDS, which proxies them to the Bluesky AppView.
* **Session management:**
  1. Login: `POST /xrpc/com.atproto.server.createSession` with body `{"identifier": "<handle>", "password": "<app-password>"}`. Response includes `accessJwt`, `refreshJwt`, `did`, `handle`.
  2. Cache the session in memory and log in lazily on the first request. Do **not** log in per call: `createSession` is heavily rate-limited (about 30 per 5 minutes, 300 per day).
  3. When a request returns HTTP 400/401 with error `ExpiredToken`, call `POST /xrpc/com.atproto.server.refreshSession` (with `Authorization: Bearer <refreshJwt>`), replace the cached tokens, and retry the original request once. If the refresh fails, do one fresh `createSession`, then retry once.
  4. Session access must be thread-safe.
  5. Login errors: HTTP 401 `AuthenticationRequired` (wrong handle or password) → `IllegalStateException("Bluesky login failed: check BLUESKY_HANDLE and BLUESKY_APP_PASSWORD")`. `AuthFactorTokenRequired` means the account password was used on an account with email 2FA → `IllegalStateException("Bluesky login needs a second factor: use an app password (Settings > Privacy and security > App passwords)")`. `AccountTakedown` → `IllegalStateException("Bluesky account is taken down")`. Never retry a failed login automatically, because of the rate limit.
  6. Every request below, reads and writes alike, sends `Authorization: Bearer <accessJwt>`, and the refresh-and-retry in step 3 applies to all of them.
* **Post mapping (`PostResult`, from a `postView`):** `uri` → `id`; `author.handle` → `author` (prefixed `@`); `record.text` → `text`; `record.createdAt` → `createdAt`; `url` = `https://bsky.app/profile/{author.handle}/post/{rkey}`, where `rkey` is the last path segment of `uri`; `replyCount`, `repostCount`, `likeCount` → the same names; `poll` = `null` (Bluesky has no polls); `quote` as below.
  * **`quote`** ← `postView.embed` when its `$type` is `app.bsky.embed.record#view`, or `app.bsky.embed.recordWithMedia#view` (use its `.record`). Otherwise `null`. The embed's `record`:
    * `#viewRecord` whose `value.$type` is `app.bsky.feed.post` → `state: "accepted"`, `id` = `uri`, `author` = `@` + `author.handle`, `text` = `value.text`, and `url` built as for posts;
    * `#viewNotFound` → `deleted`; `#viewBlocked` → `blocked`; `#viewDetached` → `detached` (the quoted author removed the quote with a postgate); the other fields are `null`;
    * an embedded feed, list, labeler or starter pack (not a post) → `quote` = `null`.
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
* **Posting rules:** fixed by the `app.bsky.feed.post` lexicon: `maxLength` = 300, `unit` = `"graphemes"`, `maxBytes` = 3000 (UTF-8), `urlLength` = `null` (URLs count at their full length, because this server doesn't generate link facets), `source` = `"fixed"`, `quotes` = `true`, `polls` = `null`.
* **Reading a post for an action** (used by quote, reply and every `setPostAction` action): resolve the reference to an AT URI (§6.9), then `GET /xrpc/app.bsky.feed.getPosts?uris={uri}` (max 25 URIs, one used here) → `posts[0]` (`postView`). An empty `posts` array → the Tool 5 not-found message: the AppView leaves out posts that were deleted or that a block hides. Bluesky feed items have no separate repost id, and a `PostResult.id` always names the original post (§5 timeline mapping), so no unwrapping is needed.
* **Quote** (`quoteTarget`, then the post):
  1. Read the post (above).
  2. `postView.viewer.embeddingDisabled: true` → `IllegalArgumentException("You can't quote this post on bluesky (the author has disabled quoting)")`. The author disables quoting with a postgate `disableRule`.
  3. `createRecord` in `app.bsky.feed.post` as for a top-level post (§5 Post), with the record also carrying:
     ```json
     "embed": {
       "$type": "app.bsky.embed.record",
       "record": { "uri": "<postView.uri>", "cid": "<postView.cid>" }
     }
     ```
     There is no approval step: the quote is live immediately, and there's no caveat. The quoted author can later detach it, which is out of scope.
  * The embed doesn't count toward the 300-grapheme text limit.
* **Polls and votes:** not supported. `createSocialPost` with `poll`, and `voteInSocialPoll`, fail with `"Bluesky doesn't support polls"` before any HTTP call.
* **Max length:** 300 graphemes **and** 3000 UTF-8 bytes (§6.2).
* **Follow:**
  1. `GET /xrpc/app.bsky.actor.getProfile?actor={handle}` → `profile` (`profileViewDetailed`). Not found → the §4 not-found message (same detection as §5 "Not found (by handle)").
  2. If `profile.did` equals the session DID → the §4 "own account" message.
  3. Read `profile.viewer` (`viewerState`, filled in because the request is authenticated):
     * `viewer.blocking` is present (an AT URI), `viewer.blockingByList` is present, or `viewer.blockedBy` is `true` → the §4 block message.
     * `viewer.following` is present (the AT URI of the existing follow record) → `already-following`, with no write.
  4. Otherwise `POST /xrpc/com.atproto.repo.createRecord`:
     ```json
     {
       "repo": "<session did>",
       "collection": "app.bsky.graph.follow",
       "record": {
         "$type": "app.bsky.graph.follow",
         "subject": "<profile.did>",
         "createdAt": "<ISO-8601 UTC timestamp>"
       }
     }
     ```
     The `subject` must be the DID, not the handle (the lexicon declares `format: did`). A successful response (`uri`, `cid`) → `following`. Bluesky has no follow requests or locked accounts, so `requested` and `already-requested` never occur.
  * The §5 session refresh-and-retry applies to both calls. A failed `createRecord` creates nothing, so the retry can't create two records.
  * **Duplicates:** each `createRecord` call makes a new record, and the lexicon says the AppView ignores duplicate follows. Step 3 is what prevents duplicates. `viewer.following` comes from the AppView and can lag a few seconds behind a follow that was just created, so two calls in quick succession may both write. That is harmless, because the AppView counts only one follow.
  * The optional `via` field (a strong reference to a starter pack) is never set.
* **Unfollow:**
  1. `GET /xrpc/app.bsky.actor.getProfile?actor={handle}` → `profile`, and reject the own account, exactly as for follow (steps 1–2), using the Tool 12 messages.
  2. `viewer.following` absent → `not-following`, with no write.
  3. Otherwise `viewer.following` is the follow record's AT URI, `at://<did>/app.bsky.graph.follow/<rkey>`. Parse it with `^at://([^/]+)/app\.bsky\.graph\.follow/([^/?#]+)$`. If it doesn't match, or `<did>` isn't the session DID → `IllegalStateException("Unexpected follow record '<uri>' on bluesky")`, with no write. (This is a defensive check: the viewer's follow always lives in the viewer's own repo.)
  4. `POST /xrpc/com.atproto.repo.deleteRecord`:
     ```json
     {
       "repo": "<session did>",
       "collection": "app.bsky.graph.follow",
       "rkey": "<rkey>"
     }
     ```
     Success → `unfollowed`. The lexicon defines `deleteRecord` as "delete a repository record, or ensure it doesn't exist", so the call is idempotent. `swapRecord` and `swapCommit` are not sent.
  * The §5 session refresh-and-retry applies. Because `deleteRecord` is idempotent, the retry is always safe.
  * **Duplicate follow records:** if another client created more than one follow record for the same account, `viewer.following` names only one of them, and the AppView may still count the follow after that one is deleted. The tool deletes only the reported record and does not scan the repository (`com.atproto.repo.listRecords` would have to page through every follow). Calling the tool again deletes the next reported record.
* **Block:**
  1. `GET /xrpc/app.bsky.actor.getProfile?actor={handle}` → `profile`, and reject the own account, as for follow (steps 1–2), using the Tool 12 messages.
  2. `viewer.blocking` present (the AT URI of an existing block record) → `already-blocked`, with no write. `viewer.blockingByList` and `viewer.blockedBy` don't prevent a direct block.
  3. Otherwise `POST /xrpc/com.atproto.repo.createRecord`:
     ```json
     {
       "repo": "<session did>",
       "collection": "app.bsky.graph.block",
       "record": {
         "$type": "app.bsky.graph.block",
         "subject": "<profile.did>",
         "createdAt": "<ISO-8601 UTC timestamp>"
       }
     }
     ```
     Success → `blocked`. `subject` must be the DID (lexicon: `format: did`, "DID of the account to be blocked").
  * The lexicon notes that **blocks are public** on Bluesky. The server doesn't delete follow records in either direction; the AppView hides the relationship while the block exists.
  * Session refresh-and-retry and duplicate handling work as for follow: a failed `createRecord` creates nothing, and `viewer.blocking` can lag a few seconds behind a new block.
* **Unblock:**
  1. `getProfile` and the own-account check, using the Tool 12 messages.
  2. `viewer.blocking` absent:
     * `viewer.blockingByList` present → the Tool 12 list-block message (list name = `viewer.blockingByList.name`), with no write.
     * Otherwise → `not-blocked`, with no write.
  3. `viewer.blocking` is `at://<did>/app.bsky.graph.block/<rkey>`. Parse it with `^at://([^/]+)/app\.bsky\.graph\.block/([^/?#]+)$`. If it doesn't match, or `<did>` isn't the session DID → `IllegalStateException("Unexpected block record '<uri>' on bluesky")`, with no write.
  4. `POST /xrpc/com.atproto.repo.deleteRecord` with `{"repo": "<session did>", "collection": "app.bsky.graph.block", "rkey": "<rkey>"}` → `unblocked`. If `viewer.blockingByList` was also present, set `note` to `"Still blocked through the moderation list '<list name>'."`.
  * Idempotent, like the unfollow delete, so the session retry is safe. The same duplicate-record caveat as for unfollow applies.
* **Like:**
  1. Read the post (above) → `postView`.
  2. `postView.viewer.like` present (the AT URI of the existing like record) → `already-liked`, with `post` mapped from `postView` and no write.
  3. Otherwise `POST /xrpc/com.atproto.repo.createRecord`:
     ```json
     {
       "repo": "<session did>",
       "collection": "app.bsky.feed.like",
       "record": {
         "$type": "app.bsky.feed.like",
         "subject": { "uri": "<postView.uri>", "cid": "<postView.cid>" },
         "createdAt": "<ISO-8601 UTC timestamp>"
       }
     }
     ```
     `subject` is a `com.atproto.repo.strongRef`: it needs both the `uri` and the `cid` of the exact post version, which is why the post is read even when the caller passed an AT URI. Success → `liked`.
  * **Counts:** the AppView updates `likeCount` asynchronously, so the service doesn't read the post again. `post` is mapped from the `postView` of step 1, with `likeCount` increased by 1.
  * The optional `via` field (a strong reference to a repost the like came through) is never set.
  * Session refresh-and-retry and duplicate handling work as for follow: a failed `createRecord` creates nothing, and `viewer.like` can lag a few seconds behind a new like.
* **Unlike:**
  1. Read the post (above) → `postView`.
  2. `viewer.like` absent → `not-liked`, with no write.
  3. `viewer.like` is `at://<did>/app.bsky.feed.like/<rkey>`. Parse it with `^at://([^/]+)/app\.bsky\.feed\.like/([^/?#]+)$`. If it doesn't match, or `<did>` isn't the session DID → `IllegalStateException("Unexpected like record '<uri>' on bluesky")`, with no write.
  4. `POST /xrpc/com.atproto.repo.deleteRecord` with `{"repo": "<session did>", "collection": "app.bsky.feed.like", "rkey": "<rkey>"}` → `unliked`. `post` is the step 1 `postView` with `likeCount` decreased by 1 (never below 0).
  * Idempotent, so the session retry is safe. The same duplicate-record caveat as for unfollow applies.
* **Reply:**
  * `replyTarget`: read the post. `parent` = `{uri: postView.uri, cid: postView.cid}`. `root` = `postView.record.reply.root` (a strong ref) if the parent is itself a reply, otherwise `parent`. `viewer.replyDisabled: true` → the Tool 14 "restricted who can reply" message. `mention` is always `null`, because Bluesky notifies the parent's author without a mention.
  * `reply`: `createRecord` in `app.bsky.feed.post` exactly as for a thread part (§5 Post), with `reply: {root, parent}` from the target. The `url` is built the same way.
  * Both fields are required by the lexicon's `replyRef`. Using the parent as the root for a reply deep in a thread would detach the reply from the thread in clients, so the root must come from the parent's own `reply` field.
  * A threadgate can also change after `getPosts`. If `createRecord` is rejected for that reason, the error surfaces through §6.5.
* **Repost:**
  1. Read the post. `viewer.repost` present → `already-reposted`, with no write.
  2. `createRecord` with `collection` = `app.bsky.feed.repost` and `record` = `{"$type": "app.bsky.feed.repost", "subject": {"uri": ..., "cid": ...}, "createdAt": ...}` (lexicon `app.bsky.feed.repost`; `via` is never set) → `reposted`. `post.repostCount` = the `postView` count + 1, because the AppView updates counts asynchronously.
  * Bluesky has no visibility restriction on reposts. A post hidden by a block is already not found in step 1.
* **Unrepost:** read the post. `viewer.repost` absent → `not-reposted`. Otherwise parse `at://<did>/app.bsky.feed.repost/<rkey>` (the repo must be the session DID, or → `IllegalStateException("Unexpected repost record '<uri>' on bluesky")`), then `deleteRecord` with `collection` = `app.bsky.feed.repost` → `unreposted`, with `repostCount` − 1 (never below 0).
* **Bookmark / unbookmark:** bookmarks are **not** repository records. They are private and stored by the AppView, and are managed with two procedures.
  * Read the post. `viewer.bookmarked: true` → `already-bookmarked` (bookmark), and anything else → `not-bookmarked` (unbookmark), with no write in either case.
  * Bookmark: `POST /xrpc/app.bsky.bookmark.createBookmark` with body `{"uri": "<postView.uri>", "cid": "<postView.cid>"}` → `bookmarked`.
  * Unbookmark: `POST /xrpc/app.bsky.bookmark.deleteBookmark` with body `{"uri": "<postView.uri>"}` → `unbookmarked`.
  * Error `UnsupportedCollection` can't happen here, because only `app.bsky.feed.post` URIs reach these calls. If it does occur, it surfaces through §6.5. The session refresh-and-retry applies.
* **Mute:** mutes are private and stored by the AppView, not as repository records.
  1. `getProfile` and the own-account check, as for block (Tool 12 messages).
  2. `viewer.muted` is `true`, `viewer.mutedByList` is absent, and neither `viewer.mutedOnlyReposts` nor `viewer.mutedOnlyQuoteposts` is `true` → a full direct mute already exists → `already-muted`, with no write.
  3. Otherwise `POST /xrpc/app.bsky.graph.muteActor` with body `{"actor": "<profile.did>"}` and no `only*` flags → `muted`. The lexicon says a repeat call replaces the stored scope, so this also widens a reposts-only or quote-posts-only mute to a full mute. When `viewer.mutedByList` is present, the list can't tell us whether a direct mute also exists, so the call is always made.
* **Unmute:** `getProfile` and the own-account check (Tool 12 messages). `viewer.muted` is `false` → `not-muted`. Otherwise `POST /xrpc/app.bsky.graph.unmuteActor` with body `{"actor": "<profile.did>"}` → `unmuted`, with `note` = `"Any direct mute was removed, but '<handle>' is still muted through the mute list '<list name>'. Remove them from the list or unsubscribe from it in the Bluesky app."` when `viewer.mutedByList` is present. (Bluesky's `viewerState` can't tell a direct mute from a list mute: `viewer.muted` is `true` for either, so the call is made whenever `viewer.muted` is `true`.)

---

## 6. Business Rules & Error Handling

1. **Configuration detection:**
   * Every credential in §7 has an empty default, so the app always starts even when environment variables are missing. (`@ConditionalOnProperty` is **not** used: it treats an empty value as present.)
   * A platform is *configured* when all of its credentials are non-blank. All sixteen tools need the same credentials (on Mastodon, several tools also need extra scopes on the token, see §5 **Auth**):

     | Platform | Required |
     |---|---|
     | Mastodon | `access-token` |
     | Bluesky | `handle`, `app-password` |

   * Service beans are always registered. Each service checks `isConfigured()` at call time.
   * Unknown platform string → `IllegalArgumentException("Unknown platform '<value>'. Use one of: mastodon, bluesky")`.
   * Any tool on an unconfigured platform → `IllegalArgumentException("Platform <name> is not configured")`.
2. **Length measurement (`checkPart`)** — shared by `createSocialPost`, `checkSocialPost`, `createSocialThread` and `replyToSocialPost`, so all four always agree. It is applied to the final text, including any numbering suffix or reply mention prefix. An embedded quote and a poll are not part of the text and don't count. Rejections happen before any posting call. The only network call that may happen first is the Mastodon instance-limits fetch (§5).
   * **Mastodon (`maxLength` from the instance, fallback 500):** This mirrors Mastodon's own `StatusLengthValidator`, which counts grapheme clusters.
     1. Rewrite the text: replace each URL that has an `http://` or `https://` scheme (`https?://\S+`) with `urlLength` placeholder characters (usually 23). Replace each remote mention `@user@domain` with `@user`. URLs without a scheme are not shortened.
     2. Count extended grapheme clusters in the rewritten text with `java.text.BreakIterator.getCharacterInstance()`, the same method as Bluesky.
   * **Bluesky (300 graphemes and 3000 bytes):** Count extended grapheme clusters with `java.text.BreakIterator.getCharacterInstance()`, **and** count the UTF-8 byte length. A part passes only if it is within both limits. The `reason` names whichever limit failed, e.g. `"327/300 graphemes (27 over)"` or `"3104/3000 bytes (104 over)"`.
   * Never use `String.length()` directly. It counts UTF-16 code units, which over-counts emoji.
   * A blank text (empty or only whitespace) always fails with `reason: "blank"`.
3. **Write kill switch:** `social.posting-enabled=false` disables every tool that changes anything on a platform, so an operator can run the server read-only:
   * `createSocialPost` (with or without a quote or poll), `createSocialThread` and `replyToSocialPost` throw `IllegalStateException("Posting is disabled")`.
   * `setAccountRelationship` and `setPostAction` throw `IllegalStateException("<Action> is disabled")`, naming the requested action: `Following`, `Unfollowing`, `Blocking`, `Unblocking`, `Muting`, `Unmuting`, `Liking`, `Unliking`, `Reposting`, `Unreposting`, `Bookmarking` or `Unbookmarking`.
   * `voteInSocialPoll` throws `IllegalStateException("Voting is disabled")`.
   * Bookmarks and mutes are private, but they still change the account's state, so they're covered too. Reading bookmarks (`getSocialBookmarks`) is not a write and keeps working.
   * The check is made before any HTTP call. It is the first check, except that the two merged tools validate `action` first (§6.11), so that the message can name the action. The eight read tools (including `getSocialBookmarks`), `getSocialPostingRules` and `checkSocialPost` still work.
   * The property keeps its name for compatibility with existing deployments, even though it now covers every write tool.
4. **Transport / stdout hygiene:** STDIO transport. Nothing other than JSON-RPC may be written to stdout. Required properties are in §7. Logs go to a file.
5. **Error handling:** Tool methods throw exceptions without catching them. Spring AI converts an exception thrown by a tool into a `CallToolResult` with `isError: true` and the exception message as text. (Spring AI 2.0.1 appends the root cause's message on a second line, so an exception with no cause shows its message twice. This is cosmetic and comes from the framework.) That is *not* a JSON-RPC protocol error, and it lets the calling model see and react to the failure.
   * Upstream HTTP errors (`RestClientResponseException`) that aren't mapped to a specific message elsewhere in this spec → `IllegalStateException("<platform> API error <status>: <short body excerpt>")`.
   * Network failures (`ResourceAccessException`) → `IllegalStateException("<platform> is unreachable: <cause message>")`.
   * Credentials are never included in any message.
   * Apart from the Mastodon post retry and the Bluesky session refresh-and-retry (§5), requests are not retried automatically. This includes every relationship change, post action and vote. Each of those checks the current state first, so the agent can safely call the tool again.
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
9. **Post reference resolution** (the `post` parameter of `getSocialPostInteractions`, `setPostAction`, `replyToSocialPost` and `voteInSocialPoll`, and the `quote` parameter of `createSocialPost`). Trim the value, then:
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
11. **`action` handling** (in `SocialMcpTools`, for `setAccountRelationship` and `setPostAction`):
    * Trim the value and match it case-insensitively against the tool's six actions, mapping it to the `AccountAction` / `PostAction` enum. A null, blank or unknown value → the tool's "Unknown action" message (§4), before any other check and with no HTTP call.
    * The parameter is a `String`, not the enum, so the generated schema stays lowercase-friendly and the server's error message can list the valid values. The `@McpToolParam` description lists all six values. The server doesn't add a JSON-schema `enum` for `action`, so a wrong value reaches the server and gets the helpful message.
    * The echoed `action` in each result is the lowercase canonical name.
12. **Poll validation** (in `SocialMcpTools`, for `createSocialPost` with `poll`, using `postingRules().polls`, before any posting call):
    * `polls` is `null` (Bluesky) → `IllegalArgumentException("Bluesky doesn't support polls")`.
    * `options` must be present (a missing or `null` `options` → the "needs 2 to …" message below).
    * Each option is trimmed. `options` must hold between 2 and `maxOptions` items. Otherwise → `IllegalArgumentException("A poll needs 2 to <maxOptions> options on mastodon")`.
    * Each option must be non-blank and at most `maxOptionLength` graphemes, counted with the same `BreakIterator` method as post text (Mastodon counts `each_grapheme_cluster`). Otherwise → `IllegalArgumentException("Poll option <i> is <reason>")`, with the same reason format as §6.2, or `blank`.
    * Options must be unique after trimming (Mastodon compares exactly, so case matters). Otherwise → `IllegalArgumentException("Poll options must be different")`.
    * `expiresInMinutes` (default 1440) must be between `minExpiresInMinutes` and `maxExpiresInMinutes`. Otherwise → `IllegalArgumentException("A poll must last between <min> minutes and <max> minutes on mastodon")`.
    * `multiple` and `hideTotals` default to `false`.
    * Every problem is detected before posting. The first one found is reported, in the order above.
13. **`choices` handling** (in `SocialMcpTools`, for `voteInSocialPoll`): must be non-null and non-empty, have no duplicates, and contain only numbers ≥ 1. Otherwise → the Tool 16 "distinct option numbers" message, with no HTTP call. The upper bound and the single-choice rule need the poll, so the service checks those after reading it.

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
# false = read-only: disables every tool that changes anything on a platform (§6.3)
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
  * `createSocialPost`, `checkSocialPost`, `createSocialThread` and `replyToSocialPost` give the same length for the same final text (they share `checkPart`).
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
* **setAccountRelationship, action `follow`:**
  * Routing: `@alice.bsky.social` and `alice.bsky.social` send the same requests. An invalid handle, or `posting-enabled=false` (the "Following is disabled" message), fails with no HTTP call.
  * Mastodon:
    * Not followed yet: `accounts/lookup`, then `accounts/relationships?id[]=<id>`, then `POST /api/v1/accounts/<id>/follow` with an empty body. A response with `following: true` → `status: "following"`. `account` is mapped as `AccountSummary`, with a fully qualified handle.
    * A response with `following: false, requested: true` (a locked or remote account) → `requested`.
    * A relationship with `following: true` → `already-following`, and a relationship with `requested: true` → `already-requested`. In both cases the follow endpoint is **not** called (`MockRestServiceServer` verifies it).
    * `blocking: true` or `blocked_by: true` → the block message, with no follow call.
    * The own account (the lookup returns the id from `verify_credentials`) → the "own account" message, with no relationships or follow call.
    * HTTP 403 `{"error":"This action is outside the authorized scopes"}` on the relationships call → the missing-scopes message. Any other 403 on the follow call → the "doesn't allow following" message. HTTP 422 → the generic `API error 422` message including the body excerpt.
    * A remote handle not known to the instance is found through the `search?resolve=true` fallback, then followed.
  * Bluesky:
    * Not followed yet: `getProfile?actor=<handle>`, then one `createRecord` whose body has `repo` = session DID, `collection` = `app.bsky.graph.follow`, `record.$type` = `app.bsky.graph.follow`, `record.subject` = the profile's **DID** (not the handle), and an ISO-8601 `createdAt` from the injected clock → `following`.
    * `viewer.following` present → `already-following`, with no `createRecord`.
    * `viewer.blocking`, `viewer.blockingByList` or `viewer.blockedBy: true` → the block message, with no `createRecord`.
    * The profile DID equals the session DID → the "own account" message, with no `createRecord`.
    * `Profile not found` → the not-found message.
    * An `ExpiredToken` on `createRecord` → `refreshSession` and exactly one retried `createRecord`.
* **setAccountRelationship, action `unfollow`:**
  * An invalid handle, or `posting-enabled=false` (the "Unfollowing is disabled" message), fails with no HTTP call.
  * Mastodon:
    * Relationship `following: true` → `POST /api/v1/accounts/<id>/unfollow` with an empty body, and a response with `following: false, requested: false` → `unfollowed`.
    * Relationship `following: false, requested: true` → the same unfollow call → `request-cancelled`.
    * Relationship with neither → `not-following`, and the unfollow endpoint is **not** called.
    * A response that still has `following: true` → the "did not confirm the unfollow" message.
    * The own account → the "can't unfollow your own account" message, with no relationships or unfollow call.
    * The missing-scopes 403 → the same message as for follow.
  * Bluesky:
    * `viewer.following` = `at://<session did>/app.bsky.graph.follow/3kabc` → one `deleteRecord` with `repo` = session DID, `collection` = `app.bsky.graph.follow`, `rkey` = `3kabc` → `unfollowed`.
    * `viewer.following` absent → `not-following`, with no `deleteRecord`.
    * `viewer.following` in another DID's repo, or not a follow-record URI → the "unexpected follow record" message, with no `deleteRecord`.
    * The profile DID equals the session DID → the "can't unfollow your own account" message.
    * An `ExpiredToken` on `deleteRecord` → `refreshSession` and exactly one retried `deleteRecord`.
* **Follow `note`:** a Mastodon `requested` result for a locked local account carries the approval note; for an unlocked remote account `bob@example.social` it carries the note naming `example.social`. `following` results have `note: null`.
* **setAccountRelationship, action `block`:**
  * An invalid handle, or `posting-enabled=false` (the "Blocking is disabled" message), fails with no HTTP call.
  * Mastodon:
    * Relationship `blocking: false` → `POST /api/v1/accounts/<id>/block` with an empty body, and a response with `blocking: true` → `blocked`.
    * Relationship `blocking: true` → `already-blocked`, with no block call.
    * Relationship `blocked_by: true, blocking: false` → the block call is still made → `blocked`.
    * The own account → the "can't block your own account" message, with no relationships or block call.
    * A 403 `outside the authorized scopes` → the missing-scopes message naming `read:follows, write:blocks`.
  * Bluesky:
    * No `viewer.blocking` → one `createRecord` with `collection` = `app.bsky.graph.block`, `record.$type` = `app.bsky.graph.block`, `record.subject` = the profile DID, and `createdAt` from the injected clock → `blocked`.
    * `viewer.blocking` present → `already-blocked`, with no `createRecord`.
    * `viewer.blockedBy: true` only, or `viewer.blockingByList` only → the `createRecord` is still made → `blocked`.
    * The own DID → the "can't block your own account" message.
* **setAccountRelationship, action `unblock`:**
  * An invalid handle, or `posting-enabled=false` (the "Unblocking is disabled" message), fails with no HTTP call.
  * Mastodon:
    * Relationship `blocking: true` → `POST /api/v1/accounts/<id>/unblock`, and a response with `blocking: false` → `unblocked`, `note: null`.
    * Relationship `blocking: false` → `not-blocked`, with no unblock call.
    * Relationship `blocking: true, domain_blocking: true` → `unblocked` with the domain-block `note`.
  * Bluesky:
    * `viewer.blocking` = `at://<session did>/app.bsky.graph.block/3kxyz` → one `deleteRecord` with `collection` = `app.bsky.graph.block` and `rkey` = `3kxyz` → `unblocked`.
    * No `viewer.blocking` and no `viewer.blockingByList` → `not-blocked`, with no `deleteRecord`.
    * `viewer.blockingByList` only (list named `Spam`) → the list-block message naming `Spam`, with no `deleteRecord`.
    * Both a direct block and a list block → `deleteRecord` → `unblocked` with the "still blocked through the moderation list" `note`.
    * A `viewer.blocking` URI in another repo → the "unexpected block record" message, with no `deleteRecord`.
* **setPostAction, action `like`:**
  * An invalid post reference (e.g. `hello`), or `posting-enabled=false` (the "Liking is disabled" message), fails with no HTTP call.
  * Mastodon:
    * `favourited: false` → `POST /api/v1/statuses/<id>/favourite` with an empty body, and a response with `favourited: true, favourites_count: 8` → `liked`, with `post.likeCount` = 8.
    * `favourited: true` → `already-liked`, with no favourite call.
    * A boost id whose status has a `reblog` → the favourite call uses the **reblog's** id, and `post` is the original post.
    * A URL reference goes through `search?resolve=true` first (§6.9), then the same flow.
    * A 404 on `GET /statuses/<id>` → the not-found message, with no favourite call.
    * A 403 `outside the authorized scopes` → the missing-scopes message naming `write:favourites`.
  * Bluesky:
    * A `bsky.app` URL is resolved through `resolveHandle`, then `getPosts?uris=<at-uri>`. With no `viewer.like`, one `createRecord` follows with `collection` = `app.bsky.feed.like`, `record.subject` = `{uri, cid}` from the `postView`, and `createdAt` from the injected clock → `liked`, with `post.likeCount` = the `postView` count + 1.
    * `viewer.like` present → `already-liked`, with no `createRecord`.
    * An empty `posts` array → the not-found message.
    * An `ExpiredToken` on `createRecord` → `refreshSession` and exactly one retried `createRecord`.
* **setPostAction, action `unlike`:**
  * `posting-enabled=false` → the "Unliking is disabled" message, with no HTTP call.
  * Mastodon: `favourited: true` → `POST /api/v1/statuses/<id>/unfavourite` → `unliked`. `favourited: false` → `not-liked`, with no unfavourite call. A response still showing `favourited: true` → the "did not confirm the unlike" message.
  * Bluesky: `viewer.like` = `at://<session did>/app.bsky.feed.like/3klik` → one `deleteRecord` with `collection` = `app.bsky.feed.like` and `rkey` = `3klik` → `unliked`, with `likeCount` decreased by 1 (a count of 0 stays 0). `viewer.like` absent → `not-liked`, with no `deleteRecord`. A like URI in another repo → the "unexpected like record" message.
* **replyToSocialPost:**
  * `posting-enabled=false` → "Posting is disabled", and blank `content` → the blank message, both with no HTTP call.
  * Mastodon:
    * A parent by `@alice@example.social` with `visibility: unlisted` → one `POST /api/v1/statuses` with `status` = `"@alice@example.social <content>"`, `in_reply_to_id` = the parent id, `visibility` = `unlisted`, and an `Idempotency-Key`. The `ReplyResult.text` equals the posted status.
    * A local author (`acct: "bob"`) gets the prefix `@bob `. Content that already mentions `@Alice@example.social` (any case) gets no prefix. A parent written by the configured account gets no prefix (`ReplyTarget.mention` is `null`).
    * An invalid `post` reference fails with no HTTP call.
    * Visibility mirrors the parent for `public`, `private` and `direct`.
    * A boost id → `in_reply_to_id` is the **reblog's** id, and the prefix names the original author.
    * Length: content that fits only without the prefix is rejected with the "Shorten it" message that mentions the automatic mention, and no `POST /statuses` is made. The prefix `@alice@a-very-long-domain.example` counts as `@alice` plus a space.
    * An HTTP 500 followed by success is retried once with the same `Idempotency-Key`.
  * Bluesky:
    * A top-level parent → `createRecord` with `reply.root` = `reply.parent` = the parent's `{uri, cid}` and no text prefix.
    * A parent that is itself a reply (its `record.reply.root` points to post R) → `reply.root` = R's strong ref and `reply.parent` = the parent.
    * `viewer.replyDisabled: true` → the "restricted who can reply" message, with no `createRecord`.
    * 301 graphemes → rejected, with no `createRecord`.
* **setPostAction, actions `repost` / `unrepost`:**
  * Mastodon:
    * `reblogged: false` on a public post → `POST /statuses/<id>/reblog` with an empty body. `post` is mapped from the response's `reblog` field → `reposted`. `reblogged: true` → `already-reposted`, with no call.
    * Someone else's `private` post, and any `direct` post (including the configured account's own), are rejected before any write. The configured account's own `private` post is reposted.
    * A boost id → the reblog call uses the original's id.
    * Unrepost: `reblogged: true` → `POST /statuses/<id>/unreblog` → `unreposted`. `reblogged: false` → `not-reposted`, with no call.
  * Bluesky:
    * No `viewer.repost` → one `createRecord` in `app.bsky.feed.repost` with `subject` = `{uri, cid}` → `reposted`, with `repostCount` + 1. `viewer.repost` present → `already-reposted`.
    * Unrepost deletes the rkey from `viewer.repost` (session repo only) → `unreposted`. Absent → `not-reposted`.
* **setPostAction, actions `bookmark` / `unbookmark`:**
  * Mastodon: `bookmarked: false` → `POST /statuses/<id>/bookmark` → `bookmarked`; `bookmarked: true` → `already-bookmarked`, with no call. Unbookmark mirrors this with `/unbookmark`. A 403 missing-scopes response names `write:bookmarks`.
  * Bluesky: `viewer.bookmarked` false/absent → `POST app.bsky.bookmark.createBookmark` with `{uri, cid}` → `bookmarked`. `true` → `already-bookmarked`. Unbookmark → `deleteBookmark` with `{uri}` only. No `createRecord` or `deleteRecord` is ever made for bookmarks.
* **setAccountRelationship, actions `mute` / `unmute`:**
  * Mastodon:
    * Relationship `muting: false` → `POST /accounts/<id>/mute` with an empty body → `muted`.
    * `muting: true, muting_notifications: true, muting_expires_at: null` → `already-muted`, with no call.
    * `muting: true` with `muting_notifications: false`, or with a non-null `muting_expires_at` → the mute call is made (widening) → `muted`.
    * Unmute: `muting: true` → `/unmute` → `unmuted`; `muting: false` → `not-muted`.
    * A missing-scopes 403 names `read:follows, write:mutes`.
  * Bluesky:
    * `viewer.muted` false → `muteActor` with `{actor: <did>}` and no `only*` flags → `muted`.
    * `muted: true` with no list and no `only*` flags → `already-muted`.
    * `mutedOnlyReposts: true` → `muteActor` (widening) → `muted`.
    * `mutedByList` present → `muteActor` is still called.
    * Unmute: `muted: true` → `unmuteActor` → `unmuted`, with the list `note` when `mutedByList` is present. `muted: false` → `not-muted`.
  * The own account → the matching "can't mute/unmute your own account" message on both platforms, with no write.
* **`action` dispatch (both merged tools):**
  * `" Follow "`, `"FOLLOW"` and `"follow"` all route to the follow flow, and the result's `action` is `"follow"`. The same holds for every value of both tools.
  * An unknown value (`"like"` on `setAccountRelationship`, `"follow"` on `setPostAction`, `"dislike"`, `""`, `null`) → the tool's "Unknown action" message listing the six valid values. No HTTP call is made, and the kill-switch check is not reached, so the message is the same with `posting-enabled=false`.
  * With `posting-enabled=false`, each valid action gets its own message (e.g. `mute` → "Muting is disabled", `unbookmark` → "Unbookmarking is disabled").
  * `SocialMcpToolsTest` with the fake platform checks that each action reaches `setRelationship` / `setPostAction` with the right enum value and the normalized handle or post reference.
* **getSocialBookmarks:**
  * Mastodon: `GET /api/v1/bookmarks?limit=<n>` with `n` capped at 40. Statuses map to `PostResult`, a boost maps to its original post, and the response order is kept. It works with `posting-enabled=false`.
  * Bluesky: `GET app.bsky.bookmark.getBookmarks?limit=<n>` with `n` capped at 100. Of 3 items (`postView`, `notFoundPost`, `blockedPost`), only the `postView` is returned, and no second request is made.
  * An empty bookmark list → `[]`.
  * A 403 missing-scopes response on Mastodon → the missing-scopes message naming `read:bookmarks`.
* **Quote and poll mapping (reads):**
  * Mastodon: a status with `quote: {state: "accepted", quoted_status: {...}}` → `quote.state` `accepted` with the quoted post's fields, and no nested `quote`/`poll`. `pending` → `pending`. `blocked_domain` → `blocked`, and `muted_account` → `muted`. A `ShallowQuote` fills only `id`. A status without `quote` → `null`.
  * Mastodon: a quote post whose `content` is `<p class="quote-inline">RE: <a href="…">…</a></p><p>My take</p>` maps to `text` = `"My take"`. The same `quote-inline` paragraph in a status **without** `quote` is kept as text.
  * Mastodon: a single-choice poll maps `voters_count: null` to `votersCount: null`, while `votesCount` is still reported.
  * Mastodon: a poll with `own_votes: [1]` → `ownVotes: [2]`. Options are numbered from 1. Hidden totals (`options[].votes_count: null`) → `null` option counts. A status without a poll → `poll: null`.
  * Bluesky: a `postView` with `embed.$type` `app.bsky.embed.record#view` and a `viewRecord` → an `accepted` quote. `recordWithMedia#view` → its inner record. `viewNotFound`, `viewBlocked` and `viewDetached` → `deleted`, `blocked` and `detached`. An embedded feed generator → `quote: null`. `poll` is always `null`.
* **createSocialPost with `quote`:**
  * Mastodon (instance `api_versions.mastodon: 7`): `quote_approval.current_user: "automatic"` on a public post → `POST /api/v1/statuses` with `quoted_status_id` and `visibility: public`, and a confirmation without a caveat.
  * `manual` → the same request, and a confirmation with the "waiting for @author to approve" caveat. `denied` or `unknown`, a missing `quote_approval`, or a `direct` post → the "can't quote" message, with no `POST /statuses`.
  * A `private` quoted post → `visibility: private`, and the followers-only caveat.
  * A response with `quote.state: "pending"` (with `automatic`) → the pending caveat.
  * A boost id → `quoted_status_id` is the reblog's id.
  * An instance with `api_versions.mastodon: 6`, a response without `api_versions`, or an instance fetch that fell back → `quotes: false` in the posting rules, and the "needs Mastodon 4.5" message with no `GET /statuses` call.
  * Bluesky: `getPosts`, then `createRecord` whose record has `embed` = `{$type: app.bsky.embed.record, record: {uri, cid}}`. `viewer.embeddingDisabled: true` → the "disabled quoting" message, with no `createRecord`.
  * Both `quote` and `poll` given → the "quote or a poll, not both" message, with no HTTP call.
  * The quote doesn't affect length: text of exactly the limit plus a quote passes.
* **createSocialPost with `poll`:**
  * Mastodon: `{options: ["Java", "Kotlin"]}` → a body with `poll.options` = both, `expires_in` = 86400, `multiple` = `false` and `hide_totals` = `false`.
  * `expiresInMinutes: 5` → `expires_in` 300. `4` → the "must last between" message, with no posting call.
  * 1 option, or 5 options (with the instance reporting `max_options: 4`), are rejected. So are a 51-grapheme option, a blank option, and duplicates (`"Java"` twice). `"Java"` and `"java"` are both accepted.
  * An instance reporting `max_options: 6` accepts 6 options.
  * The instance fetch falls back → the defaults 4, 50, 5 and 43829 are used.
  * Bluesky with `poll` → "Bluesky doesn't support polls", with no HTTP call.
* **voteInSocialPoll:**
  * Mastodon, a single-choice poll with 3 options, `voted: false`, and another author: `choices: [2]` → `POST /api/v1/polls/<poll id>/votes` with `{"choices": [1]}` → `voted`, and `post.poll` comes from the response.
  * A multiple-choice poll with `choices: [1, 3]` → `{"choices": [0, 2]}`.
  * `voted: true` → `already-voted`, with no vote call.
  * `expired: true` → "The poll has ended". The own poll → "can't vote in your own poll". `choices: [4]` on 3 options, or `[1, 2]` on a single-choice poll → the "This poll has…" message. None of these make a vote call.
  * `choices: []`, `null`, `[0]` or `[2, 2]` → the "distinct option numbers" message, with no HTTP call.
  * An expired poll that the account already voted in → `already-voted`, not "The poll has ended".
  * A post without a poll → "has no poll".
  * A 422 "already voted" on the vote call → a re-read, then `already-voted`.
  * Bluesky → "Bluesky doesn't support polls", with no HTTP call. `posting-enabled=false` → "Voting is disabled", with no HTTP call.
* **Posting rules `quotes` / `polls`:** Mastodon reports `polls` from the instance and `quotes` from `api_versions`. Bluesky reports `quotes: true, polls: null`.
* **Platform-aware tool definitions:**
  * With only Mastodon configured, every tool with a `platform` parameter has `enum: ["mastodon"]`, a parameter description naming only `"mastodon"`, and `Mastodon` in place of `{platforms}`. Only-Bluesky mirrors this.
  * With both configured, the enum is `["mastodon", "bluesky"]` and the description says `Mastodon or Bluesky`.
  * With none configured, there is no `enum`, and both the tool and parameter descriptions say none is configured.
  * No tool description or schema contains a literal `{platforms}` after startup. Names, the other parameters, `required` and call handlers are unchanged. Tools without a `platform` parameter are left untouched.
  * End to end: the jar started with only `MASTODON_ACCESS_TOKEN` set lists all sixteen tools in `tools/list` with `enum: ["mastodon"]` and no leftover `{platforms}`. A call with `platform: "bluesky"` or `platform: "Mastodon"` returns the SDK's `isError` enum-validation result naming `["mastodon"]`.
  * Direct (non-MCP) calls to `SocialMcpTools` still reject an unconfigured platform with the §6.1 "not configured" message.
* **Configuration:**
  * With no credentials set, the context starts, and every tool on each platform throws the §6.1 "not configured" message.
  * With `social.posting-enabled=false`, every write tool (§6.3) throws, while the eight read tools, `getSocialPostingRules` and `checkSocialPost` still work.
  * Each of these fails startup with a clear message: `social.mastodon.thread-visibility=private`, `social.thread.max-parts=1`, `social.read.max-limit=0`, and `social.read.default-limit=50` with `social.read.max-limit=40`.
  * An unreachable host produces the §6.5 "is unreachable" message rather than a raw stack trace.
* **Boot check:** `java -jar target/social-mcp-server-0.0.1-SNAPSHOT.jar` waits on stdin. Sending `initialize`, then the `notifications/initialized` notification, then `tools/list` yields valid JSON-RPC responses listing all sixteen tools, each with a description that names the supported platforms. Every line on stdout parses as JSON (no banner or log output).
