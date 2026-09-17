package com.ahmadre.hinata.timeoff;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.timetracking.TimeTrackingSettings;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Who may keep the catalogue, and whose balance somebody may read. Asked here so the rule is
 * written once, the way {@code availability.AvailabilityAccess} is for absences.
 *
 * <ul>
 * <li><b>Everybody</b> reads the catalogue of types. A picker needs it, and "this organisation
 * offers parental leave" is not anybody's personal data.</li>
 * <li><b>One's own</b> balance, ledger and entitlement: always.</li>
 * <li><b>A keeper</b> — an administrator, or somebody an operator named in
 * {@code absenceManagers} — reads and writes anybody's, and keeps the catalogue.</li>
 * <li><b>Everybody else</b>, leads included: nothing. A lead may see <em>that</em> a member is
 * away, through {@code AvailabilityAccess} and only with the policy on. How many vacation days
 * somebody has left is a different question, and it is not one project planning has to answer
 * (R2, R10).</li>
 * </ul>
 *
 * <p>The keeper list is deliberately not a role. This is where sick days are visible as sick days
 * (Art. 9 DSGVO), and "every administrator" is a wider circle than most operators want for that.
 * An administrator keeps the right anyway — they can add themselves in one save — so the list
 * narrows who looks by default without pretending to lock anybody out.
 */
@Component
@RequiredArgsConstructor
public class TimeOffAccess {

	private final TimeTrackingSettings settings;
	private final UserRepository users;

	/** Whether somebody keeps absences for everybody. */
	public boolean isKeeper(User viewer) {
		if (viewer.isAdmin()) {
			return true;
		}
		List<String> named = settings.absenceManagers();
		return !named.isEmpty() && named.contains(viewer.getId());
	}

	/** 403 for anybody who does not keep absences for everybody. */
	public void requireKeeper(User viewer) {
		if (!isKeeper(viewer)) {
			throw ApiException.forbidden("error.timeOff.forbidden");
		}
	}

	/**
	 * The person a read or a write is about: oneself when the request names nobody, anybody for a
	 * keeper. 404 for a keeper naming somebody who is gone, 403 for everybody else — a member
	 * probing ids learns nothing from the difference, because they never get past the check.
	 */
	public User requireSubject(User viewer, String userId) {
		if (userId == null || userId.isBlank() || userId.equals(viewer.getId())) {
			return viewer;
		}
		requireKeeper(viewer);
		return users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
	}
}
