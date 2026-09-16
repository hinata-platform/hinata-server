package com.ahmadre.hinata.common;

/**
 * The MongoDB image the integration tests run against.
 *
 * <p>Live runs 8.0, the long-term release, and so does CI: a test that passes against another
 * version says little about the one serving people. A workstation may need a different one. Docker
 * Desktop ships Linux kernels from 6.19 on, and MongoDB 8.0 refuses to start on those
 * (<a href="https://jira.mongodb.org/browse/SERVER-121912">SERVER-121912</a>, fixed in kernel
 * 7.0.14), which left every container test on such a machine unrunnable.
 *
 * <p>So the image is named here once and can be pointed elsewhere for a single run:
 *
 * <pre>HINATA_TEST_MONGO_IMAGE=mongo:8.2.12 ./gradlew test</pre>
 *
 * <p>8.2 reads an 8.0 data directory and leaves the feature compatibility at 8.0, so nothing about
 * the data moves on ahead of the version that runs live.
 */
public final class TestMongo {

	/** What live runs; overridden per run by {@code HINATA_TEST_MONGO_IMAGE}. */
	public static final String IMAGE = imageFromEnvironment();

	private TestMongo() {
	}

	private static String imageFromEnvironment() {
		String named = System.getenv("HINATA_TEST_MONGO_IMAGE");
		return named == null || named.isBlank() ? "mongo:8.0" : named.trim();
	}
}
