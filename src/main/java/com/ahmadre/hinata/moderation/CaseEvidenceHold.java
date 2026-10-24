package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.ContentRef;
import com.ahmadre.hinata.content.EvidenceHold;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The cases that still need content as evidence (HIN-170): an open case on it, or any case on it
 * under a legal hold. Asked by every way content is deleted (HIN-169), for everything one request
 * deletes at once: two reads however much it is.
 */
@Component
@RequiredArgsConstructor
class CaseEvidenceHold implements EvidenceHold {

	private final MongoTemplate mongo;
	private final Clock clock;

	@Override
	public Optional<String> holdingCaseId(ContentRef ref) {
		return ref == null ? Optional.empty() : Optional.ofNullable(holdingCaseIds(List.of(ref)).get(ref));
	}

	@Override
	public Map<ContentRef, String> holdingCaseIds(Collection<ContentRef> refs) {
		Map<String, List<ContentRef>> byKey = new LinkedHashMap<>();
		for (ContentRef ref : refs) {
			if (ref != null && ref.id() != null) {
				byKey.computeIfAbsent(ModerationCase.keyOf(ref), key -> new ArrayList<>()).add(ref);
			}
		}
		if (byKey.isEmpty()) {
			return Map.of();
		}
		Map<String, String> caseByKey = new HashMap<>();
		// Off open_target: the filter repeats its partial filter.
		Query open = Query.query(Criteria.where("targetKey").in(byKey.keySet()).exists(true)
				.and("status").is(CaseStatus.OPEN));
		open.fields().include("_id", "targetKey");
		mongo.find(open, ModerationCase.class).forEach(found -> caseByKey.putIfAbsent(found.getTargetKey(), found.getId()));
		List<String> ids = byKey.entrySet().stream().filter(entry -> !caseByKey.containsKey(entry.getKey()))
				.map(entry -> entry.getValue().get(0).id()).distinct().toList();
		if (!ids.isEmpty()) {
			// Off legal_hold: the $exists repeats its partial filter. The kind is matched by the key.
			Query held = Query.query(Criteria.where("target.id").in(ids)
					.and("legalHoldUntil").gt(clock.instant()).exists(true));
			held.fields().include("_id", "target");
			for (ModerationCase found : mongo.find(held, ModerationCase.class)) {
				caseByKey.putIfAbsent(ModerationCase.keyOf(found.getTarget()), found.getId());
			}
		}
		Map<ContentRef, String> holding = new LinkedHashMap<>();
		byKey.forEach((key, named) -> {
			String caseId = caseByKey.get(key);
			if (caseId != null) {
				named.forEach(ref -> holding.put(ref, caseId));
			}
		});
		return holding;
	}
}
