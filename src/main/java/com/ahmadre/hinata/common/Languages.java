package com.ahmadre.hinata.common;

import java.util.List;

/** The languages the server speaks: its messages, its mails and its public pages. */
public final class Languages {

	private Languages() {
	}

	/** Every language the server speaks, the order the bundles are kept in. */
	public static final List<String> SUPPORTED = List.of("en", "de", "zh", "hi", "es", "ja", "fr", "ru", "ar");
}
