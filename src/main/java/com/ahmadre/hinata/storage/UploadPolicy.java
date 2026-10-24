package com.ahmadre.hinata.storage;

import java.util.Set;

/**
 * Which groups of file types the instance switched off (HIN-177). Implemented where the settings
 * live, so storage needs nothing from them; without an implementation every group is on.
 */
public interface UploadPolicy {

	Set<UploadGroup> disabledGroups();
}
