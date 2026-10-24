package com.ahmadre.hinata.auth;

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

import java.util.List;
import java.util.Map;

/**
 * The ways in that let new accounts in without approval (HIN-175), as a settings save changes
 * them: one opens only once the administrator confirmed what that makes of the instance, since
 * content its members share is then public, with the duties of an online platform; and every way
 * that opens or closes is recorded.
 */
@Component
@RequiredArgsConstructor
class OpenAccessSettings implements SettingsGuard, SettingsAudit {

	private final AccountAdmission admission;
	private final AuditService audit;

	@Override
	public void check(ServerSettings before, ServerSettings after) {
		List<String> opened = opened(before, after);
		if (!opened.isEmpty() && !Boolean.TRUE.equals(after.getOpenAccessConfirmed())) {
			throw ApiException.refusal(HttpStatus.CONFLICT, "error.moderation.openAccessUnconfirmed",
					Map.of("ways", String.join(",", opened)));
		}
	}

	@Override
	public void record(ServerSettings before, ServerSettings after, User actor) {
		for (String way : opened(before, after)) {
			audit.event(AuditAction.ACCOUNT_APPROVAL_CHANGED).actor(actor).meta("way", way).meta("open", "true").log();
		}
		for (String way : opened(after, before)) {
			audit.event(AuditAction.ACCOUNT_APPROVAL_CHANGED).actor(actor).meta("way", way).meta("open", "false").log();
		}
	}

	/** The ways open in [after] that were not in [before]. */
	private List<String> opened(ServerSettings before, ServerSettings after) {
		List<String> wasOpen = ways(before);
		return ways(after).stream().filter(way -> !wasOpen.contains(way)).toList();
	}

	private List<String> ways(ServerSettings settings) {
		return admission.openWays(settings).stream().map(AccountAdmission.OpenWay::way).toList();
	}
}
