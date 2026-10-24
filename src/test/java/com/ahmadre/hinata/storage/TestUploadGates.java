package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.config.HinataProperties;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real upload gates for tests that build their services by hand (HIN-177). */
public final class TestUploadGates {

	private TestUploadGates() {
	}

	/** The gate of an instance with the default limits and no type switched off. */
	public static UploadGate open() {
		return counting(new ArrayList<>());
	}

	/** As {@link #open()}, collecting every refusal the gate counts into [refused]. */
	public static UploadGate counting(List<UploadRefusedEvent> refused) {
		return gate(Set.of(), refused);
	}

	/** A gate of an instance that switched off [disabled]. */
	public static UploadGate gate(Set<UploadGroup> disabled, List<UploadRefusedEvent> refused) {
		@SuppressWarnings("unchecked")
		ObjectProvider<UploadPolicy> policy = mock(ObjectProvider.class);
		when(policy.getIfAvailable()).thenReturn(() -> disabled);
		return new UploadGate(new HinataProperties(), policy, event -> {
			if (event instanceof UploadRefusedEvent counted) {
				refused.add(counted);
			}
		});
	}
}
