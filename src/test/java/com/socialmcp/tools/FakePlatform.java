package com.socialmcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import org.jspecify.annotations.Nullable;

import com.socialmcp.model.PartCheck;
import com.socialmcp.model.PostInteractions;
import com.socialmcp.model.PostResult;
import com.socialmcp.model.PostingRules;
import com.socialmcp.model.ProfileResult;
import com.socialmcp.model.PublishedPost;
import com.socialmcp.model.SearchSort;
import com.socialmcp.model.SimilarAccountsResult;
import com.socialmcp.model.TimelineType;
import com.socialmcp.model.TrendsResult;
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
		return new PostingRules(id, 20, "graphemes", null, null, 10, " (n/N)", 8, null, "", "fixed");
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
