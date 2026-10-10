package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code /api/v1/billing} — what the reader may do with money, and the rates (HIN-96). Behind
 * {@link BillingGate}: with billing off, none of this exists.
 */
@Tag(name = "Billing")
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class BillingRateController {

	private final BillingRateService rates;
	private final BillingAccess access;
	private final BillingSettings settings;
	private final CurrentUser currentUser;

	/**
	 * What the app offers this reader.
	 *
	 * @param costs        cost rates, costs and margins (administrators)
	 * @param ledProjects  the projects whose revenue the reader prices and bills; empty for an
	 *                     administrator, who reaches every project
	 */
	public record Access(String currency, boolean admin, boolean costs, boolean invoices, boolean reports,
			List<String> ledProjects) {
	}

	public record RateRequest(BillingRate.Kind kind, BillingRate.Scope scope, @Size(max = 64) String scopeId,
			@Size(max = 64) String secondaryId, Long amountCents, LocalDate validFrom, LocalDate validTo) {
	}

	public record RateChange(Long amountCents, LocalDate validFrom, LocalDate validTo, Boolean openEnded) {
	}

	@Operation(summary = "What the reader may do with billing")
	@GetMapping("/access")
	public Access access() {
		User viewer = currentUser.require();
		BillingAccess.Reach reach = access.of(viewer);
		boolean any = reach.admin() || !reach.ledProjects().isEmpty();
		return new Access(settings.currency(), reach.admin(), reach.costs(), any, any,
				List.copyOf(reach.ledProjects()));
	}

	@Operation(summary = "A page of rates, filtered")
	@GetMapping("/rates")
	public Page<BillingRateService.RateView> page(@RequestParam(required = false) BillingRate.Kind kind,
			@RequestParam(required = false) BillingRate.Scope scope,
			@RequestParam(required = false) @Size(max = 64) String scopeId,
			@RequestParam(required = false) @Size(max = 64) String secondaryId,
			@RequestParam(required = false) @Size(max = 64) String projectId,
			@RequestParam(required = false) BillingRateService.Status status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		return rates.page(currentUser.require(), kind, scope, scopeId, secondaryId, projectId, status, page, size);
	}

	@Operation(summary = "Every rate of one target, newest first")
	@GetMapping("/rates/timeline")
	public List<BillingRateService.RateView> timeline(@RequestParam BillingRate.Kind kind,
			@RequestParam BillingRate.Scope scope, @RequestParam(required = false) @Size(max = 64) String scopeId,
			@RequestParam(required = false) @Size(max = 64) String secondaryId) {
		return rates.timeline(currentUser.require(), kind, scope, blank(scopeId), blank(secondaryId));
	}

	@Operation(summary = "Create a rate; a new open-ended rate closes the running one the day before")
	@PostMapping("/rates")
	@ResponseStatus(HttpStatus.CREATED)
	public BillingRateService.RateView create(@Valid @RequestBody RateRequest body) {
		return rates.create(currentUser.require(), new BillingRateService.Draft(body.kind(), body.scope(),
				body.scopeId(), body.secondaryId(), body.amountCents(), body.validFrom(), body.validTo()));
	}

	@Operation(summary = "Change a rate's amount or span")
	@PatchMapping("/rates/{id}")
	public BillingRateService.RateView update(@PathVariable String id, @RequestBody RateChange body) {
		return rates.update(currentUser.require(), id,
				new BillingRateService.Change(body.amountCents(), body.validFrom(), body.validTo(), body.openEnded()));
	}

	@Operation(summary = "Remove a rate")
	@DeleteMapping("/rates/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		rates.delete(currentUser.require(), id);
	}

	private static String blank(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
