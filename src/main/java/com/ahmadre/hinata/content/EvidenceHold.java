package com.ahmadre.hinata.content;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Whether a case still needs content as evidence (HIN-169). Implemented by the moderation module;
 * where no implementation exists, nothing is held.
 *
 * <p>Asked by every way content is deleted: when an open case or a legal hold names it, its
 * objects move into that case's quarantine instead of being deleted, because DSA Art. 18 asks for
 * all relevant information.
 */
public interface EvidenceHold {

	/** The case holding [ref], if one does. */
	Optional<String> holdingCaseId(ContentRef ref);

	/**
	 * The cases holding any of [refs], by ref; a ref no case holds is not in it. An implementation
	 * answers this in a few reads however many refs there are; the default asks one at a time.
	 */
	default Map<ContentRef, String> holdingCaseIds(Collection<ContentRef> refs) {
		Map<ContentRef, String> held = new LinkedHashMap<>();
		for (ContentRef ref : refs) {
			holdingCaseId(ref).ifPresent(caseId -> held.put(ref, caseId));
		}
		return held;
	}
}
