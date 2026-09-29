package com.ahmadre.hinata.article;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.me.PersonalDataExport;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A person's private knowledge-base pages in their data export (Art. 15/20 DSGVO),
 * and gone with their account (Art. 17).
 *
 * <p>Since HIN-129 a page with neither project nor team is readable by its author
 * alone. Nobody else could hand it over or delete it, so the person's own rights
 * have to reach it here. Pages in a project or a team belong to that place and
 * stay, as issues and comments do.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticlePersonalData implements PersonalDataExport {

	/**
	 * Pages in the JSON export. Far beyond what one person writes, so the export is
	 * complete in practice; past it the export says so rather than cutting silently.
	 */
	static final int CAP = 10_000;

	/** Rows in the PDF's table, which is a summary; the JSON carries every page. */
	static final int TABLE_ROWS = 500;

	private final MongoTemplate mongo;
	private final UserWords words;

	@Override
	public String key() {
		return "privateKnowledgePages";
	}

	@Override
	public Object data(User user) {
		List<Article> pages = privatePagesOf(user.getId(), CAP + 1, true);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("pages", pages.stream().limit(CAP).map(page -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", page.getId());
			row.put("title", page.getTitle());
			row.put("content", page.getContent());
			row.put("createdAt", page.getCreatedAt());
			row.put("updatedAt", page.getUpdatedAt());
			return row;
		}).toList());
		out.put("pagesTruncated", pages.size() > CAP);
		return out;
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		List<Article> pages = privatePagesOf(user.getId(), TABLE_ROWS + 1, false);
		return List.of(new Table(words.in(locale, "export.pdf.knowledge.privatePages"),
				List.of(words.in(locale, "export.pdf.knowledge.title"), words.in(locale, "export.pdf.knowledge.updated")),
				new float[]{6, 2},
				pages.stream().limit(TABLE_ROWS)
						.map(page -> List.of(page.getTitle() == null ? "—" : page.getTitle(),
								PersonalDataExport.instant(page.getUpdatedAt())))
						.toList(),
				pages.size() > TABLE_ROWS ? words.in(locale, "export.pdf.time.listCapped", TABLE_ROWS) : null));
	}

	/** The account is gone; so are the pages only it could read. */
	@EventListener
	public void onUserDeleted(UserService.UserDeletedEvent event) {
		try {
			long removed = mongo.remove(Query.query(privateOf(event.userId())), Article.class).getDeletedCount();
			if (removed > 0) {
				log.info("Removed {} private knowledge page(s) of a deleted account", removed);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Could not remove the private pages of a deleted account", ex);
		}
	}

	/**
	 * The person's private pages, newest first: with their text for the JSON, titles
	 * and dates only for the table. Never the rich document, which the text already
	 * says in a form anybody can read.
	 */
	private List<Article> privatePagesOf(String userId, int limit, boolean withText) {
		Query query = Query.query(privateOf(userId)).with(Sort.by(Sort.Order.desc("updatedAt"))).limit(limit);
		query.fields().include("_id").include("title").include("createdAt").include("updatedAt").include("sortOrder");
		if (withText) query.fields().include("content");
		return mongo.find(query, Article.class);
	}

	private static Criteria privateOf(String userId) {
		return Criteria.where("authorId").is(userId).and("projectId").is(null).and("teamId").is(null);
	}
}
