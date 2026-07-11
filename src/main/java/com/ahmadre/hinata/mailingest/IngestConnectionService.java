package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.setup.ServerSettings;
import com.ahmadre.hinata.setup.SettingsService;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * CRUD + IMAP folder discovery for the managed e-mail-to-ticket connections.
 * Replaces the former single-mailbox {@link ServerSettings.EmailIngest} config;
 * a legacy config is migrated into a connection document on boot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestConnectionService {

	private final IngestConnectionRepository connections;
	private final SettingsService settings;

	public List<IngestConnection> list() {
		List<IngestConnection> all = new ArrayList<>(connections.findAll());
		all.sort(Comparator.comparing(IngestConnection::getCreatedAt,
				Comparator.nullsFirst(Comparator.naturalOrder())));
		all.forEach(this::deriveFlags);
		return all;
	}

	public IngestConnection get(String id) {
		return connections.findById(id).orElseThrow(() -> ApiException.notFound("ingestConnection"));
	}

	public IngestConnection create(IngestConnection connection) {
		connection.setId(null);
		validate(connection);
		return deriveFlags(connections.save(connection));
	}

	/** Full update; a blank password keeps the stored one (never echoed). */
	public IngestConnection update(String id, IngestConnection updated) {
		IngestConnection current = get(id);
		updated.setId(current.getId());
		updated.setCreatedAt(current.getCreatedAt());
		if (isBlank(updated.getPassword())) {
			updated.setPassword(current.getPassword());
		}
		validate(updated);
		return deriveFlags(connections.save(updated));
	}

	public void delete(String id) {
		connections.deleteById(id);
	}

	private void validate(IngestConnection connection) {
		if (isBlank(connection.getHost()) || isBlank(connection.getUsername())) {
			throw ApiException.badRequest("error.ingest.fieldsRequired");
		}
		if (isBlank(connection.getFolder())) {
			connection.setFolder("INBOX");
		}
		if (connection.getPort() <= 0) {
			connection.setPort(connection.isSsl() ? 993 : 143);
		}
		if (connection.getPollSeconds() < 15) {
			connection.setPollSeconds(15);
		}
	}

	private IngestConnection deriveFlags(IngestConnection connection) {
		connection.setPasswordSet(!isBlank(connection.getPassword()));
		return connection;
	}

	/**
	 * Connects to the mailbox and lists every folder that can hold messages
	 * (full names, e.g. "INBOX/Support"). Only invoked on the admin's explicit
	 * request — never automatically. A blank password with a known connection id
	 * falls back to the stored one so a saved connection can be re-scanned.
	 */
	public List<String> listFolders(String connectionId, String host, int port, boolean ssl,
			String username, String password) {
		if (isBlank(password) && !isBlank(connectionId)) {
			password = connections.findById(connectionId)
					.map(IngestConnection::getPassword)
					.orElse(null);
		}
		if (isBlank(host) || isBlank(username) || isBlank(password)) {
			throw ApiException.badRequest("error.ingest.credentialsRequired");
		}
		if (port <= 0) {
			port = ssl ? 993 : 143;
		}
		Properties props = new Properties();
		String protocol = ssl ? "imaps" : "imap";
		props.put("mail.store.protocol", protocol);
		props.put("mail." + protocol + ".host", host);
		props.put("mail." + protocol + ".port", String.valueOf(port));
		props.put("mail." + protocol + ".connectiontimeout", "10000");
		props.put("mail." + protocol + ".timeout", "15000");
		Session session = Session.getInstance(props);
		try (Store store = session.getStore(protocol)) {
			store.connect(host, port, username, password);
			List<String> names = new ArrayList<>();
			for (Folder folder : store.getDefaultFolder().list("*")) {
				if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
					names.add(folder.getFullName());
				}
			}
			names.sort(String.CASE_INSENSITIVE_ORDER);
			return names;
		}
		catch (Exception ex) {
			log.info("IMAP folder scan for {}@{} failed: {}", username, host, ex.getMessage());
			throw ApiException.badRequest("error.ingest.connectionFailed", ex.getMessage());
		}
	}

	/**
	 * One-time boot migration: the pre-management single-mailbox config (stored
	 * in the settings singleton) becomes the first managed connection. Runs only
	 * while no connection documents exist, so it can never duplicate.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void migrateLegacyConfig() {
		if (connections.count() > 0) {
			return;
		}
		ServerSettings.EmailIngest legacy = settings.get().getEmailIngest();
		if (legacy == null || isBlank(legacy.getHost())) {
			return;
		}
		IngestConnection migrated = IngestConnection.builder()
				.enabled(legacy.isEnabled())
				.host(legacy.getHost())
				.port(legacy.getPort())
				.ssl(legacy.isSsl())
				.username(legacy.getUsername())
				.password(legacy.getPassword())
				.folder(isBlank(legacy.getFolder()) ? "INBOX" : legacy.getFolder())
				.projectId(legacy.getDefaultProjectId())
				.pollSeconds(Math.max(15, legacy.getPollSeconds()))
				.build();
		connections.save(migrated);
		log.info("Migrated legacy e-mail ingest config ({}@{}) to a managed connection",
				legacy.getUsername(), legacy.getHost());
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
