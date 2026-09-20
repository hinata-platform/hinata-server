package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.availability.TimeOff;
import com.ahmadre.hinata.availability.TimeOffCatalogue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The one thing absence management tells the module below it: which of the three stored kinds an
 * operator's absence type is.
 *
 * <p>An absence entered under "parental leave" is stored as {@code OTHER} plus the type's id. The
 * three values are a wire and storage contract — the published app reads them, and a document
 * written in 2025 has nothing else — so the kind is derived here on the way in rather than looked
 * up on the way out. A client that learns a new type today keeps reading old absences correctly,
 * and one that never learns of the module keeps reading new ones.
 *
 * <p>Answers nothing while the module is off: there is no catalogue then, so there is no type to
 * point at, and an absence that named one would be pointing at a document nobody can read.
 */
@Component
@RequiredArgsConstructor
public class TimeOffCatalogueBridge implements TimeOffCatalogue {

	private final TimeOffTypeRepository types;
	private final TimeOffSettings settings;

	@Override
	public Optional<TimeOff.Type> kindOf(String typeId) {
		if (!settings.enabled() || typeId == null || typeId.isBlank()) {
			return Optional.empty();
		}
		return types.findById(typeId).filter(TimeOffType::isActive).map(TimeOffCatalogueBridge::storedKind);
	}

	@Override
	public Optional<TimeOff.Type> builtInKindOf(String typeId) {
		if (!settings.enabled() || typeId == null || typeId.isBlank()) {
			return Optional.empty();
		}
		return types.findById(typeId).filter(TimeOffType::isSystem).map(TimeOffCatalogueBridge::storedKind);
	}

	/**
	 * Vacation is vacation and sickness is sickness; everything else an operator invents is
	 * {@code OTHER}, which is what a client that has never heard of it will show.
	 */
	private static TimeOff.Type storedKind(TimeOffType type) {
		return switch (type.getKind()) {
			case VACATION -> TimeOff.Type.VACATION;
			case SICK -> TimeOff.Type.SICK;
			default -> TimeOff.Type.OTHER;
		};
	}
}
