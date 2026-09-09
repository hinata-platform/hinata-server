package com.ahmadre.hinata.audit;

import com.ahmadre.hinata.config.ClientIpResolver;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.User;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central entry point for recording {@link AuditLog} events. Every call is gated
 * by the runtime audit settings (the master switch plus the per-event toggle),
 * so instrumenting a call site is always safe: when auditing is off, or that
 * specific event is disabled, {@link Entry#log()} is a no-op.
 *
 * <p>Recording must never break the action being audited — all persistence is
 * wrapped so an audit failure is logged and swallowed.
 *
 * <p>Usage from a service:
 * <pre>{@code
 * audit.event(AuditAction.LOGIN_SUCCESS).actor(user).log();
 * audit.event(AuditAction.USER_ROLE_CHANGED)
 *      .actor(currentUser).target(other)
 *      .meta("role", "ADMIN").log();
 * }</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

	private final AuditLogRepository repository;
	private final SettingsService settings;
	private final ClientIpResolver clientIpResolver;

	/**
	 * The audit block as last read, so asking whether an event is captured is a
	 * volatile read rather than a query.
	 *
	 * <p>It used to be a {@code findById} on {@code server_settings} per call —
	 * acceptable while the call sites were logins and administrative acts, and
	 * not acceptable now that the time module asks the question on the path of
	 * every entry written, twice (once to decide whether to gather the metadata,
	 * once inside {@link Entry#log()}). Same shape as {@code SecurityPolicy} and
	 * {@code TimeTrackingSettings}, which cache the same document the same way
	 * and for the same reason.
	 *
	 * <p>The event is in-process, so a second application instance keeps its own
	 * copy until it sees its own save — the trade those two already make. What is
	 * at stake here is a toggle an operator changes rarely, not an authorisation.
	 */
	private volatile ServerSettings.Audit cached;

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		cached = orDefault(event.settings().getAudit());
	}

	/** Whether the given action is currently captured (master switch + toggle). */
	public boolean isEnabled(AuditAction action) {
		ServerSettings.Audit cfg = cached;
		if (cfg == null) {
			cfg = orDefault(settings.get().getAudit());
			cached = cfg;
		}
		if (!cfg.isEnabled()) {
			return false;
		}
		Boolean override = cfg.getEvents() == null ? null : cfg.getEvents().get(action.name());
		return override != null ? override : action.defaultEnabled();
	}

	/**
	 * An instance with no stored audit block records everything an action's
	 * default says — which is what {@code ServerSettings.Audit}'s own field
	 * defaults mean. Never null once loaded, so the absence costs one read rather
	 * than one per call.
	 */
	private static ServerSettings.Audit orDefault(ServerSettings.Audit stored) {
		return stored != null ? stored : new ServerSettings.Audit();
	}

	/** Opens a fluent builder for {@code action}. Nothing is written until {@link Entry#log()}. */
	public Entry event(AuditAction action) {
		return new Entry(action);
	}

	/** Fluent builder accumulating one {@link AuditLog} before it is persisted. */
	public final class Entry {

		private final AuditAction action;
		private AuditLog.Outcome outcome;
		private String actorId;
		private String actorLabel;
		private String targetId;
		private String targetLabel;
		private String ip;
		private String userAgent;
		private final Map<String, String> metadata = new LinkedHashMap<>();

		private Entry(AuditAction action) {
			this.action = action;
			// Authentication failures are failures by nature; everything else
			// defaults to SUCCESS and can be overridden via outcome().
			this.outcome = switch (action) {
				case LOGIN_FAILURE, LOGIN_BLOCKED, MFA_FAILURE -> AuditLog.Outcome.FAILURE;
				default -> AuditLog.Outcome.SUCCESS;
			};
		}

		public Entry actor(User user) {
			if (user != null) {
				this.actorId = user.getId();
				this.actorLabel = user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
			}
			return this;
		}

		public Entry actor(String id, String label) {
			this.actorId = id;
			this.actorLabel = label;
			return this;
		}

		public Entry target(User user) {
			if (user != null) {
				this.targetId = user.getId();
				this.targetLabel = user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
			}
			return this;
		}

		public Entry target(String id, String label) {
			this.targetId = id;
			this.targetLabel = label;
			return this;
		}

		public Entry outcome(AuditLog.Outcome outcome) {
			this.outcome = outcome;
			return this;
		}

		/** Override the request-derived IP (e.g. login already resolved one). */
		public Entry ip(String ip) {
			this.ip = mask(ip);
			return this;
		}

		public Entry userAgent(String userAgent) {
			this.userAgent = userAgent;
			return this;
		}

		public Entry meta(String key, String value) {
			if (key != null && value != null) {
				this.metadata.put(key, value);
			}
			return this;
		}

		/** Persists the event when its action is enabled; otherwise a no-op. */
		public void log() {
			try {
				if (!isEnabled(action)) {
					return;
				}
				resolveRequestContext();
				AuditLog entry = AuditLog.builder()
						.action(action)
						.category(action.category())
						.severity(action.defaultSeverity())
						.outcome(outcome)
						.actorId(actorId)
						.actorLabel(actorLabel)
						.targetId(targetId)
						.targetLabel(targetLabel)
						.ip(ip)
						.userAgent(userAgent)
						.metadata(metadata.isEmpty() ? null : metadata)
						.build();
				repository.save(entry);
			}
			catch (Exception ex) {
				// Auditing must never break the audited action.
				log.warn("Failed to record audit event {}: {}", action, ex.getMessage());
			}
		}

		/** Fills IP / User-Agent from the active HTTP request when not set explicitly. */
		private void resolveRequestContext() {
			HttpServletRequest request = currentRequest();
			if (request == null) {
				return;
			}
			if (ip == null) {
				ip = mask(clientIpResolver.resolve(request));
			}
			if (userAgent == null) {
				userAgent = request.getHeader("User-Agent");
			}
		}
	}

	private static HttpServletRequest currentRequest() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
			return attrs.getRequest();
		}
		return null;
	}

	/** Masks the last two octets of an IPv4 address (best-effort for IPv6). */
	static String mask(String ip) {
		if (ip == null || ip.isBlank()) {
			return null;
		}
		String[] parts = ip.split("\\.");
		if (parts.length == 4) {
			return parts[0] + "." + parts[1] + ".xx.xx";
		}
		int cut = ip.lastIndexOf(':');
		return cut > 0 ? ip.substring(0, cut) + ":xx" : ip;
	}
}
