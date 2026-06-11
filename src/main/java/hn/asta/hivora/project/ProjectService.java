package hn.asta.hivora.project;

import hn.asta.hivora.common.ApiException;
import hn.asta.hivora.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ProjectService {

	private final ProjectRepository projects;
	private final MongoTemplate mongo;

	public Project get(String id) {
		return projects.findById(id).orElseThrow(() -> ApiException.notFound("Project"));
	}

	public List<Project> visibleTo(User user) {
		return user.isAdmin() ? projects.findByArchivedFalse()
				: projects.findByMemberIdsContainsAndArchivedFalse(user.getId());
	}

	public Project create(Project project, User creator) {
		String key = project.getKey().toUpperCase(Locale.ROOT);
		if (!key.matches("[A-Z][A-Z0-9]{1,9}")) {
			throw ApiException.badRequest("Project key must be 2-10 characters, A-Z and digits");
		}
		if (projects.existsByKeyIgnoreCase(key)) {
			throw ApiException.conflict("Project key already exists");
		}
		project.setKey(key);
		if (project.getLeadId() == null) {
			project.setLeadId(creator.getId());
		}
		if (!project.getMemberIds().contains(creator.getId())) {
			project.getMemberIds().add(creator.getId());
		}
		return projects.save(project);
	}

	public Project save(Project project) {
		return projects.save(project);
	}

	/** Atomically reserves the next issue number for the project. */
	public long nextIssueNumber(String projectId) {
		Project updated = mongo.findAndModify(
				Query.query(Criteria.where("_id").is(projectId)),
				new Update().inc("issueCounter", 1),
				FindAndModifyOptions.options().returnNew(true),
				Project.class);
		if (updated == null) {
			throw ApiException.notFound("Project");
		}
		return updated.getIssueCounter();
	}

	public void assertMember(Project project, User user) {
		if (!user.isAdmin() && !project.getMemberIds().contains(user.getId())) {
			throw ApiException.forbidden("Not a member of this project");
		}
	}
}
