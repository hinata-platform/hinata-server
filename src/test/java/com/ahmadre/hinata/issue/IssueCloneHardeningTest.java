package com.ahmadre.hinata.issue;

import com.ahmadre.hinata.audit.AuditAction;
import com.ahmadre.hinata.audit.AuditLogRepository;
import com.ahmadre.hinata.project.Project;
import com.ahmadre.hinata.project.ProjectRepository;
import com.ahmadre.hinata.user.Role;
import com.ahmadre.hinata.user.User;
import com.ahmadre.hinata.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;

/**
 * What a clone must cost, and what must survive it going wrong.
 *
 * <p>Both are properties of the calls {@link IssueCloneService} makes rather
 * than of the rows it leaves behind, which is why this class spies on
 * {@link IssueLinkService} instead of reading the database like
 * {@code IssueCloneIntegrationTest} does. It is deliberately a class of its own:
 * a spy sitting in front of a service that ten threads hammer in the
 * parallel-numbering test there is a source of flakiness nobody would thank us
 * for.
 */
@SpringBootTest(properties = {
		"hinata.mongodb.tls.enabled=false",
		"hinata.gateway.enabled=false",
		"hinata.demo.seed=false",
		"hinata.rate-limit.enabled=false",
		"management.health.mail.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class IssueCloneHardeningTest {

	@Container
	@ServiceConnection
	static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));

	@Autowired
	private MongoTemplate mongo;
	@Autowired
	private IssueCloneService clones;
	@Autowired
	private IssueService issues;
	@Autowired
	private IssueRepository issueRepository;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private UserRepository users;
	@Autowired
	private AuditLogRepository auditLog;

	// The subject: how many times the clone reaches into the link service, and
	// what happens when that reach fails.
	@MockitoSpyBean
	private IssueLinkService links;

	private User member;
	private Project project;
	private Issue original;

	@BeforeEach
	void seed() {
		for (String collection : List.of("issues", "issue_links", "issue_activities",
				"issue_comments", "projects", "users", "notifications", "audit_log")) {
			mongo.getCollection(collection).deleteMany(new Document());
		}
		member = users.save(User.builder().email("member@example.org").username("member")
				.displayName("member").roles(Set.of(Role.MEMBER)).active(true).build());
		project = projects.save(Project.builder().key("HIN").name("Hinata")
				.leadId(member.getId())
				.leadIds(new ArrayList<>(List.of(member.getId())))
				.memberIds(new ArrayList<>(List.of(member.getId())))
				.build());
		original = issue("The hub ticket");
	}

	private Issue issue(String title) {
		return issues.create(Issue.builder().projectId(project.getId()).title(title)
				.assigneeIds(new ArrayList<>()).tags(new ArrayList<>()).build(), member);
	}

	private IssueCloneService.Options options(boolean includeLinks) {
		return new IssueCloneService.Options("CLONE - " + original.getTitle(), List.of(), false,
				includeLinks, false);
	}

	/**
	 * Copying n links must not cost n round-trips into the link service, and none
	 * of the trips it does make may ask for a rendered link list.
	 *
	 * <p>Two bounds, because a clone can blow past either one. {@code addLinks}
	 * answers with the issue's whole link list — it re-reads every link and loads
	 * the issue behind each one, description and all — so asking it per link makes
	 * a clone cost on the order of n² reads, and asking it per <em>batch</em> still
	 * re-renders a list that every batch before it has grown. Neither is looked at
	 * here. {@code addLinksWithoutView} writes the same rows and skips the view;
	 * the count below keeps the batching, and the {@code never()} keeps the view
	 * gone.
	 *
	 * <p>Pinned as an exact count rather than a ceiling: three is one call for the
	 * copy's own origin plus one per type/orientation the original carries, and
	 * that is the whole rule. Twelve links produced thirteen calls before.
	 */
	@Test
	void copyingLinksCostsOneCallPerTypeAndDirectionRatherThanOnePerLink() {
		for (int i = 0; i < 6; i++) {
			links.addLinks(original.getId(), IssueLinkType.BLOCKS, true,
					List.of(issue("blocked " + i).getId()), member);
			links.addLinks(original.getId(), IssueLinkType.RELATES, true,
					List.of(issue("related " + i).getId()), member);
		}
		Mockito.clearInvocations(links);

		Issue copy = clones.clone(original.getId(), options(true), member);

		Mockito.verify(links, Mockito.times(3))
				.addLinksWithoutView(any(), any(), anyBoolean(), any(), any());
		Mockito.verify(links, Mockito.never())
				.addLinks(any(), any(), anyBoolean(), any(), any());
		// And every link still made it across, which is what the batching must not
		// have bought its cheapness with.
		assertThat(links.linksOf(copy.getId(), member))
				.extracting(IssueLinkService.LinkView::type)
				.filteredOn(type -> type != IssueLinkType.CLONES)
				.hasSize(12);
	}

	/**
	 * A clone is recorded even when the link write behind it fails.
	 *
	 * <p>The copy exists the moment {@code create} returns; everything after it is
	 * decoration. An audit log that skips the clones whose links did not save is
	 * one that misses exactly the entries worth looking at — a new issue sitting
	 * in a project with nothing anywhere saying who put it there or what it was
	 * copied from.
	 */
	@Test
	void theCloneIsAuditedEvenWhenTheOriginLinkCannotBeWritten() {
		Mockito.doThrow(new IllegalStateException("link store unavailable"))
				.when(links).addLinksWithoutView(any(), any(), anyBoolean(), any(), any());

		assertThatThrownBy(() -> clones.clone(original.getId(), options(false), member))
				.isInstanceOf(IllegalStateException.class);

		assertThat(issueRepository.count()).as("the copy was written before the failure")
				.isEqualTo(2);
		assertThat(auditLog.findAll())
				.filteredOn(entry -> entry.getAction() == AuditAction.ISSUE_CLONED)
				.singleElement()
				.satisfies(entry -> {
					assertThat(entry.getActorId()).isEqualTo(member.getId());
					assertThat(entry.getMetadata()).containsEntry("source", original.getReadableId());
				});
	}
}
