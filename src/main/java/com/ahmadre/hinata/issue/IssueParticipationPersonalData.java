package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.me.PersonalDataExport;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The issues shared with somebody from outside their project (HIN-136), in that person's own data
 * export (Art. 15 DSGVO): a share is an access right and is stored with when and by whom it was
 * made. Up to {@value #CAP} issues, with a flag or a line when cut; an issue's title is shown,
 * nothing else of it.
 */
@Component
@RequiredArgsConstructor
public class IssueParticipationPersonalData implements PersonalDataExport {

	static final int CAP = 500;

	private final MongoTemplate mongo;
	private final UserRepository users;
	private final UserWords words;

	/** One share as the export lists it. */
	private record Share(String issue, String title, Instant addedAt, String addedBy) {
	}

	@Override
	public String key() {
		return "sharedIssues";
	}

	@Override
	public Object data(User user) {
		List<Share> shares = sharesOf(user, CAP + 1);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("issues", shares.stream().limit(CAP).map(share -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("issue", share.issue());
			row.put("title", share.title());
			row.put("addedAt", share.addedAt());
			row.put("addedBy", share.addedBy());
			return row;
		}).toList());
		out.put("truncated", shares.size() > CAP);
		return out;
	}

	@Override
	public List<Table> tables(User user, Locale locale) {
		List<Share> shares = sharesOf(user, CAP + 1);
		if (shares.isEmpty()) {
			return List.of();
		}
		return List.of(new Table(words.in(locale, "export.pdf.sharedIssues.title"),
				List.of(words.in(locale, "export.pdf.sharedIssues.issue"),
						words.in(locale, "export.pdf.sharedIssues.issueTitle"),
						words.in(locale, "export.pdf.sharedIssues.addedAt"),
						words.in(locale, "export.pdf.sharedIssues.addedBy")),
				new float[]{2, 6, 3, 3},
				shares.stream().limit(CAP).map(share -> List.of(share.issue(), share.title(),
						PersonalDataExport.instant(share.addedAt()), share.addedBy())).toList(),
				shares.size() > CAP ? words.in(locale, "export.pdf.sharedIssues.capped", CAP) : null));
	}

	private List<Share> sharesOf(User user, int limit) {
		Query query = Query.query(Criteria.where("participantIds").is(user.getId()))
				.with(Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.DESC, "_id")))
				.limit(limit);
		query.fields().include("readableId", "title", "participantMeta");
		// Plain documents: a projection into Issue fails on its primitive fields.
		List<Document> found = mongo.find(query, Document.class, mongo.getCollectionName(Issue.class));
		Set<String> adders = new HashSet<>();
		for (Document issue : found) {
			Document meta = metaOf(issue, user.getId());
			if (meta != null && meta.getString("addedById") != null) adders.add(meta.getString("addedById"));
		}
		Map<String, String> names = new HashMap<>();
		users.findAllById(adders).forEach(adder -> names.put(adder.getId(), adder.getDisplayName()));
		return found.stream().map(issue -> {
			Document meta = metaOf(issue, user.getId());
			java.util.Date addedAt = meta == null ? null : meta.getDate("addedAt");
			String addedBy = meta == null ? null : meta.getString("addedById");
			return new Share(issue.getString("readableId"), issue.getString("title"),
					addedAt == null ? null : addedAt.toInstant(),
					addedBy == null ? "—" : names.getOrDefault(addedBy, "—"));
		}).toList();
	}

	private static Document metaOf(Document issue, String userId) {
		List<Document> meta = issue.getList("participantMeta", Document.class);
		if (meta == null) return null;
		return meta.stream().filter(entry -> userId.equals(entry.getString("userId"))).findFirst().orElse(null);
	}
}
