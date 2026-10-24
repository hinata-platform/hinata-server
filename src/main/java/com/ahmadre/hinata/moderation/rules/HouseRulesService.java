package com.ahmadre.hinata.moderation.rules;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.common.Expiring;
import com.ahmadre.hinata.config.HinataProperties;
import com.ahmadre.hinata.setup.OperatorContact;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.ServerSettings.RulesMode;
import com.ahmadre.hinata.setup.SettingsService;
import com.ahmadre.hinata.user.HouseRules;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The versions of the house rules and who accepted which (HIN-175).
 *
 * <p>Whenever what the rules say changes, in any language, a new version is written: from a
 * settings save, from a server update that changes the built-in words, and the first time this
 * runs. The first version asks everybody to accept; a later one only when an administrator asks
 * for it. What the rules say includes the section on how moderation works, so a new contact or a
 * new retention period makes a version too, one that asks nobody.
 *
 * <p>Which version is current and which one people have to have accepted is cached for at most
 * {@link #MAX_AGE}, like the operator contact: a second instance learns of a new version that late.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseRulesService implements HouseRules {

	static final Duration MAX_AGE = Duration.ofSeconds(30);

	/** Where the house rules and the form for reports without an account answer, below the base URL. */
	public static final String PUBLIC_RULES_PATH = "/api/v1/public/rules";
	public static final String PUBLIC_REPORT_PATH = "/api/v1/public/report";

	private final MongoTemplate mongo;
	private final RulesRenderer renderer;
	private final SettingsService settings;
	private final OperatorContact operatorContact;
	private final HinataProperties properties;
	private final AuditService audit;
	private final CurrentUser currentUser;
	private final Clock clock;

	private final Expiring<Snapshot> versions = new Expiring<>(MAX_AGE, this::readVersions);

	private record Snapshot(Integer current, Integer required) {
	}

	/** What a person sees of the rules: the current version in their language, and what they accepted. */
	public record RulesView(int version, RulesMode mode, String url, String markdown, Instant createdAt,
			boolean pending, Integer acceptedVersion, Instant acceptedAt) {
	}

	@Override
	public Integer currentVersion() {
		return snapshot().current();
	}

	@Override
	public boolean pending(User user) {
		Integer required = snapshot().required();
		return required != null && (user.getRulesAcceptedVersion() == null || user.getRulesAcceptedVersion() < required);
	}

	/** The newest version everybody had to accept anew; null while there is none. */
	public Integer requiredVersion() {
		return snapshot().required();
	}

	private Snapshot snapshot() {
		return versions.get(clock.instant());
	}

	private Snapshot readVersions() {
		RulesVersion latest = latest();
		RulesVersion required = mongo.findOne(Query.query(Criteria.where("requiresReacceptance").is(true))
				.with(Sort.by(Sort.Direction.DESC, "version")).limit(1), RulesVersion.class);
		return new Snapshot(latest == null ? null : latest.getVersion(), required == null ? null : required.getVersion());
	}

	/** The titles of the built-in rules in [locale]'s language by number, as the published rules head them. */
	public Map<Integer, String> builtInRules(Locale locale) {
		return renderer.numberedRules(locale.getLanguage());
	}

	/** The current version; null before the first. */
	public RulesVersion latest() {
		return mongo.findOne(new Query().with(Sort.by(Sort.Direction.DESC, "version")).limit(1), RulesVersion.class);
	}

	/** Version [version]; null when there is no such version. */
	public RulesVersion version(int version) {
		return mongo.findOne(Query.query(Criteria.where("version").is(version)), RulesVersion.class);
	}

	@EventListener(ApplicationReadyEvent.class)
	void onStart() {
		try {
			ensureCurrent(settings.get(), null);
		}
		catch (RuntimeException ex) {
			// The rules must not keep the server from starting; the next settings save tries again.
			log.warn("Writing the current house rules failed: {}", ex.getMessage());
		}
	}

	@EventListener
	void onSettingsChanged(SettingsService.SettingsChangedEvent event) {
		ensureCurrent(event.settings(), savedBy());
	}

	/** Who saved the settings that changed, when a person did; null for the server itself. */
	private User savedBy() {
		try {
			return currentUser.require();
		}
		catch (ApiException noPerson) {
			return null;
		}
	}

	/** The version that says what [stored] says, written now when the current one says something else. */
	synchronized RulesVersion ensureCurrent(ServerSettings stored, User actor) {
		RulesRenderer.Inputs inputs = inputs(stored);
		Map<String, String> rendered = renderer.render(inputs);
		String fingerprint = fingerprint(rendered);
		RulesVersion latest = latest();
		if (latest != null && fingerprint.equals(latest.getFingerprint())) {
			return latest;
		}
		return write(inputs, rendered, fingerprint, latest == null, latest, actor);
	}

	/**
	 * A new version that asks everybody to accept again, saying what the current one says: for an
	 * administrator after a change that matters to everybody.
	 */
	public synchronized RulesVersion requireReacceptance(User admin) {
		RulesRenderer.Inputs inputs = inputs(settings.get());
		Map<String, String> rendered = renderer.render(inputs);
		return write(inputs, rendered, fingerprint(rendered), true, latest(), admin);
	}

	private RulesVersion write(RulesRenderer.Inputs inputs, Map<String, String> rendered, String fingerprint,
			boolean reacceptance, RulesVersion latest, User actor) {
		int next = latest == null ? 1 : latest.getVersion() + 1;
		RulesVersion version = RulesVersion.builder()
				.version(next)
				.mode(inputs.mode())
				.text(inputs.mode() == RulesMode.TEXT ? inputs.text() : null)
				.url(inputs.mode() == RulesMode.URL ? inputs.url() : null)
				.rendered(rendered)
				.fingerprint(fingerprint)
				.requiresReacceptance(reacceptance)
				.createdAt(clock.instant())
				.createdBy(actor == null ? null : actor.getId())
				.build();
		try {
			mongo.insert(version);
		}
		catch (DuplicateKeyException raced) {
			// Another instance wrote this version a moment ago; it says the same.
			versions.clear();
			return latest();
		}
		versions.clear();
		audit.event(AuditAction.MODERATION_RULES_VERSION_CREATED)
				.actor(actor)
				.meta("version", String.valueOf(next))
				.meta("reacceptance", String.valueOf(reacceptance))
				.log();
		return version;
	}

	/**
	 * Records that [user] accepted [version], which has to be the current one: a person agrees to
	 * what they were shown, and an older version is no longer what is shown.
	 */
	public void accept(User user, int version) {
		RulesVersion latest = latest();
		if (latest == null || latest.getVersion() != version) {
			throw ApiException.conflict("error.rules.notCurrent");
		}
		try {
			mongo.insert(RulesAcceptance.builder().userId(user.getId()).version(version)
					.acceptedAt(clock.instant()).build());
		}
		catch (DuplicateKeyException acceptedBefore) {
			// Pressed twice, or on two devices: the first moment counts.
		}
		mongo.updateFirst(Query.query(Criteria.where("_id").is(user.getId())),
				new Update().max("rulesAcceptedVersion", version), User.class);
	}

	/** The current rules for [user] in [language]. 404 while there are none. */
	public RulesView view(User user, String language) {
		RulesVersion latest = latest();
		if (latest == null) {
			throw ApiException.notFound("rules");
		}
		RulesAcceptance accepted = user.getRulesAcceptedVersion() == null ? null
				: mongo.findOne(Query.query(Criteria.where("userId").is(user.getId())
						.and("version").is(user.getRulesAcceptedVersion())), RulesAcceptance.class);
		return new RulesView(latest.getVersion(), latest.getMode(), latest.getUrl(), latest.renderedIn(language),
				latest.getCreatedAt(), pending(user), user.getRulesAcceptedVersion(),
				accepted == null ? null : accepted.getAcceptedAt());
	}

	/** Every acceptance of [userId], oldest first, for the data export. */
	public List<RulesAcceptance> acceptancesOf(String userId) {
		return mongo.find(Query.query(Criteria.where("userId").is(userId))
				.with(Sort.by(Sort.Direction.ASC, "version")), RulesAcceptance.class);
	}

	@Override
	public String publicRulesUrl() {
		return publicRulesUrl(null);
	}

	/** The public page of the rules, or of their version [version]. */
	public String publicRulesUrl(Integer version) {
		return base() + PUBLIC_RULES_PATH + (version == null ? "" : "?version=" + version);
	}

	@Override
	public String publicReportUrl() {
		return base() + PUBLIC_REPORT_PATH;
	}

	private String base() {
		String base = properties.getBaseUrl();
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	private RulesRenderer.Inputs inputs(ServerSettings stored) {
		ServerSettings.Moderation block = stored.getModeration();
		ServerSettings.Rules rules = block == null ? null : block.getRules();
		RulesMode mode = rules == null || rules.getMode() == null || !rules.isComplete() ? RulesMode.BUILT_IN
				: rules.getMode();
		OperatorContact.Contact contact = operatorContact.of(stored);
		String operator = contact.operatorName() != null ? contact.operatorName()
				: stored.getOrganizationName() != null && !stored.getOrganizationName().isBlank()
						? stored.getOrganizationName().strip() : "Hinata";
		return new RulesRenderer.Inputs(mode,
				mode == RulesMode.TEXT ? rules.getText() : null,
				mode == RulesMode.URL ? rules.getUrl().strip() : null,
				operator, contact.contactEmail(), contact.imprintUrl(),
				block == null ? 6 : block.retentionMonthsOrDefault(), publicReportUrl(), textCheck(block));
	}

	private static RulesRenderer.TextCheck textCheck(ServerSettings.Moderation block) {
		ServerSettings.Filter filter = block == null || block.getFilter() == null ? new ServerSettings.Filter()
				: block.getFilter();
		return new RulesRenderer.TextCheck(filter.blocks(), filter.flags(), filter.ownBlockTerms());
	}

	private static String fingerprint(Map<String, String> rendered) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (Map.Entry<String, String> language : rendered.entrySet()) {
				digest.update(language.getKey().getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
				digest.update(language.getValue().getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
