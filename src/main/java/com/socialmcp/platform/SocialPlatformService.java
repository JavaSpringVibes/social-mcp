package com.socialmcp.platform;

import java.util.List;

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

/**
 * One social platform behind a common interface (SPEC §3).
 *
 * <p>The {@code limit} passed to read methods has already had the default applied and been capped at
 * {@code social.read.max-limit}; each implementation caps it further at the endpoint maximum. Handles are
 * already normalized (no leading {@code @}) and validated.
 */
public interface SocialPlatformService {

	/** Canonical lowercase id, e.g. {@code "mastodon"}. */
	String platform();

	boolean isConfigured();

	/** Whether {@code handle} (already stripped of a leading {@code @}) is valid on this platform (SPEC §6.7). */
	boolean isValidHandle(String handle);

	List<PostResult> searchPosts(String query, SearchSort sort, int limit);

	List<PostResult> getTimeline(TimelineType type, int limit);

	List<PostResult> getUserPosts(String handle, int limit);

	/** @param handle a normalized handle, or {@code null} for the configured account */
	ProfileResult getProfile(@Nullable String handle);

	/** @param postRef a post id or public URL (SPEC §6.9) */
	PostInteractions getPostInteractions(String postRef, int limit);

	TrendsResult getTrends(int limit);

	SimilarAccountsResult findSimilarAccounts(String handle, int limit);

	/**
	 * Publishes one post. {@code root} and {@code parent} are both null for a top-level post; for a thread part,
	 * {@code root} is the thread's first post and {@code parent} the previous part.
	 */
	PublishedPost createPost(String content, @Nullable PublishedPost root, @Nullable PublishedPost parent);

	PostingRules postingRules();

	/** Measures one final post text, including any numbering suffix (SPEC §6.2). */
	PartCheck checkPart(int index, String text);

}
