package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.storage.StorageService;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * An object store in memory behind a mocked {@link StorageService}: what removing content copies,
 * deletes and checks for. Keys can be told to refuse a copy or a delete, the two failures a removal
 * has to survive (HIN-169).
 */
final class MemoryObjects {

	final Map<String, byte[]> objects = new ConcurrentHashMap<>();
	final Set<String> refuseCopies = ConcurrentHashMap.newKeySet();
	final Set<String> refuseDeletes = ConcurrentHashMap.newKeySet();

	void put(String key, String content) {
		objects.put(key, content.getBytes());
	}

	boolean has(String key) {
		return objects.containsKey(key);
	}

	void installOn(StorageService storage) {
		objects.clear();
		refuseCopies.clear();
		refuseDeletes.clear();
		when(storage.isConfigured()).thenReturn(true);
		when(storage.exists(anyString())).thenAnswer(call -> objects.containsKey(call.<String>getArgument(0)));
		when(storage.copyObject(anyString(), anyString())).thenAnswer(call -> {
			String from = call.getArgument(0);
			if (refuseCopies.contains(from) || !objects.containsKey(from)) {
				return false;
			}
			objects.put(call.getArgument(1), objects.get(from));
			return true;
		});
		when(storage.deleteVerified(anyString())).thenAnswer(call -> {
			String key = call.getArgument(0);
			if (refuseDeletes.contains(key)) {
				return false;
			}
			objects.remove(key);
			return true;
		});
		doAnswer(call -> objects.remove(call.<String>getArgument(0))).when(storage).delete(anyString());
		when(storage.getObject(anyString())).thenAnswer(call -> Optional.ofNullable(objects.get(call.<String>getArgument(0)))
				.map(bytes -> new StorageService.StoredObject(bytes, "application/octet-stream")));
		doAnswer(call -> objects.put(call.getArgument(0), call.getArgument(1))).when(storage)
				.putObject(anyString(), any(byte[].class), anyString());
		when(storage.list(anyString())).thenAnswer(call -> objects.keySet().stream()
				.filter(key -> key.startsWith(call.<String>getArgument(0)))
				.map(key -> new StorageService.ObjectInfo(key, java.time.Instant.now())).toList());
	}
}
