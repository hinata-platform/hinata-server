package com.ahmadre.hinata.richtext;

/**
 * Which form of a stored document a response gives in {@code descriptionDoc} / {@code textDoc}
 * (HIN-181), chosen per request by the {@code X-Hinata-Doc} header.
 *
 * <p>An app that reads layouts sends {@code X-Hinata-Doc: layout} and gets the full document in
 * the field it always read, and nothing beside it. Every other client, a published app above
 * all, gets the {@link LegacyDocs legacy projection} there and the full document in the additive
 * field, as before. Held for the request's thread by {@code config.DocFormFilter}, because the
 * entities write themselves out through Jackson far from any controller.
 */
public enum DocForm {

	/** The legacy projection, the full document beside it when it differs. */
	LEGACY,

	/** The full document, nothing beside it. */
	LAYOUT;

	/** The request header that asks for {@link #LAYOUT}. */
	public static final String HEADER = "X-Hinata-Doc";

	private static final ThreadLocal<DocForm> CURRENT = new ThreadLocal<>();

	/** The form of the request on this thread; {@link #LEGACY} outside of one. */
	public static DocForm current() {
		DocForm form = CURRENT.get();
		return form == null ? LEGACY : form;
	}

	/** The form [header] asks for: {@link #LAYOUT} for {@code layout}, else {@link #LEGACY}. */
	public static DocForm of(String header) {
		return header != null && "layout".equalsIgnoreCase(header.strip()) ? LAYOUT : LEGACY;
	}

	/** Sets the form of this thread's request; [form] null clears it. */
	public static void set(DocForm form) {
		if (form == null) CURRENT.remove();
		else CURRENT.set(form);
	}
}
