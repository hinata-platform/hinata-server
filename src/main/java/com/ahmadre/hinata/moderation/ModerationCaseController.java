package com.ahmadre.hinata.moderation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The moderation page's routes (HIN-170). Each one asks {@link CaseAccess}: 403 for whoever does
 * not moderate, 404 for whoever wrote the case's content, before anything else.
 */
@Tag(name = "Moderation")
@RestController
@RequestMapping("/api/v1/moderation")
@RequiredArgsConstructor
public class ModerationCaseController {

	private final CaseService cases;
	private final DecisionService decisions;
	private final AppealService appeals;
	private final SuspendedAccounts suspended;

	/** A step on a case at the revision the moderator saw. */
	public record ClaimRequest(@NotNull Long rev, boolean takeOver) {
	}

	@Operation(summary = "A page of cases: open, appealed or done")
	@GetMapping("/cases")
	public Page<CaseViews.CaseSummary> list(@RequestParam(defaultValue = "OPEN") CaseViews.View view,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		return cases.list(view, page, size);
	}

	@Operation(summary = "The numbers on the moderation page")
	@GetMapping("/summary")
	public CaseViews.Summary summary() {
		return cases.summary();
	}

	@Operation(summary = "One case")
	@GetMapping("/cases/{id}")
	public CaseViews.CaseDetail detail(@PathVariable String id) {
		return cases.detail(id);
	}

	@Operation(summary = "More reports of a case")
	@GetMapping("/cases/{id}/reports")
	public Page<CaseViews.ReportView> reports(@PathVariable String id, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return cases.reports(id, page, size);
	}

	@Operation(summary = "Take a case")
	@PostMapping("/cases/{id}/claim")
	public CaseViews.CaseSummary claim(@PathVariable String id, @Valid @RequestBody ClaimRequest request) {
		return cases.claim(id, request.rev(), request.takeOver());
	}

	@Operation(summary = "Give a case back")
	@PostMapping("/cases/{id}/release")
	public CaseViews.CaseSummary release(@PathVariable String id, @Valid @RequestBody ClaimRequest request) {
		return cases.release(id, request.rev());
	}

	@Operation(summary = "Uncover who filed a report, with a reason")
	@PostMapping("/cases/{id}/reports/{reportId}/reveal")
	public CaseViews.ReportView revealReporter(@PathVariable String id, @PathVariable String reportId,
			@Valid @RequestBody CaseService.RevealRequest request) {
		return cases.revealReporter(id, reportId, request.reason());
	}

	@Operation(summary = "Open what a report without an account names, with a second person")
	@PostMapping("/cases/{id}/evidence/reveal")
	public CaseViews.CaseDetail revealEvidence(@PathVariable String id,
			@Valid @RequestBody CaseService.RevealRequest request) {
		return cases.revealEvidence(id, request.reason());
	}

	/** Never cached, never sniffed, never a download: a file is looked at, and the look is recorded. */
	@Operation(summary = "One file of a case's evidence")
	@GetMapping("/cases/{id}/evidence/{index}")
	public ResponseEntity<byte[]> evidence(@PathVariable String id, @PathVariable int index) {
		CaseService.EvidenceFile file = cases.evidenceFile(id, index);
		ContentDisposition inline = file.fileName() == null ? ContentDisposition.inline().build()
				: ContentDisposition.inline().filename(file.fileName(), StandardCharsets.UTF_8).build();
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header("X-Content-Type-Options", "nosniff")
				.header(HttpHeaders.CONTENT_DISPOSITION, inline.toString())
				.contentType(file.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM
						: MediaType.parseMediaType(file.contentType()))
				.body(file.data());
	}

	@Operation(summary = "Decide a case")
	@PostMapping("/cases/{id}/decision")
	public CaseViews.CaseSummary decide(@PathVariable String id, @Valid @RequestBody DecisionService.DecisionRequest request) {
		return decisions.decide(id, request);
	}

	@Operation(summary = "Confirm a decision that suspends a person with a role, as the second person")
	@PostMapping("/cases/{id}/decision/confirm")
	public CaseViews.CaseSummary confirm(@PathVariable String id, @Valid @RequestBody DecisionService.StepRequest request) {
		return decisions.confirm(id, request);
	}

	@Operation(summary = "Turn down a decision waiting for a second person; the case is open again")
	@PostMapping("/cases/{id}/decision/reject")
	public CaseViews.CaseSummary reject(@PathVariable String id, @Valid @RequestBody DecisionService.NoteRequest request) {
		return decisions.reject(id, request);
	}

	@Operation(summary = "Lift the suspension a case decided")
	@PostMapping("/cases/{id}/suspension/lift")
	public CaseViews.CaseSummary liftSuspension(@PathVariable String id,
			@Valid @RequestBody DecisionService.NoteRequest request) {
		return decisions.liftSuspension(id, request);
	}

	@Operation(summary = "The accounts moderation suspended, newest first")
	@GetMapping("/suspensions")
	public Page<SuspendedAccounts.SuspendedAccount> suspensions(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return suspended.page(page, size);
	}

	@Operation(summary = "The statement a decision would send, without deciding")
	@PostMapping("/cases/{id}/statement-preview")
	public Statement preview(@PathVariable String id, @Valid @RequestBody DecisionService.DecisionRequest request) {
		return decisions.preview(id, request);
	}

	@Operation(summary = "Undo the removal of a decided case without an appeal")
	@PostMapping("/cases/{id}/restore")
	public CaseViews.CaseSummary restore(@PathVariable String id, @Valid @RequestBody DecisionService.RestoreRequest request) {
		return decisions.restore(id, request);
	}

	@Operation(summary = "Enter an appeal that came by a reply to the mail")
	@PostMapping("/cases/{id}/appeal")
	public CaseViews.CaseSummary recordAppeal(@PathVariable String id,
			@Valid @RequestBody AppealService.RecordedAppealRequest request) {
		return appeals.record(id, request);
	}

	@Operation(summary = "Decide an appeal")
	@PostMapping("/cases/{id}/appeal-decision")
	public CaseViews.CaseSummary decideAppeal(@PathVariable String id,
			@Valid @RequestBody AppealService.AppealDecisionRequest request) {
		return appeals.decide(id, request);
	}

	@Operation(summary = "Hold a decided case beyond its time, with a reason")
	@PostMapping("/cases/{id}/legal-hold")
	public CaseViews.CaseSummary legalHold(@PathVariable String id,
			@Valid @RequestBody DecisionService.LegalHoldRequest request) {
		return decisions.legalHold(id, request);
	}

	@Operation(summary = "The house rules a decision can rest on")
	@GetMapping("/rules")
	public ModerationRules.RuleSet rules() {
		return decisions.rules();
	}

	@Operation(summary = "Open a case on moderation's own initiative")
	@PostMapping("/cases")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, String> open(@Valid @RequestBody CaseService.OwnCaseRequest request) {
		return Map.of("id", cases.openOwn(request));
	}
}
