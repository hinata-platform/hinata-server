package com.ahmadre.hinata.moderation.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ContentReportRepository extends MongoRepository<ContentReport, String> {

	/**
	 * Whether this reporter already has an unjudged report on this exact target.
	 * Filing the same notice twice does not make it truer; it only inflates the queue
	 * and hides the reports nobody has seen yet. Satisfied by the equality prefix of
	 * the {@code reporter_target_state} index.
	 */
	boolean existsByReporterIdAndTargetTypeAndTargetIdAndState(String reporterId,
			ContentReport.TargetType targetType, String targetId, ContentReport.State state);

	/** The moderator queue, newest first — the admin surface reads through here. */
	Page<ContentReport> findByStateOrderByCreatedAtDesc(ContentReport.State state, Pageable pageable);

	/**
	 * Every report about one target, whatever its state. What a moderator opens to
	 * judge a case: five independent notices about the same comment is a different
	 * situation from one, and only this view shows that.
	 */
	List<ContentReport> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
			ContentReport.TargetType targetType, String targetId);

	/** Reports a user filed — for the GDPR self-service data export. */
	List<ContentReport> findByReporterIdOrderByCreatedAtDesc(String reporterId);

	long countByState(ContentReport.State state);
}
