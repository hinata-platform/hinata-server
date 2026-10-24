package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.storage.StorageService.ObjectInfo;
import com.ahmadre.hinata.storage.StorageService.StoredObject;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A configured object store that keeps everything in memory, for tests that look at what was stored. */
public final class TestStorage {

	private TestStorage() {
	}

	/** A store behind [gate], and the objects it holds by key. */
	public static StorageService inMemory(UploadGate gate, Map<String, StoredObject> objects) {
		return new StorageService(gate, new InMemoryBackend(objects));
	}

	public static StorageService inMemory(UploadGate gate) {
		return inMemory(gate, new ConcurrentHashMap<>());
	}

	/**
	 * A store behind [gate] whose [objects] a test reads and writes as bytes, and which refuses to
	 * copy a key in [refuseCopies] or to delete one in [refuseDeletes]: the two failures a removal
	 * has to survive (HIN-169). Everything else is the real {@link StorageService}.
	 */
	public static StorageService refusing(UploadGate gate, Map<String, byte[]> objects, Set<String> refuseCopies,
			Set<String> refuseDeletes) {
		return new StorageService(gate, new RefusingBackend(objects, refuseCopies, refuseDeletes));
	}

	private record RefusingBackend(Map<String, byte[]> objects, Set<String> refuseCopies, Set<String> refuseDeletes)
			implements StorageBackend {

		@Override
		public void put(String objectKey, InputStream stream, long length, String contentType) throws Exception {
			objects.put(objectKey, stream.readAllBytes());
		}

		@Override
		public Optional<StoredObject> get(String objectKey) {
			return Optional.ofNullable(objects.get(objectKey))
					.map(bytes -> new StoredObject(bytes, "application/octet-stream"));
		}

		@Override
		public void copy(String fromKey, String toKey) {
			byte[] from = objects.get(fromKey);
			if (from == null || refuseCopies.contains(fromKey)) {
				throw new IllegalStateException("the store will not copy " + fromKey);
			}
			objects.put(toKey, from);
		}

		@Override
		public void delete(String objectKey) {
			if (refuseDeletes.contains(objectKey)) {
				throw new IllegalStateException("the store will not delete " + objectKey);
			}
			objects.remove(objectKey);
		}

		@Override
		public boolean exists(String objectKey) {
			return objects.containsKey(objectKey);
		}

		@Override
		public List<ObjectInfo> list(String keyPrefix) {
			return objects.keySet().stream().filter(key -> key.startsWith(keyPrefix))
					.map(key -> new ObjectInfo(key, Instant.now())).toList();
		}

		@Override
		public String presignedDownloadUrl(String objectKey, String fileName) {
			return "memory://" + objectKey;
		}
	}

	private record InMemoryBackend(Map<String, StoredObject> objects) implements StorageBackend {

		@Override
		public void put(String objectKey, InputStream stream, long length, String contentType) throws Exception {
			byte[] data = stream.readAllBytes();
			if (data.length != length) {
				throw new IllegalStateException("declared " + length + " bytes, got " + data.length);
			}
			objects.put(objectKey, new StoredObject(data, contentType));
		}

		@Override
		public Optional<StoredObject> get(String objectKey) {
			return Optional.ofNullable(objects.get(objectKey));
		}

		@Override
		public void copy(String fromKey, String toKey) {
			objects.put(toKey, objects.get(fromKey));
		}

		@Override
		public void delete(String objectKey) {
			objects.remove(objectKey);
		}

		@Override
		public boolean exists(String objectKey) {
			return objects.containsKey(objectKey);
		}

		@Override
		public List<ObjectInfo> list(String keyPrefix) {
			return objects.keySet().stream().filter(key -> key.startsWith(keyPrefix))
					.map(key -> new ObjectInfo(key, Instant.EPOCH)).toList();
		}

		@Override
		public String presignedDownloadUrl(String objectKey, String fileName) {
			return "memory://" + objectKey;
		}
	}
}
