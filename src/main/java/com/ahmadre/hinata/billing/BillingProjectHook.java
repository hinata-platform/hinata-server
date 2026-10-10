package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.deletion.ProjectDeletionHook;
import com.ahmadre.hinata.project.Project;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * A project with an issued invoice or credit note is not deleted (HIN-96): those are booking
 * records (§ 147 AO, § 257 HGB), and a record that names a project nobody can find any more is no
 * longer a record anybody can read. Drafts and the project's own rates go with the project.
 *
 * <p>Asked whether billing is switched on or not — an invoice issued last year is no less a
 * record because billing was switched off since.
 */
@Component
@RequiredArgsConstructor
class BillingProjectHook implements ProjectDeletionHook {

	private final InvoiceRepository invoices;
	private final BillingRateRepository rates;

	@Override
	public void assertDeletable(Project project) {
		if (invoices.existsByProjectIdAndStatus(project.getId(), Invoice.Status.ISSUED)) {
			throw ApiException.conflict("error.billing.projectHasInvoices",
					Map.of("reason", "invoice", "holder", "accounting", "remedy", "keepProject"));
		}
	}

	@Override
	public void projectDeleted(String projectId) {
		invoices.deleteByProjectIdAndStatus(projectId, Invoice.Status.DRAFT);
		invoices.deleteByProjectIdAndStatus(projectId, Invoice.Status.ISSUING);
		rates.deleteByProjectId(projectId);
	}
}
