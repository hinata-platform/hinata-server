package com.ahmadre.hinata.issue;

import org.springframework.data.mongodb.core.mapping.event.BeforeConvertCallback;
import org.springframework.stereotype.Component;

/** Computes an issue's search text on every save, from what the saved issue holds. */
@Component
class IssueSearchTextCallback implements BeforeConvertCallback<Issue> {

	@Override
	public Issue onBeforeConvert(Issue issue, String collection) {
		issue.setSearchText(IssueSearchText.of(issue.getReadableId(), issue.getTitle(), issue.getTags()));
		return issue;
	}
}
