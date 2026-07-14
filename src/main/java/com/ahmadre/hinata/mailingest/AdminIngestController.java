package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditService;
import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Admin area: manage the e-mail-to-ticket (IMAP) connections. Multiple
 * connections may point different mailboxes/folders at different projects.
 * Secured by the /api/v1/admin/** ADMIN rule in SecurityConfig.
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/ingest-connections")
@RequiredArgsConstructor
public class AdminIngestController {

	private final IngestConnectionService service;
	private final EmailIngestService emailIngest;
	private final ProjectRepository projects;
	private final AuditService audit;
	private final CurrentUser currentUser;

	@GetMapping
	public List<IngestConnection> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public IngestConnection create(@RequestBody IngestConnection connection) {
		IngestConnection created = service.create(connection);
		auditChange("created", created);
		return created;
	}

	@PutMapping("/{id}")
	public IngestConnection update(@PathVariable String id, @RequestBody IngestConnection connection) {
		IngestConnection updated = service.update(id, connection);
		auditChange("updated", updated);
		return updated;
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		IngestConnection connection = service.get(id);
		service.delete(id);
		auditChange("deleted", connection);
	}

	/**
	 * Re-reads this connection's mailbox now and rebuilds the description of every
	 * ticket whose source e-mail is still present, using the current body parser.
	 * Seen flags are untouched and manual edits are preserved. With
	 * {@code createMissing=true} the admin also opts into (re-)creating tickets for
	 * e-mails that currently have none — otherwise missing tickets stay missing, so
	 * intentionally deleted ones never silently reappear.
	 */
	@PostMapping("/{id}/reprocess")
	public EmailIngestService.ReprocessResult reprocess(@PathVariable String id,
			@RequestParam(defaultValue = "false") boolean createMissing) {
		IngestConnection connection = service.get(id);
		EmailIngestService.ReprocessResult result = emailIngest.reprocess(connection, createMissing);
		auditChange(createMissing ? "reprocessed-full" : "reprocessed", connection);
		return result;
	}

	public record ProbeFoldersRequest(String connectionId, String host, Integer port, Boolean ssl,
			String username, String password) {
	}

	public record ProbeFoldersResponse(List<String> folders) {
	}

	/**
	 * Lists the mailbox's folders. Only called after the admin explicitly
	 * consented to a live connection to the mail server — the UI never probes
	 * automatically.
	 */
	@PostMapping("/probe-folders")
	public ProbeFoldersResponse probeFolders(@RequestBody ProbeFoldersRequest request) {
		return new ProbeFoldersResponse(service.listFolders(
				request.connectionId(),
				request.host(),
				request.port() != null ? request.port() : 0,
				request.ssl() == null || request.ssl(),
				request.username(),
				request.password()));
	}

	public record ProjectOption(String id, String key, String name, String color) {
	}

	public record ProjectPage(List<ProjectOption> items, long total) {
	}

	/** Server-filtered, paginated project options for the connection editor. */
	@GetMapping("/projects")
	public ProjectPage projectOptions(
			@RequestParam(defaultValue = "") String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		String needle = q.trim().toLowerCase(Locale.ROOT);
		List<Project> matches = projects.findByArchivedFalse().stream()
				.filter(p -> needle.isEmpty()
						|| p.getName().toLowerCase(Locale.ROOT).contains(needle)
						|| p.getKey().toLowerCase(Locale.ROOT).contains(needle))
				.sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
				.toList();
		int safeSize = Math.max(1, Math.min(size, 100));
		int from = Math.max(0, page) * safeSize;
		List<ProjectOption> items = matches.stream()
				.skip(from)
				.limit(safeSize)
				.map(p -> new ProjectOption(p.getId(), p.getKey(), p.getName(), p.getColor()))
				.toList();
		return new ProjectPage(items, matches.size());
	}

	private void auditChange(String operation, IngestConnection connection) {
		audit.event(AuditAction.SETTINGS_CHANGED)
				.actor(currentUser.require())
				.meta("ingestConnection", operation)
				.meta("host", String.valueOf(connection.getHost()))
				.meta("username", String.valueOf(connection.getUsername()))
				.log();
	}
}
