package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.common.UserWords;
import com.ahmadre.hinata.search.SearchCategory;
import com.ahmadre.hinata.search.SearchContext;
import com.ahmadre.hinata.search.SearchHit;
import com.ahmadre.hinata.search.SearchSource;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Absence management in the global search (HIN-165): the absence types on offer by name, and the
 * caller's own requests by their type's name or their note.
 *
 * <p>Asked only while absence management is on ({@link TimeOffSettings#enabled()}). Requests are
 * only ever the caller's own: {@code userId} is the equality of the query, on the
 * {@code user_from} index, so nobody else's request is read, approvers' included. The types are
 * the catalogue everybody is offered ({@code active != false}, as {@link TimeOffTypeService#list}),
 * found by their name or, for the built-in ones that have none, by their label in the caller's
 * language.
 * Three reads: the matching types, the requests, and the names of the requests' types.
 */
@Component
@RequiredArgsConstructor
class TimeOffSearch implements SearchSource {

	/** Most matching types a request query asks for by id. The catalogue is small; this only bounds it. */
	static final int TYPE_MATCH_MAX = 50;

	private static final String REQUESTS = "/time/absences?scope=requests";

	/** The built-in types, which carry no name of their own and are called by a label per language. */
	private static final List<String> SYSTEM_KEYS = List.of(
			TimeOffType.SYSTEM_VACATION, TimeOffType.SYSTEM_SICK, TimeOffType.SYSTEM_OTHER);

	private final TimeOffSettings settings;
	private final MongoTemplate mongo;
	private final UserWords words;

	@Override
	public SearchCategory category() {
		return SearchCategory.ABSENCES;
	}

	@Override
	public boolean enabled() {
		return settings.enabled();
	}

	@Override
	public List<SearchHit> search(SearchContext context, int cap) {
		// A built-in type without a name of its own matches by its label in the caller's language,
		// worked out here for three keys and put into the query as systemKey $in.
		List<String> labelled = SYSTEM_KEYS.stream().filter(key -> context.matches(label(key, context))).toList();
		Criteria named = labelled.isEmpty() ? context.contains("name")
				: new Criteria().orOperator(context.contains("name"), new Criteria().andOperator(
						new Criteria().orOperator(Criteria.where("name").is(null), Criteria.where("name").is("")),
						Criteria.where("systemKey").in(labelled)));
		Query types = Query.query(new Criteria().andOperator(Criteria.where("active").ne(false), named))
				.with(TimeOffTypeRepository.BY_NAME).limit(TYPE_MATCH_MAX);
		List<TimeOffType> matching = mongo.find(types, TimeOffType.class);
		Set<String> typeIds = new LinkedHashSet<>();
		matching.forEach(type -> typeIds.add(type.getId()));

		Criteria text = typeIds.isEmpty() ? context.contains("note")
				: new Criteria().orOperator(context.contains("note"), Criteria.where("typeId").in(typeIds));
		List<SearchHit> hits = new ArrayList<>(requests(Criteria.where("userId").is(context.userId()), text,
				context, cap));
		for (TimeOffType type : matching) {
			if (hits.size() >= cap) break;
			hits.add(type(type, context));
		}
		return hits;
	}

	/** The caller's latest requests. */
	@Override
	public List<SearchHit> suggest(SearchContext context, int limit) {
		return requests(Criteria.where("userId").is(context.userId()), null, context, limit);
	}

	private List<SearchHit> requests(Criteria mine, Criteria text, SearchContext context, int limit) {
		Query query = Query.query(text == null ? mine : new Criteria().andOperator(mine, text))
				.with(Sort.by(Sort.Direction.DESC, "from", "_id")).limit(limit);
		List<TimeOffRequest> found = mongo.find(query, TimeOffRequest.class);
		Map<String, TimeOffType> types = types(found);
		return found.stream().map(request -> {
			TimeOffType type = types.get(request.getTypeId());
			return SearchHit.builder()
					.category(SearchCategory.ABSENCES.name())
					.kind("REQUEST")
					.id(request.getId())
					.route(REQUESTS + "&request=" + request.getId())
					.title(type == null ? null : nameOf(type, context))
					.subtitle(request.getNote() == null || request.getNote().isBlank() ? null
							: context.excerpt(request.getNote(), 120))
					.from(request.getFrom())
					.to(request.getTo())
					.status(request.getStatus() == null ? null : request.getStatus().name())
					.icon(type == null ? null : type.getIcon())
					.hue(type == null ? null : type.getHue())
					.build();
		}).toList();
	}

	private Map<String, TimeOffType> types(List<TimeOffRequest> requests) {
		Set<String> ids = new LinkedHashSet<>();
		requests.forEach(request -> {
			if (request.getTypeId() != null) ids.add(request.getTypeId());
		});
		if (ids.isEmpty()) return Map.of();
		Query query = Query.query(Criteria.where("_id").in(ids));
		query.fields().include("name", "systemKey", "icon", "hue");
		Map<String, TimeOffType> byId = new HashMap<>();
		mongo.find(query, TimeOffType.class).forEach(type -> byId.put(type.getId(), type));
		return byId;
	}

	/** What the type is called for the caller: an operator's name, or the built-in label. */
	private String nameOf(TimeOffType type, SearchContext context) {
		return type.getName() != null && !type.getName().isBlank() ? type.getName()
				: label(type.getSystemKey() == null ? TimeOffType.SYSTEM_OTHER : type.getSystemKey(), context);
	}

	private String label(String systemKey, SearchContext context) {
		return words.of(context.user(), "timeOff.type." + systemKey);
	}

	private SearchHit type(TimeOffType type, SearchContext context) {
		return SearchHit.builder()
				.category(SearchCategory.ABSENCES.name())
				.kind("TYPE")
				.id(type.getId())
				// The caller's absences, with a new request of this type open.
				.route(REQUESTS + "&type=" + type.getId())
				.title(nameOf(type, context))
				.icon(type.getIcon())
				.hue(type.getHue())
				.build();
	}
}
