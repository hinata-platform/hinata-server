package com.ahmadre.hinata.moderation.filter;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsAudit;
import com.ahmadre.hinata.setup.SettingsGuard;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The text check as a settings save changes it (HIN-176). Putting texts before moderation starts
 * only once the administrator confirmed that the works council was involved and the impact
 * assessment done (BetrVG § 87 (1) no. 6, DSGVO Art. 35); every change of what it does is
 * recorded, with the number of own terms but never the terms.
 */
@Component
@RequiredArgsConstructor
class FilterSettings implements SettingsGuard, SettingsAudit {

	private final AuditService audit;

	@Override
	public void check(ServerSettings before, ServerSettings after) {
		boolean switchedOn = filterOf(after).flags() && !filterOf(before).flags();
		if (switchedOn && !Boolean.TRUE.equals(after.getFlagConfirmed())) {
			throw ApiException.refusal(HttpStatus.CONFLICT, "error.moderation.flagUnconfirmed");
		}
	}

	@Override
	public void record(ServerSettings before, ServerSettings after, User actor) {
		ServerSettings.Filter was = filterOf(before);
		ServerSettings.Filter now = filterOf(after);
		if (was.blocks() == now.blocks() && was.flags() == now.flags()
				&& was.ownBlockTerms().equals(now.ownBlockTerms()) && was.ownAllowTerms().equals(now.ownAllowTerms())) {
			return;
		}
		audit.event(AuditAction.MODERATION_FILTER_CHANGED).actor(actor)
				.meta("block", String.valueOf(now.blocks()))
				.meta("flag", String.valueOf(now.flags()))
				.meta("blockTerms", String.valueOf(now.ownBlockTerms().size()))
				.meta("allowTerms", String.valueOf(now.ownAllowTerms().size()))
				.log();
	}

	private static ServerSettings.Filter filterOf(ServerSettings settings) {
		return settings.moderationOrDefault().filterOrDefault();
	}
}
