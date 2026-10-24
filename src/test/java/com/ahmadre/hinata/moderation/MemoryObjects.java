package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.storage.StorageService;
import com.ahmadre.hinata.storage.TestStorage;
import com.ahmadre.hinata.storage.UploadGate;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The objects of the real {@link StorageService} the moderation tests run against, held in
 * memory: what removing content copies, deletes and checks for. Keys can be told to refuse a copy
 * or a delete, the two failures a removal has to survive (HIN-169).
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

	/** The store over these objects, behind [gate]. */
	StorageService storage(UploadGate gate) {
		return TestStorage.refusing(gate, objects, refuseCopies, refuseDeletes);
	}

	void clear() {
		objects.clear();
		refuseCopies.clear();
		refuseDeletes.clear();
	}
}
