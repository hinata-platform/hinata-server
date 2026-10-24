package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.article.Article;
import com.ahmadre.hinata.article.ArticleRepository;
import com.ahmadre.hinata.article.ArticleService;
import com.ahmadre.hinata.article.ArticleVersion;
import com.ahmadre.hinata.content.ContentKind;
import com.ahmadre.hinata.content.ContentSnapshot;
import com.ahmadre.hinata.content.MarkState;
import com.ahmadre.hinata.content.Removal;
import com.ahmadre.hinata.content.RemovalScope;
import com.ahmadre.hinata.content.ReportCategory;
import com.ahmadre.hinata.content.RestoreOutcome;
import com.ahmadre.hinata.content.TargetRef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Removing a knowledge page (HIN-169): vandalism on a page everyone may edit is reverted, not the
 * page emptied, and only the reported versions go to the quarantine.
 */
class ArticleRemovalIntegrationTest extends RemovalIntegrationSupport {

	@Autowired
	private ArticleRepository articles;
	@Autowired
	private ArticleService articleService;

	private Article page(String title, String text) {
		return articles.save(Article.builder().title(title).content(text).contentDoc(doc(text))
				.projectId(project.getId()).authorId(owner.getId()).lastEditorId(owner.getId()).build());
	}

	private void version(Article page, int number, String text, String editorId) {
		mongo.insert(ArticleVersion.builder().articleId(page.getId()).number(number).title(page.getTitle())
				.contentDoc(doc(text)).editorId(editorId).createdAt(Instant.now()).build());
	}

	private List<Integer> numbers(Article page) {
		return mongo.find(Query.query(Criteria.where("articleId").is(page.getId())).with(Sort.by("number")),
				ArticleVersion.class).stream().map(ArticleVersion::getNumber).toList();
	}

	@Test
	void revertingGoesBackToTheVersionBeforeTheReportedOneAndKeepsEverybodyElsesWork() {
		Article page = page("Aufbauplan", "Stand 3: Lena ist eine Idiotin");
		version(page, 1, "Stand 1", owner.getId());
		version(page, 2, "Stand 2 von Lena", reader.getId());
		version(page, 3, "Stand 3: Lena ist eine Idiotin", author.getId());
		ContentSnapshot seen = surface(ContentKind.ARTICLE).forModeration(TargetRef.of(ContentKind.ARTICLE, page.getId()))
				.orElseThrow();
		assertThat(seen.contributorIds()).contains(owner.getId(), reader.getId(), author.getId());
		assertThat(seen.ref().parentId()).isEqualTo("3");

		Removal removal = surface(ContentKind.ARTICLE).remove(
				new TargetRef(ContentKind.ARTICLE, page.getId(), seen.ref().parentId()), RemovalScope.REVERT, CASE,
				ReportCategory.HARASSMENT, placeholders);

		Article reverted = articles.findById(page.getId()).orElseThrow();
		assertThat(reverted.getContent()).isEqualTo("Stand 2 von Lena");
		assertThat(reverted.getModeration()).isNullOrEmpty();
		// Versions 1 and 2 stay, 3 waits in the quarantine, 4 records the reset.
		assertThat(numbers(page)).containsExactly(1, 2, 4);
		ArticleVersion reset = mongo.findOne(Query.query(Criteria.where("articleId").is(page.getId()).and("number").is(4)),
				ArticleVersion.class);
		assertThat(reset.getNote()).isEqualTo(placeholders.revertNote());
		assertThat(reset.getEditorId()).isNull();
		assertThat(mongo.count(Query.query(Criteria.where("quarantineId").is(removal.quarantineId())),
				"moderation_quarantine_items")).isEqualTo(1);

		assertThat(surface(ContentKind.ARTICLE).restore(removal)).isEqualTo(RestoreOutcome.RESTORED);
		assertThat(articles.findById(page.getId()).orElseThrow().getContent()).isEqualTo("Stand 3: Lena ist eine Idiotin");
		assertThat(numbers(page)).containsExactly(1, 2, 3);
	}

	@Test
	void anEditAfterTheResetIsNotRestoredOver() {
		Article page = page("Aufbauplan", "Stand 2");
		version(page, 1, "Stand 1", owner.getId());
		version(page, 2, "Stand 2", author.getId());
		Removal removal = surface(ContentKind.ARTICLE).remove(new TargetRef(ContentKind.ARTICLE, page.getId(), "2"),
				RemovalScope.REVERT, CASE, ReportCategory.HARASSMENT, placeholders);

		Article reverted = articles.findById(page.getId()).orElseThrow();
		reverted.setContentDoc(doc("Stand 4 von Lena"));
		reverted.setContent("Stand 4 von Lena");
		articleService.save(reverted, reverted.getParentId(), reader);

		assertThat(surface(ContentKind.ARTICLE).restore(removal)).isEqualTo(RestoreOutcome.REWRITTEN_SINCE);
		assertThat(articles.findById(page.getId()).orElseThrow().getContent()).isEqualTo("Stand 4 von Lena");
	}

	@Test
	void removingAPageWholeShowsThePlaceholderAndMovesEveryVersion() {
		Article page = page("Hetze", "Hetze im Text");
		version(page, 1, "Hetze im Text", author.getId());

		remove(ContentKind.ARTICLE, page.getId(), null, RemovalScope.WHOLE, ReportCategory.ILLEGAL);

		Article removed = articles.findById(page.getId()).orElseThrow();
		assertThat(removed.getTitle()).isEqualTo(placeholders.removedTitle());
		assertThat(removed.getContent()).isEqualTo(placeholders.removedText());
		assertThat(removed.getModeration().get("content").state()).isEqualTo(MarkState.REMOVED);
		assertThat(numbers(page)).isEmpty();

		// The page stays editable; whoever writes it anew writes over the placeholder.
		removed.setTitle("Neuer Plan");
		removed.setContentDoc(doc("Neuer Text"));
		articleService.save(removed, removed.getParentId(), reader);
		assertThat(articles.findById(page.getId()).orElseThrow().getModeration().get("title").state())
				.isEqualTo(MarkState.REWRITTEN);
	}

	@Test
	void aHundredLargeVersionsMoveIntoTheQuarantineTenAtATime() {
		String large = "x".repeat(900_000);
		Article page = page("Gross", "Gross");
		List<ArticleVersion> versions = new ArrayList<>();
		for (int number = 1; number <= 100; number++) {
			versions.add(ArticleVersion.builder().articleId(page.getId()).number(number).title("Gross")
					.contentDoc(doc(large)).editorId(author.getId()).createdAt(Instant.now()).build());
			if (versions.size() == 10) {
				mongo.insertAll(versions);
				versions.clear();
			}
		}

		Removal removal = remove(ContentKind.ARTICLE, page.getId(), null, RemovalScope.WHOLE, ReportCategory.SPAM);

		assertThat(numbers(page)).isEmpty();
		assertThat(mongo.count(Query.query(Criteria.where("quarantineId").is(removal.quarantineId())),
				"moderation_quarantine_items")).isEqualTo(100);
	}
}
