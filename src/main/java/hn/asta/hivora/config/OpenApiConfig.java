package hn.asta.hivora.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@OpenAPIDefinition(
		info = @Info(
				title       = "Hivora API",
				version     = "1.0",
				description = "Self-hosted project management REST API. All endpoints except the ones "
						+ "listed under *Public* require a Bearer access token obtained from `POST /api/v1/auth/login`.",
				contact     = @Contact(name = "AStA Hochschule Niederrhein", url = "https://asta.hn"),
				license     = @License(name = "GPL-3.0", url = "https://www.gnu.org/licenses/gpl-3.0.html")
		),
		servers = @Server(url = "/", description = "Current server")
)
@SecurityScheme(
		name        = "Bearer",
		type        = SecuritySchemeType.HTTP,
		scheme      = "bearer",
		bearerFormat = "JWT",
		in          = SecuritySchemeIn.HEADER,
		description = "JWT access token issued by `POST /api/v1/auth/login`. "
				+ "Refresh tokens are **not** accepted here."
)
@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI hivoraOpenApi() {
		return new OpenAPI()
				.addSecurityItem(new SecurityRequirement().addList("Bearer"))
				.tags(List.of(
						new Tag().name("Public").description("No authentication required"),
						new Tag().name("Auth").description("Login, token refresh, password management"),
						new Tag().name("SSO").description("Single-sign-on provider listing and callbacks"),
						new Tag().name("Setup").description("First-run setup wizard (unauthenticated)"),
						new Tag().name("Projects").description("Project CRUD and workflow management"),
						new Tag().name("Issues").description("Issue CRUD, comments, state transitions"),
						new Tag().name("Attachments").description("File uploads and downloads per issue"),
						new Tag().name("Boards").description("Agile boards, sprints and columns"),
						new Tag().name("Gantt").description("Gantt task read-model per project"),
						new Tag().name("Time Tracking").description("Work items and timesheet aggregation"),
						new Tag().name("Dashboard").description("Personalised dashboard aggregation"),
						new Tag().name("Reports").description("Issue and time reports"),
						new Tag().name("Knowledge Base").description("Hierarchical Markdown articles"),
						new Tag().name("Notifications").description("In-app notifications"),
						new Tag().name("Users").description("User search, management and directory lookup"),
						new Tag().name("Admin").description("Runtime server settings (ADMIN role required)")
				));
	}
}
