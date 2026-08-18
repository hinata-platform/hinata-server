package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.notification.IssueDigestService;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectService;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.mongodb.client.result.UpdateResult;

import java.util.List;

/**
 * Subscribing to an issue's changes, and reading back what one is subscribed to.
 *
 * <p>Kept out of {@link IssueService} deliberately: watching is its own small
 * concern with its own access story, and the issue service is already the
 * largest file in the domain.
 *
 * <p>Two rules run through everything here. Watching <em>requires</em> access —
 * it goes through the ordinary {@code getForUser} gate, so a non-member gets a
 * 403 and never lands in a watcher list. And watching <em>grants</em> nothing —
 * the list below is filtered by project visibility on every read, and the
 * notification fan-out asks the same question again at delivery time.
 */
@Service
@RequiredArgsConstructor
public class IssueWatchService {

	private final IssueService issues;
	private final ProjectService projects;
	private final IssueDigestService digests;
	private final AuditService audit;
	private final MongoTemplate mongo;

	/** Hard cap on one page of watched issues, mirroring the issue search. */
	private static final int MAX_PAGE_SIZE = 100;

	/**
	 * Subscribes the caller to the issue. Idempotent: a second POST is a no-op,
	 * because the write is an {@code $addToSet} rather than a read of the list
	 * followed by a write of it — two requests arriving together would otherwise
	 * each save the list they read and one of them would drop the other's watcher.
	 *
	 * <p>The audit entry follows the write rather than the request: the whole point
	 * of the idempotence is that a client may safely retry one it never saw the
	 * answer to, and two {@code ISSUE_WATCHED} rows for one subscription would
	 * leave the log unable to answer when that subscription actually began.
	 */
	public void watch(String idOrReadableId, User user) {
		Issue issue = issues.getForUser(idOrReadableId, user); // 403 unless they can see it
		UpdateResult result = mongo.updateFirst(byId(issue.getId()),
				new Update().addToSet("watcherIds", user.getId()), Issue.class);
		if (result.getModifiedCount() > 0) {
			audit.event(AuditAction.ISSUE_WATCHED).actor(user)
					.meta("issue", issue.getReadableId()).log();
		}
	}

	/**
	 * Unsubscribes the caller, and throws away whatever was still queued for them
	 * on this issue. A bundled mail can sit in the queue for half an hour; one
	 * landing after someone unsubscribed is precisely what teaches people that the
	 * unsubscribe does not work.
	 *
	 * <p>Deliberately <em>not</em> routed through {@code getForUser}: subscribing
	 * requires access, unsubscribing must not. A subscription outlives the access
	 * that allowed it, and if the only endpoint that can remove it answered such a
	 * caller with a 403, the owner of that subscription could never take it back —
	 * the delivery-time filter would silence it, but it would sit in the list
	 * forever, unremovable by the one person it belongs to. Nothing is revealed by
	 * allowing it: the write only ever touches the caller's own id.
	 */
	public void unwatch(String idOrReadableId, User user) {
		Issue issue = issues.get(idOrReadableId);
		UpdateResult result = mongo.updateFirst(byId(issue.getId()),
				new Update().pull("watcherIds", user.getId()), Issue.class);
		digests.discard(user.getId(), issue.getId());
		if (result.getModifiedCount() > 0) {
			audit.event(AuditAction.ISSUE_UNWATCHED).actor(user)
					.meta("issue", issue.getReadableId()).log();
		}
	}

	/**
	 * The caller's watched issues, newest-touched first.
	 *
	 * <p>Scoped to the projects they can currently see rather than filtered after
	 * paging: a subscription survives losing access to its project, and a page
	 * that fetched ten rows and then dropped six of them would be both a leak of
	 * "something exists here" and a page of four. The scope is the same
	 * {@code ProjectReach} rule the rest of the app uses, applied in the query so
	 * the count and the page agree.
	 */
	public Page<Issue> watchedBy(User user, int page, int size) {
		Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
				// _id breaks ties so a row can never be shown on two pages (or on
				// none) when several issues share an updatedAt.
				Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("_id")));
		List<String> scope = user.isAdmin()
				? List.copyOf(projects.activeProjectIds())
				: projects.visibleTo(user).stream().map(Project::getId).toList();
		if (scope.isEmpty()) {
			return Page.empty(pageable);
		}
		Query query = new Query(Criteria.where("watcherIds").is(user.getId())
				// Archived issues are soft-deleted and hidden from every default
				// listing; the watch list is no exception. Asked as an EQUALITY, not
				// as $ne: a $ne is a two-interval bound, and a multi-interval bound
				// sitting before the sort keys is exactly what stops Mongo taking the
				// sort from the index (see the watchers_updated index on Issue).
				.and("archived").is(false)
				.and("projectId").in(scope));
		long total = mongo.count(query, Issue.class);
		List<Issue> content = mongo.find(query.with(pageable), Issue.class);
		return new PageImpl<>(content, pageable, total);
	}

	private static Query byId(String issueId) {
		return new Query(Criteria.where("_id").is(issueId));
	}
}
