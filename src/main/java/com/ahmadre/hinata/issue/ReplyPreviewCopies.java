package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.content.ContentCopies;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.CopySource;
import com.ahmadre.hinata.content.Placeholders;
import com.ahmadre.hinata.content.ScrubReason;
import com.ahmadre.hinata.richtext.RichTextService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The quote a reply keeps of the comment it answers (HIN-169). A reply written since the quoted
 * comment's id is kept is found by it; an older one by its thread, the quoted author and the very
 * preview it holds, which only that comment can have produced. A few writes per request, however
 * many comments it deleted.
 */
@Component
@RequiredArgsConstructor
class ReplyPreviewCopies implements ContentCopies {

	private final MongoTemplate mongo;
	private final RichTextService richText;

	/** Older replies one write looks for at once. */
	private static final int PER_QUERY = 100;

	@Override
	public void scrub(List<CopySource> sources, ScrubReason reason, Placeholders placeholders) {
		List<CopySource> quoted = sources.stream().filter(source -> source.ref().kind() == ContentKind.COMMENT).toList();
		if (quoted.isEmpty()) {
			return;
		}
		Update quote = Update.update("replyToPreview", placeholders.textFor(reason));
		mongo.updateMulti(Query.query(Criteria.where("replyToTargetId").in(quoted.stream()
				.map(source -> source.ref().id()).toList())), quote, IssueComment.class);
		List<Criteria> older = new ArrayList<>();
		for (CopySource source : quoted) {
			if (source.textDoc() == null || source.rootId() == null || source.ref().parentId() == null) {
				// A voice message quotes as a microphone: no words to scrub.
				continue;
			}
			String preview = IssueService.previewOf(richText.plainText(source.textDoc()));
			if (!preview.isBlank()) {
				older.add(Criteria.where("issueId").is(source.ref().parentId())
						.and("replyToId").is(source.rootId())
						.and("replyToAuthorId").is(source.authorId())
						.and("replyToPreview").is(preview));
			}
		}
		for (int from = 0; from < older.size(); from += PER_QUERY) {
			mongo.updateMulti(Query.query(new Criteria().andOperator(
					new Criteria().orOperator(older.subList(from, Math.min(older.size(), from + PER_QUERY))),
					Criteria.where("replyToTargetId").exists(false))), quote, IssueComment.class);
		}
	}
}
