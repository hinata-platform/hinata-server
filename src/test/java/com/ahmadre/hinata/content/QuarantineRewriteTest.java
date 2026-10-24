package com.ahmadre.hinata.content;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** After a hold the content is rewritten first and the originals go last; a failed rewrite keeps them. */
class QuarantineRewriteTest {

	private final List<String> steps = new ArrayList<>();
	private final Quarantine.Held held = new Quarantine.Held("q-1", "case-1", List.of());

	private final Quarantine quarantine = new Quarantine() {
		@Override
		public Held hold(String caseId, ContentRef ref, RemovalScope scope, Map<String, Object> fields,
				List<MediaRef> move, List<MediaRef> copy) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void keepVersions(Held held, List<VersionCopy> versions) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void discard(Held held) {
			steps.add("discard");
		}

		@Override
		public List<String> deleteOriginals(Held held) {
			steps.add("delete");
			return List.of("still-there");
		}

		@Override
		public List<String> deleteOriginals(Collection<String> keys) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean restoreObjects(String quarantineId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void forEachVersions(String quarantineId, Consumer<List<VersionCopy>> batch) {
			throw new UnsupportedOperationException();
		}
	};

	@Test
	void theOriginalsGoOnlyOnceTheContentIsRewritten() {
		assertThat(quarantine.rewriteHeld(held, () -> steps.add("rewrite"))).containsExactly("still-there");
		assertThat(steps).containsExactly("rewrite", "delete");
	}

	@Test
	void aFailedRewriteUndoesTheHoldAndKeepsTheOriginals() {
		assertThatThrownBy(() -> quarantine.rewriteHeld(held, () -> {
			throw new IllegalStateException("store down");
		})).hasMessage("store down");
		assertThat(steps).containsExactly("discard");
	}
}
