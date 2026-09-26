package com.socialmcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import org.jspecify.annotations.Nullable;

import com.socialmcp.media.ImageFormats;
import com.socialmcp.model.AccountAction;
import com.socialmcp.model.AccountSummary;
import com.socialmcp.model.ImageRules;
import com.socialmcp.model.NewPost;
import com.socialmcp.model.PartCheck;
import com.socialmcp.model.PollInput;
import com.socialmcp.model.PollRules;
import com.socialmcp.model.PostAction;
import com.socialmcp.model.PostActionResult;
import com.socialmcp.model.PostInteractions;
import com.socialmcp.model.PostResult;
import com.socialmcp.model.PostingRules;
import com.socialmcp.model.PreparedImage;
import com.socialmcp.model.ProfileResult;
import com.socialmcp.model.PublishedPost;
import com.socialmcp.model.QuoteTarget;
import com.socialmcp.model.RelationshipResult;
import com.socialmcp.model.ReplyTarget;
import com.socialmcp.model.SearchSort;
import com.socialmcp.model.SimilarAccountsResult;
import com.socialmcp.model.TimelineType;
import com.socialmcp.model.TrendsResult;
import com.socialmcp.model.VoteResult;
import com.socialmcp.platform.SocialPlatformService;
import com.socialmcp.text.TextLength;

/** In-memory platform that records calls; max length 20 graphemes. */
class FakePlatform implements SocialPlatformService {

	final String id;

	boolean configured = true;

	final List<String> calls = new ArrayList<>();

	/** Each published post as {text, rootId, parentId}. */
	final List<String[]> posted = new ArrayList<>();

	IntPredicate failPostNumber = n -> false;

	boolean polls = true;

	/** Image limits reported by {@link #postingRules}; tests may replace them. */
	ImageRules imageRules = new ImageRules(4, 1000, 10_000L, 50, ImageFormats.SNIFFABLE, true, false, null, "fixed");

	/** Whether images get metadata stripped (like Bluesky). */
	boolean stripImages;

	/** The images passed to the last post or reply. */
	List<PreparedImage> attached = List.of();

	/** What {@link #replyTarget} reports as the author to mention; null means no mention is needed. */
	@Nullable String replyMention = "alice@example.social";

	@Nullable String quoteCaveat;

	FakePlatform(String id) {
		this.id = id;
	}

	@Override
	public String platform() {
		return id;
	}

	@Override
	public boolean isConfigured() {
		return configured;
	}

	@Override
	public boolean isValidHandle(String handle) {
		return handle.matches("[a-z.]+");
	}

	@Override
	public List<PostResult> searchPosts(String query, SearchSort sort, int limit) {
		calls.add("search:" + query + ":" + sort + ":" + limit);
		return List.of();
	}

	@Override
	public List<PostResult> getTimeline(TimelineType type, int limit) {
		calls.add("timeline:" + type + ":" + limit);
		return List.of();
	}

	@Override
	public List<PostResult> getUserPosts(String handle, int limit) {
		calls.add("userPosts:" + handle + ":" + limit);
		return List.of();
	}

	@Override
	public ProfileResult getProfile(@Nullable String handle) {
		calls.add("profile:" + handle);
		return null;
	}

	@Override
	public PostInteractions getPostInteractions(String postRef, int limit) {
		calls.add("interactions:" + postRef + ":" + limit);
		return null;
	}

	@Override
	public TrendsResult getTrends(int limit) {
		calls.add("trends:" + limit);
		return null;
	}

	@Override
	public SimilarAccountsResult findSimilarAccounts(String handle, int limit) {
		calls.add("similar:" + handle + ":" + limit);
		return null;
	}

	@Override
	public PublishedPost createPost(String content, @Nullable PublishedPost root, @Nullable PublishedPost parent) {
		int number = posted.size() + 1;
		if (failPostNumber.test(number)) {
			throw new IllegalStateException(id + " API error 500: boom");
		}
		posted.add(new String[] { content, root == null ? null : root.id(), parent == null ? null : parent.id() });
		return new PublishedPost("id" + number, "cid" + number, "https://example/" + number);
	}

	@Override
	public PostingRules postingRules() {
		return new PostingRules(id, 20, "graphemes", null, null, 10, " (n/N)", 8, null, "", "fixed", true,
				polls ? new PollRules(4, 50, 5, 43829) : null, imageRules);
	}

	@Override
	public boolean supportsPolls() {
		return polls;
	}

	@Override
	public boolean stripsImageMetadata() {
		return stripImages;
	}

	@Override
	public RelationshipResult setRelationship(String handle, AccountAction action) {
		calls.add("relationship:" + handle + ":" + action);
		return new RelationshipResult(id, action.id(), "done", new AccountSummary(id, "1", "@" + handle, handle, "", "u"),
				null);
	}

	@Override
	public PostActionResult setPostAction(String postRef, PostAction action) {
		calls.add("postAction:" + postRef + ":" + action);
		return new PostActionResult(id, action.id(), "done", post(postRef));
	}

	@Override
	public List<PostResult> getBookmarks(int limit) {
		calls.add("bookmarks:" + limit);
		return List.of();
	}

	@Override
	public ReplyTarget replyTarget(String postRef) {
		calls.add("replyTarget:" + postRef);
		PublishedPost parent = new PublishedPost(postRef, null, "https://example/" + postRef);
		return new ReplyTarget(post(postRef), parent, parent, "public", replyMention);
	}

	@Override
	public PublishedPost reply(ReplyTarget target, String text, List<PreparedImage> images) {
		calls.add("reply:" + target.parent().id() + ":" + text);
		attached = images;
		return new PublishedPost("r1", null, "https://example/r1");
	}

	@Override
	public QuoteTarget quoteTarget(String postRef) {
		calls.add("quoteTarget:" + postRef);
		return new QuoteTarget(new PublishedPost(postRef, null, "u"), "@alice@example.social", "public", quoteCaveat);
	}

	@Override
	public NewPost createTopLevelPost(String content, @Nullable QuoteTarget quote, @Nullable PollInput poll,
			List<PreparedImage> images) {
		attached = images;
		calls.add("topLevel:" + content + ":" + (quote == null ? null : quote.quoted().id()) + ":"
				+ (poll == null ? null : poll.options()));
		PublishedPost published = createPost(content, null, null);
		return new NewPost(published, quote == null ? null : quote.caveat());
	}

	@Override
	public VoteResult vote(String postRef, List<Integer> choices) {
		calls.add("vote:" + postRef + ":" + choices);
		return new VoteResult(id, "voted", post(postRef));
	}

	private PostResult post(String postRef) {
		return new PostResult(id, postRef, "@alice@example.social", "text", null, "u", 0, 0, 0, null, null, List.of());
	}

	@Override
	public PartCheck checkPart(int index, String text) {
		int length = TextLength.graphemes(text);
		if (text.isBlank()) {
			return new PartCheck(index, text, length, null, false, "blank");
		}
		boolean ok = length <= 20;
		return new PartCheck(index, text, length, null, ok, ok ? null : TextLength.overReason(length, 20, "graphemes"));
	}

}
