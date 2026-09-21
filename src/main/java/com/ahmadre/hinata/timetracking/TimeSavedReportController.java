package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/time/reports/saved} — saved reports, their share links and schedules (HIN-93).
 * See {@link TimeSavedReportService}.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/reports/saved")
@RequiredArgsConstructor
public class TimeSavedReportController {

	private final TimeSavedReportService saved;
	private final CurrentUser currentUser;

	@Operation(summary = "The caller's saved reports, most recently changed first")
	@GetMapping
	public Page<TimeSavedReportService.View> mine(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return saved.mine(currentUser.require(), page, size);
	}

	@PostMapping
	public ResponseEntity<TimeSavedReportService.View> create(@RequestBody TimeSavedReportService.Draft draft) {
		return ResponseEntity.status(HttpStatus.CREATED).body(saved.create(currentUser.require(), draft));
	}

	@PatchMapping("/{id}")
	public TimeSavedReportService.View update(@PathVariable String id,
			@RequestBody TimeSavedReportService.Draft draft) {
		return saved.update(currentUser.require(), id, draft);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id) {
		saved.delete(currentUser.require(), id);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "A new share link; the token is shown once")
	@PostMapping("/{id}/share")
	public TimeSavedReportService.Share share(@PathVariable String id) {
		return saved.share(currentUser.require(), id);
	}

	@Operation(summary = "Take the share link back")
	@DeleteMapping("/{id}/share")
	public ResponseEntity<Void> unshare(@PathVariable String id) {
		saved.unshare(currentUser.require(), id);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/{id}/schedule")
	public TimeSavedReportService.View schedule(@PathVariable String id,
			@RequestBody TimeSavedReportService.ScheduleDraft draft) {
		return saved.schedule(currentUser.require(), id, draft);
	}

	@DeleteMapping("/{id}/schedule")
	public TimeSavedReportService.View unschedule(@PathVariable String id) {
		return saved.unschedule(currentUser.require(), id);
	}

	@Operation(summary = "The report a share link points to, opened in the caller's own scope")
	@GetMapping("/shared/{token}")
	public TimeSavedReportService.View opened(@PathVariable String token) {
		return saved.opened(currentUser.require(), token);
	}
}
