package com.ahmadre.hinata.project;

/**
 * A project is being deleted. Published by {@link ProjectService#announceDeleted} from the deletion
 * cascade just before the project document goes, so a module that keeps records keyed by
 * {@code projectId} can drop them without the cascade naming the module.
 *
 * <p>A listener that fails must catch its own failure; the deletion goes on.
 */
public record ProjectDeleted(String projectId) {
}
