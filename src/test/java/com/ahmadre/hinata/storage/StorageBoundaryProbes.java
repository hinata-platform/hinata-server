package com.ahmadre.hinata.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Classes written to break the storage boundary (HIN-177): bytes a person sent are stored through
 * the upload gate, never with {@code putObject}. Imported as bytecode by
 * {@code ModuleBoundaryTest}, never used.
 */
@SuppressWarnings("unused")
public final class StorageBoundaryProbes {

	private StorageBoundaryProbes() {
	}

	/** Stores what a person sent as it came, past the gate. */
	public static class PutsAPersonsBytes {
		void store(StorageService storage, MultipartFile file) throws IOException {
			storage.putObject("uploads/x", file.getBytes(), file.getContentType());
		}
	}

	/** Stores it through the gate, which the rule allows anywhere. */
	public static class StoresThroughTheGate {
		void store(StorageService storage, MultipartFile file) {
			storage.putUserObject("uploads/x", UploadCandidate.of(UploadKind.ATTACHMENT, file));
		}
	}
}
