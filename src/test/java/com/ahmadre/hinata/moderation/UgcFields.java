package com.ahmadre.hinata.moderation;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Every place a stored document can hold words (HIN-169): the paths of all fields of type
 * {@code String}, {@code Collection<String>} or {@code Map}, through embedded classes four deep,
 * of every {@code @Document} under {@code com.ahmadre.hinata}.
 */
final class UgcFields {

	private static final String ROOT = "com.ahmadre.hinata";
	private static final int DEPTH = 4;

	private UgcFields() {
	}

	/** Every {@code @Document} class of the application. */
	static List<Class<?>> documents() {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));
		List<Class<?>> found = new ArrayList<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents(ROOT)) {
			Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(), UgcFields.class.getClassLoader());
			// The probes of the boundary tests and of this one live in the test sources.
			if (!type.getName().contains("Probe")) {
				found.add(type);
			}
		}
		return found;
	}

	/** The paths of the word-holding fields of [documents], as {@code collection.field.sub}. */
	static TreeSet<String> pathsOf(Collection<Class<?>> documents) {
		TreeSet<String> paths = new TreeSet<>();
		for (Class<?> document : documents) {
			walk(collectionOf(document), document, 0, paths);
		}
		return paths;
	}

	static String collectionOf(Class<?> document) {
		Document annotation = document.getAnnotation(Document.class);
		String name = annotation.collection().isEmpty() ? annotation.value() : annotation.collection();
		return name.isEmpty() ? StringUtils.uncapitalize(document.getSimpleName()) : name;
	}

	private static void walk(String prefix, Class<?> type, int depth, TreeSet<String> paths) {
		for (Class<?> level = type; level != null && level != Object.class; level = level.getSuperclass()) {
			for (Field field : level.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()
						|| field.isAnnotationPresent(Transient.class)
						|| Modifier.isTransient(field.getModifiers())) {
					continue;
				}
				String path = prefix + "." + field.getName();
				Class<?> raw = field.getType();
				if (raw == String.class || Map.class.isAssignableFrom(raw)) {
					paths.add(path);
				}
				else if (Collection.class.isAssignableFrom(raw)) {
					Class<?> element = elementOf(field.getGenericType());
					if (element == String.class) {
						paths.add(path);
					}
					else if (embedded(element) && depth < DEPTH) {
						walk(path, element, depth + 1, paths);
					}
				}
				else if (embedded(raw) && depth < DEPTH) {
					walk(path, raw, depth + 1, paths);
				}
			}
		}
	}

	private static Class<?> elementOf(Type generic) {
		if (generic instanceof ParameterizedType parameterized
				&& parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
			return element;
		}
		return Object.class;
	}

	/** A class of the application stored inside another document: not an enum, not a document of its own. */
	private static boolean embedded(Class<?> type) {
		return type != null && type.getName().startsWith(ROOT) && !type.isEnum() && !type.isInterface()
				&& !type.isAnnotationPresent(Document.class);
	}
}
