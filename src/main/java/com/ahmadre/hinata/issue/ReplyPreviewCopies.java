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

import java.util.List;

/**
 * The quote a reply keeps of the comment it answers (HIN-169). A reply written since the quoted
 * comment's id is kept is found by it; an older one by its thread, the quoted author and the very
 * preview it holds, which only that comment can have produced.
 */
@Component
@RequiredArgsConstructor
class ReplyPreviewCopies implements ContentCopies {

	private final MongoTemplate mongo;
	private final RichTextService richText;

	@Override
	public void scrub(List<CopySource> sources, ScrubReason reason, Placeholders placeholders) {
		Update quote = Update.update("replyToPreview", placeholders.textFor(reason));
		for (CopySource source : sources) {
			if (source.ref().kind() != ContentKind.COMMENT) {
				continue;
			}
			mongo.updateMulti(Query.query(Criteria.where("replyToTargetId").is(source.ref().id())), quote,
					IssueComment.class);
			if (source.textDoc() == null || source.rootId() == null || source.ref().parentId() == null) {
				// A voice message quotes as a microphone: no words to scrub.
				continue;
			}
			String preview = IssueService.previewOf(richText.plainText(source.textDoc()));
			if (preview.isBlank()) {
				continue;
			}
			mongo.updateMulti(Query.query(Criteria.where("issueId").is(source.ref().parentId())
					.and("replyToId").is(source.rootId())
					.and("replyToAuthorId").is(source.authorId())
					.and("replyToPreview").is(preview)
					.and("replyToTargetId").exists(false)), quote, IssueComment.class);
		}
	}
}
