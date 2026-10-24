package com.ahmadre.hinata.storage;

import com.ahmadre.hinata.storage.StorageService.ObjectInfo;
import com.ahmadre.hinata.storage.StorageService.StoredObject;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
