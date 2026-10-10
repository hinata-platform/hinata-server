package com.ahmadre.hinata.billing;

import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.user.User;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Who may do what with money (HIN-96).
 *
 * <p>Two roles and nobody else. An organisation administrator sees and sets everything, costs
 * included. A project lead sees and sets revenue for the projects they lead — the project's rate,
 * its members' rates, its issues' rates — writes invoices for those projects, and never learns what
 * anybody costs (R7). A member who leads nothing has no billing at all: 403 on every route.
 *
 * <p>Leading a project is something anybody can make themselves (they create one). That is fine
 * here: what a lead reaches is the revenue of their own project, which is theirs to price.
 */
@Component
@RequiredArgsConstructor
public class BillingAccess {

	/** Most led projects one reader's lists narrow to; beyond it an $in stops being cheap. */
	static final int MAX_LED_PROJECTS = 500;

	private final MongoTemplate mongo;

	/** What one reader may do, worked out once per request. */
	public record Reach(User viewer, boolean admin, Set<String> ledProjects) {

		/** Whether the reader may see costs and margins. */
		public boolean costs() {
			return admin;
		}

		/** Whether the reader may write and read revenue of [projectId]. */
		public boolean leads(String projectId) {
			return admin || projectId != null && ledProjects.contains(projectId);
		}
	}

	/** The reader's reach; 403 for somebody who neither administers nor leads anything. */
	public Reach require(User viewer) {
		Reach reach = of(viewer);
		if (!reach.admin() && reach.ledProjects().isEmpty()) {
			throw ApiException.forbidden("error.billing.forbidden");
		}
		return reach;
	}

	/** The reader's reach, possibly empty. */
	public Reach of(User viewer) {
		if (viewer.isOrgAdmin()) {
			return new Reach(viewer, true, Set.of());
		}
		Query query = Query.query(new Criteria().orOperator(Criteria.where("leadIds").is(viewer.getId()),
				Criteria.where("leadId").is(viewer.getId()))).limit(MAX_LED_PROJECTS);
		query.fields().include("_id");
		Set<String> led = new LinkedHashSet<>();
		for (Document project : mongo.query(Project.class).as(Document.class).matching(query).all()) {
			led.add(String.valueOf(project.get("_id")));
		}
		return new Reach(viewer, false, Set.copyOf(led));
	}

	/** 403 unless the reader may see costs. */
	public void requireCosts(Reach reach) {
		if (!reach.costs()) {
			throw ApiException.forbidden("error.billing.costsForbidden");
		}
	}

	/** 403 unless the reader leads [projectId] or administers. */
	public void requireLead(Reach reach, String projectId) {
		if (!reach.leads(projectId)) {
			throw ApiException.forbidden("error.billing.notLead");
		}
	}
}
