package com.ahmadre.hinata.setup;

import com.ahmadre.hinata.storage.UploadGroup;
import com.ahmadre.hinata.storage.UploadPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** The file types this instance switched off (HIN-177), as the admin area sets them. */
@Component
@RequiredArgsConstructor
class SettingsUploadPolicy implements UploadPolicy {

	private final SettingsService settings;

	@Override
	public Set<UploadGroup> disabledGroups() {
		ServerSettings.Moderation moderation = settings.current().getModeration();
		if (moderation == null || moderation.getUploads() == null
				|| moderation.getUploads().getDisabledGroups() == null) {
			return Set.of();
		}
		return moderation.getUploads().getDisabledGroups();
	}
}
