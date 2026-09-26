package com.socialmcp.platform;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.socialmcp.model.AccountAction;
import com.socialmcp.model.NewPost;
import com.socialmcp.model.PartCheck;
import com.socialmcp.model.PollInput;
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

	/** Whether the platform has polls at all (Mastodon yes, Bluesky no). Answered without any HTTP call. */
	boolean supportsPolls();

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

	/** Measures one final post text, including any numbering suffix or reply mention prefix (SPEC §6.2). */
	PartCheck checkPart(int index, String text);

	/**
	 * Performs one {@code setAccountRelationship} action (SPEC §4, Tool 12). Reads the current relationship first and
	 * makes no write when there is nothing to change.
	 */
	RelationshipResult setRelationship(String handle, AccountAction action);

	/**
	 * Performs one {@code setPostAction} action (SPEC §4, Tool 13). Reads the post first and makes no write when there
	 * is nothing to change.
	 * @param postRef a post id or public URL (SPEC §6.9)
	 */
	PostActionResult setPostAction(String postRef, PostAction action);

	/** The configured account's bookmarks, most recently bookmarked first (SPEC §4, Tool 15). */
	List<PostResult> getBookmarks(int limit);

	/** Reads the post being replied to and returns what the reply needs (SPEC §5, Reply). */
	ReplyTarget replyTarget(String postRef);

	/**
	 * Publishes {@code text}, already prefixed and measured, as a reply to {@code target}, with zero to four images that
	 * already passed SPEC §6.14.
	 */
	PublishedPost reply(ReplyTarget target, String text, List<PreparedImage> images);

	/** Reads the post to quote and checks that the configured account may quote it (SPEC §5, Quote). */
	QuoteTarget quoteTarget(String postRef);

	/**
	 * Publishes a top-level post with at most one of a quote or a poll, and zero to four images (SPEC §4, Tool 8). The
	 * poll has already passed SPEC §6.12, and the images SPEC §6.14 including the combination rules; the service only
	 * uploads and attaches them (SPEC §5, Images).
	 */
	NewPost createTopLevelPost(String content, @Nullable QuoteTarget quote, @Nullable PollInput poll,
			List<PreparedImage> images);

	/** Whether images must have EXIF/XMP metadata stripped before upload (SPEC §6.14, step 9). */
	boolean stripsImageMetadata();

	/**
	 * Votes in the poll on a post (SPEC §4, Tool 16).
	 * @param choices 1-based option numbers, already checked to be distinct and at least 1
	 */
	VoteResult vote(String postRef, List<Integer> choices);

}
