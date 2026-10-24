package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.content.ContentCopies;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.CopySource;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.ScrubReason;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The history of an issue's title (HIN-169): every change of it keeps the title before and after,
 * and the history, the export and MCP all read it. Only the removed title is replaced; earlier
 * titles nobody decided on stay as they were.
 */
@Component
@RequiredArgsConstructor
class ActivityCopies implements ContentCopies {

	private final MongoTemplate mongo;

	@Override
	public void scrub(List<CopySource> sources, ScrubReason reason, Placeholders placeholders) {
		String placeholder = reason == ScrubReason.REMOVED ? placeholders.removedTitle() : placeholders.deletedText();
		for (CopySource source : sources) {
			if (source.ref().kind() != ContentKind.ISSUE || source.title() == null) {
				continue;
			}
			Criteria titles = Criteria.where("issueId").is(source.ref().id()).and("field").is(IssueActivity.Field.TITLE);
			for (String side : List.of("fromValue", "toValue")) {
				mongo.updateMulti(Query.query(new Criteria().andOperator(titles,
								Criteria.where(side).is(source.title()))),
						Update.update(side, placeholder), IssueActivity.class);
			}
		}
	}
}
