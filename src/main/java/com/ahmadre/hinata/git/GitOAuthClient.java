package com.ahmadre.hinata.git;

import com.ahmadre.hinata.common.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real provider API client for the OAuth flow — no emulation. Exchanges the
 * authorization {@code code} for an access token and lists the account's owners
 * (orgs / groups / workspaces) and repositories against the provider's live REST
 * API (GitHub, GitLab, Bitbucket SaaS). Self-managed instances use the URL+token
 * path instead and never reach here.
 */
@Component
@RequiredArgsConstructor
public class GitOAuthClient {

	private static final Logger log = LoggerFactory.getLogger(GitOAuthClient.class);

	private static final String GH_API = "https://api.github.com";
	private static final String GL_API = "https://gitlab.com/api/v4";
	private static final String BB_API = "https://api.bitbucket.org/2.0";
	private static final String ACCEPT = "Accept";
	private static final String APPLICATION_JSON = "application/json";

	/** Canonical GitHub-linguist colours for the few languages worth a dot. */
	private static final Map<String, String> LANG_COLORS = Map.ofEntries(
			Map.entry("Dart", "00B4AB"), Map.entry("Kotlin", "A97BFF"),
			Map.entry("Java", "B07219"), Map.entry("TypeScript", "3178C6"),
			Map.entry("JavaScript", "F1E05A"), Map.entry("Python", "3572A5"),
			Map.entry("Go", "00ADD8"), Map.entry("Rust", "DEA584"),
			Map.entry("Ruby", "701516"), Map.entry("Swift", "F05138"),
			Map.entry("C", "555555"), Map.entry("C++", "F34B7D"),
			Map.entry("C#", "178600"), Map.entry("PHP", "4F5D95"),
			Map.entry("Shell", "89E051"), Map.entry("HTML", "E34C26"),
			Map.entry("CSS", "563D7C"), Map.entry("HCL", "844FBA"),
			Map.entry("Vue", "41B883"), Map.entry("Scala", "C22D40"));

	private final GitIntegrationSettings config;
	// Self-contained mapper: the app registers no ObjectMapper bean (Spring MVC
	// uses its own internal one), so we don't depend on injection here.
	private final ObjectMapper mapper = new ObjectMapper();

	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(8))
			.build();

	// ─────────────────────────── code → token ───────────────────────────

	public String exchangeCode(String provider, String code, String redirectUri) {
		JsonNode json = switch (provider) {
			case "github" -> postForm("https://github.com/login/oauth/access_token",
					form("client_id", config.githubClientId(),
							"client_secret", config.githubClientSecret(),
							"code", code, "redirect_uri", redirectUri),
					Map.of("Accept", "application/json"));
			case "gitlab" -> postForm("https://gitlab.com/oauth/token",
					form("client_id", config.gitlabClientId(),
							"client_secret", config.gitlabClientSecret(),
							"code", code, "grant_type", "authorization_code",
							"redirect_uri", redirectUri),
					Map.of("Accept", "application/json"));
			case "bitbucket" -> postForm("https://bitbucket.org/site/oauth2/access_token",
					form("grant_type", "authorization_code", "code", code),
					Map.of("Accept", "application/json",
							"Authorization", basic(config.bitbucketClientId(), config.bitbucketClientSecret())));
			default -> throw ApiException.badRequest("error.git.unknownProvider", provider);
		};
		String token = json.path("access_token").asText(null);
		if (token == null || token.isBlank()) {
			log.warn("[git] OAuth token exchange for {} returned no access_token: {}",
					provider, json.path("error").asText(json.path("error_description").asText("")));
			throw ApiException.badRequest("error.git.oauthExchangeFailed");
		}
		return token;
	}

	// ─────────────────────────── owners ───────────────────────────

	public List<GitService.OwnerDto> listOwners(String provider, String token) {
		return switch (provider) {
			case "github" -> githubOwners(token);
			case "gitlab" -> gitlabOwners(token);
			case "bitbucket" -> bitbucketOwners(token);
			default -> throw ApiException.badRequest("error.git.unknownProvider", provider);
		};
	}

	private List<GitService.OwnerDto> githubOwners(String token) {
		List<GitService.OwnerDto> owners = new ArrayList<>();
		JsonNode me = getJson(GH_API + "/user", token);
		String login = me.path("login").asText("");
		if (!login.isBlank()) {
			owners.add(new GitService.OwnerDto(login, login, "Personal account",
					me.path("public_repos").asInt(-1)));
		}
		for (JsonNode org : getJson(GH_API + "/user/orgs?per_page=100", token)) {
			String orgLogin = org.path("login").asText("");
			if (!orgLogin.isBlank()) {
				owners.add(new GitService.OwnerDto(orgLogin, orgLogin, "Organization", -1));
			}
		}
		return owners;
	}

	private List<GitService.OwnerDto> gitlabOwners(String token) {
		List<GitService.OwnerDto> owners = new ArrayList<>();
		JsonNode me = getJson(GL_API + "/user", token);
		String username = me.path("username").asText("");
		if (!username.isBlank()) {
			owners.add(new GitService.OwnerDto(username, username, "Personal account", -1));
		}
		for (JsonNode g : getJson(GL_API + "/groups?min_access_level=30&per_page=100&order_by=path", token)) {
			String path = g.path("full_path").asText(g.path("path").asText(""));
			String name = g.path("name").asText(path);
			if (!path.isBlank()) {
				owners.add(new GitService.OwnerDto(path, name, "Group", -1));
			}
		}
		return owners;
	}

	private List<GitService.OwnerDto> bitbucketOwners(String token) {
		List<GitService.OwnerDto> owners = new ArrayList<>();
		JsonNode page = getJson(BB_API + "/workspaces?pagelen=100", token);
		for (JsonNode ws : page.path("values")) {
			String slug = ws.path("slug").asText("");
			String name = ws.path("name").asText(slug);
			if (!slug.isBlank()) {
				owners.add(new GitService.OwnerDto(slug, name, "Workspace", -1));
			}
		}
		return owners;
	}

	// ─────────────────────────── repos ───────────────────────────

	public List<GitService.RepoDto> listRepos(String provider, String token, String owner, String query) {
		List<GitService.RepoDto> repos = switch (provider) {
			case "github" -> githubRepos(token, owner);
			case "gitlab" -> gitlabRepos(token, owner);
			case "bitbucket" -> bitbucketRepos(token, owner);
			default -> throw ApiException.badRequest("error.git.unknownProvider", provider);
		};
		String needle = query == null ? "" : query.toLowerCase().trim();
		if (needle.isEmpty()) {
			return repos;
		}
		return repos.stream().filter(r -> r.name().toLowerCase().contains(needle)).toList();
	}

	private List<GitService.RepoDto> githubRepos(String token, String owner) {
		// An org lists via /orgs/{owner}/repos; a personal account via /user/repos
		// (so private repos are included). Try the org path first, fall back on 404.
		HttpResponse<String> orgResp = get(GH_API + "/orgs/" + enc(owner)
				+ "/repos?per_page=100&sort=updated&type=all", token);
		JsonNode arr = orgResp.statusCode() == 404
				? filterByOwner(parse(get(GH_API + "/user/repos?per_page=100&sort=updated&affiliation=owner",
						token)), owner)
				: parse(orgResp);
		List<GitService.RepoDto> repos = new ArrayList<>();
		for (JsonNode r : arr) {
			repos.add(new GitService.RepoDto(
					r.path("name").asText(""),
					r.path("private").asBoolean(false),
					textOrNull(r, "language"),
					langColor(textOrNull(r, "language")),
					relative(r.path("pushed_at").asText(r.path("updated_at").asText(null)))));
		}
		return repos;
	}

	private List<GitService.RepoDto> gitlabRepos(String token, String owner) {
		// Group projects; fall back to the user's own projects when not a group.
		HttpResponse<String> groupResp = get(GL_API + "/groups/" + enc(owner)
				+ "/projects?per_page=100&order_by=last_activity_at&include_subgroups=true", token);
		JsonNode arr = groupResp.statusCode() == 404
				? parse(get(GL_API + "/users/" + enc(owner)
						+ "/projects?per_page=100&order_by=last_activity_at", token))
				: parse(groupResp);
		List<GitService.RepoDto> repos = new ArrayList<>();
		for (JsonNode r : arr) {
			String lang = null; // GitLab needs a per-project call for languages; skip.
			repos.add(new GitService.RepoDto(
					r.path("path").asText(r.path("name").asText("")),
					!"public".equals(r.path("visibility").asText("private")),
					lang,
					langColor(lang),
					relative(r.path("last_activity_at").asText(null))));
		}
		return repos;
	}

	private List<GitService.RepoDto> bitbucketRepos(String token, String owner) {
		JsonNode page = getJson(BB_API + "/repositories/" + enc(owner) + "?pagelen=100&sort=-updated_on", token);
		List<GitService.RepoDto> repos = new ArrayList<>();
		for (JsonNode r : page.path("values")) {
			String lang = capitalize(textOrNull(r, "language"));
			repos.add(new GitService.RepoDto(
					r.path("name").asText(r.path("slug").asText("")),
					r.path("is_private").asBoolean(false),
					lang,
					langColor(lang),
					relative(r.path("updated_on").asText(null))));
		}
		return repos;
	}

	private JsonNode filterByOwner(JsonNode repos, String owner) {
		var array = mapper.createArrayNode();
		for (JsonNode r : repos) {
			if (owner.equalsIgnoreCase(r.path("owner").path("login").asText(""))) {
				array.add(r);
			}
		}
		return array;
	}

	// ─────────────────────────── commit stats ───────────────────────────

	/** Line additions/deletions for a single commit; zeros when unavailable. */
	public record CommitStats(int additions, int deletions) {
		static final CommitStats NONE = new CommitStats(0, 0);
	}

	/**
	 * Fetches a commit's line stats from the provider API. Push webhooks don't
	 * carry per-commit line counts, so the {@code +x / -y} shown on an issue comes
	 * from here. Best-effort: any failure (missing token, rate limit, network, an
	 * unreachable self-managed host) yields zeros rather than throwing, so
	 * recording the commit is never blocked.
	 */
	public CommitStats commitStats(String provider, String token, String owner, String repo, String sha) {
		if (token == null || token.isBlank() || sha == null || sha.isBlank()) {
			return CommitStats.NONE;
		}
		try {
			return switch (provider) {
				case "github" -> githubCommitStats(token, owner, repo, sha);
				case "gitlab" -> gitlabCommitStats(token, owner, repo, sha);
				case "bitbucket" -> bitbucketCommitStats(token, owner, repo, sha);
				default -> CommitStats.NONE;
			};
		}
		catch (RuntimeException e) {
			log.warn("[git] commit stats ({}) for {} failed: {}", provider, sha, e.getMessage());
			return CommitStats.NONE;
		}
	}

	private CommitStats githubCommitStats(String token, String owner, String repo, String sha) {
		HttpResponse<String> resp = get(GH_API + "/repos/" + enc(owner) + "/" + enc(repo)
				+ "/commits/" + enc(sha), token);
		if (resp.statusCode() / 100 != 2) {
			return CommitStats.NONE;
		}
		JsonNode stats = parse(resp).path("stats");
		return new CommitStats(stats.path("additions").asInt(0), stats.path("deletions").asInt(0));
	}

	private CommitStats gitlabCommitStats(String token, String owner, String repo, String sha) {
		HttpResponse<String> resp = get(GL_API + "/projects/" + enc(owner + "/" + repo)
				+ "/repository/commits/" + enc(sha), token);
		if (resp.statusCode() / 100 != 2) {
			return CommitStats.NONE;
		}
		JsonNode stats = parse(resp).path("stats");
		return new CommitStats(stats.path("additions").asInt(0), stats.path("deletions").asInt(0));
	}

	private CommitStats bitbucketCommitStats(String token, String owner, String repo, String sha) {
		// Bitbucket commits carry no line totals; sum the diffstat entries instead.
		HttpResponse<String> resp = get(BB_API + "/repositories/" + enc(owner) + "/" + enc(repo)
				+ "/diffstat/" + enc(sha) + "?pagelen=100", token);
		if (resp.statusCode() / 100 != 2) {
			return CommitStats.NONE;
		}
		int added = 0;
		int removed = 0;
		for (JsonNode f : parse(resp).path("values")) {
			added += f.path("lines_added").asInt(0);
			removed += f.path("lines_removed").asInt(0);
		}
		return new CommitStats(added, removed);
	}

	// ─────────────────────────── merge PR ───────────────────────────

	/**
	 * Merges a pull/merge request on the provider. Throws {@link ApiException} on
	 * any non-success (conflict, blocked checks, already closed, unreachable) so
	 * the caller never records a fake merge — the failure propagates to the client
	 * and the optimistic UI rolls back.
	 */
	public void mergePr(String provider, String token, String owner, String repo, int number) {
		if (token == null || token.isBlank()) {
			throw ApiException.badRequest("error.git.mergeFailed");
		}
		switch (provider) {
			case "github" -> githubMerge(token, owner, repo, number);
			case "gitlab" -> gitlabMerge(token, owner, repo, number);
			case "bitbucket" -> bitbucketMerge(token, owner, repo, number);
			default -> throw ApiException.badRequest("error.git.unknownProvider", provider);
		}
	}

	private void githubMerge(String token, String owner, String repo, int number) {
		ObjectNode body = mapper.createObjectNode();
		body.put("merge_method", "merge");
		putJson(GH_API + "/repos/" + enc(owner) + "/" + enc(repo) + "/pulls/" + number + "/merge",
				token, body, "error.git.mergeFailed");
	}

	private void gitlabMerge(String token, String owner, String repo, int number) {
		putJson(GL_API + "/projects/" + enc(owner + "/" + repo) + "/merge_requests/" + number + "/merge",
				token, mapper.createObjectNode(), "error.git.mergeFailed");
	}

	private void bitbucketMerge(String token, String owner, String repo, int number) {
		// Bitbucket merges via POST (no body required).
		HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(
				BB_API + "/repositories/" + enc(owner) + "/" + enc(repo) + "/pullrequests/" + number + "/merge"))
				.timeout(Duration.ofSeconds(20))
				.header("Authorization", "Bearer " + token)
				.header(ACCEPT, APPLICATION_JSON)
				.header("Content-Type", APPLICATION_JSON)
				.header("User-Agent", "hinata")
				.POST(HttpRequest.BodyPublishers.noBody())
				.build());
		if (resp.statusCode() / 100 != 2) {
			log.warn("[git] PR merge POST {} -> HTTP {}", resp.uri(), resp.statusCode());
			throw ApiException.badRequest("error.git.mergeFailed");
		}
	}

	/** Authenticated PUT of a JSON body; non-2xx throws the given error key. */
	private void putJson(String url, String token, JsonNode body, String errorKey) {
		String json;
		try {
			json = mapper.writeValueAsString(body);
		}
		catch (JsonProcessingException e) {
			throw ApiException.badRequest(errorKey);
		}
		HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("Authorization", "Bearer " + token)
				.header(ACCEPT, APPLICATION_JSON)
				.header("Content-Type", APPLICATION_JSON)
				.header("User-Agent", "hinata")
				.PUT(HttpRequest.BodyPublishers.ofString(json))
				.build());
		if (resp.statusCode() / 100 != 2) {
			log.warn("[git] PR merge PUT {} -> HTTP {}", url, resp.statusCode());
			throw ApiException.badRequest(errorKey);
		}
	}

	// ─────────────────────────── webhooks ───────────────────────────

	/**
	 * Registers a push + pull-request (+ CI) webhook on the repo pointing at
	 * {@code callbackUrl}, signed with {@code secret}. Returns the provider hook
	 * id (kept so it can be removed on disconnect). Throws on failure — callers
	 * treat webhook setup as best-effort.
	 */
	public String registerWebhook(String provider, String token, String owner, String repo,
			String callbackUrl, String secret) {
		return switch (provider) {
			case "github" -> githubHook(token, owner, repo, callbackUrl, secret);
			case "gitlab" -> gitlabHook(token, owner, repo, callbackUrl, secret);
			case "bitbucket" -> bitbucketHook(token, owner, repo, callbackUrl, secret);
			default -> throw ApiException.badRequest("error.git.unknownProvider", provider);
		};
	}

	/** Best-effort removal of a previously registered webhook. */
	public void deleteWebhook(String provider, String token, String owner, String repo, String hookId) {
		if (hookId == null || hookId.isBlank()) {
			return;
		}
		String url = switch (provider) {
			case "github" -> GH_API + "/repos/" + owner + "/" + repo + "/hooks/" + hookId;
			case "gitlab" -> GL_API + "/projects/" + enc(owner + "/" + repo) + "/hooks/" + hookId;
			case "bitbucket" -> BB_API + "/repositories/" + owner + "/" + repo + "/hooks/" + enc(hookId);
			default -> null;
		};
		if (url == null) {
			return;
		}
		try {
			send(HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(12))
					.header("Authorization", "Bearer " + token)
					.header(ACCEPT, APPLICATION_JSON)
					.header("User-Agent", "hinata")
					.DELETE().build());
		}
		catch (RuntimeException e) {
			log.warn("[git] webhook delete ({}) failed: {}", provider, e.getMessage());
		}
	}

	private String githubHook(String token, String owner, String repo, String callbackUrl, String secret) {
		ObjectNode cfg = mapper.createObjectNode();
		cfg.put("url", callbackUrl);
		cfg.put("content_type", "json");
		cfg.put("secret", secret);
		cfg.put("insecure_ssl", "0");
		ObjectNode body = mapper.createObjectNode();
		body.put("name", "web");
		body.put("active", true);
		ArrayNode events = body.putArray("events");
		events.add("push");
		events.add("create"); // branch/tag creation (e.g. "Create branch" via the API, no push)
		events.add("pull_request");
		events.add("workflow_run");
		body.set("config", cfg);
		JsonNode resp = postJson(GH_API + "/repos/" + owner + "/" + repo + "/hooks", token, body);
		return resp.path("id").asText(null);
	}

	private String gitlabHook(String token, String owner, String repo, String callbackUrl, String secret) {
		ObjectNode body = mapper.createObjectNode();
		body.put("url", callbackUrl);
		body.put("token", secret);
		body.put("push_events", true);
		body.put("merge_requests_events", true);
		body.put("pipeline_events", true);
		body.put("enable_ssl_verification", true);
		JsonNode resp = postJson(GL_API + "/projects/" + enc(owner + "/" + repo) + "/hooks", token, body);
		return resp.path("id").asText(null);
	}

	private String bitbucketHook(String token, String owner, String repo, String callbackUrl, String secret) {
		// Bitbucket Cloud webhooks are unsigned, so the shared secret rides in the
		// callback query string and is checked on receipt.
		ObjectNode body = mapper.createObjectNode();
		body.put("description", "hinata");
		body.put("url", callbackUrl + "?secret=" + enc(secret));
		body.put("active", true);
		ArrayNode events = body.putArray("events");
		events.add("repo:push");
		events.add("pullrequest:created");
		events.add("pullrequest:updated");
		events.add("pullrequest:fulfilled");
		events.add("pullrequest:rejected");
		JsonNode resp = postJson(BB_API + "/repositories/" + owner + "/" + repo + "/hooks", token, body);
		return resp.path("uuid").asText(null);
	}

	private JsonNode postJson(String url, String token, JsonNode body) {
		String json;
		try {
			json = mapper.writeValueAsString(body);
		}
		catch (JsonProcessingException e) {
			throw ApiException.badRequest("error.git.apiFailed");
		}
		HttpResponse<String> resp = send(HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + token)
				.header(ACCEPT, APPLICATION_JSON)
				.header("Content-Type", APPLICATION_JSON)
				.header("User-Agent", "hinata")
				.POST(HttpRequest.BodyPublishers.ofString(json))
				.build());
		if (resp.statusCode() / 100 != 2) {
			log.warn("[git] webhook POST {} -> HTTP {}", url, resp.statusCode());
			throw ApiException.badRequest("error.git.apiFailed");
		}
		return parse(resp);
	}

	// ─────────────────────────── HTTP plumbing ───────────────────────────

	private JsonNode postForm(String url, String body, Map<String, String> headers) {
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		headers.forEach(req::header);
		HttpResponse<String> resp = send(req.build());
		if (resp.statusCode() / 100 != 2) {
			log.warn("[git] token endpoint {} -> HTTP {}", url, resp.statusCode());
			throw ApiException.badRequest("error.git.oauthExchangeFailed");
		}
		return parse(resp);
	}

	/** Authenticated GET returning the parsed body; non-2xx throws. */
	private JsonNode getJson(String url, String token) {
		HttpResponse<String> resp = get(url, token);
		if (resp.statusCode() / 100 != 2) {
			log.warn("[git] provider GET {} -> HTTP {}", url, resp.statusCode());
			throw ApiException.badRequest("error.git.apiFailed");
		}
		return parse(resp);
	}

	/** Authenticated GET returning the raw response (caller inspects status). */
	private HttpResponse<String> get(String url, String token) {
		return send(HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + token)
				.header("Accept", "application/json")
				.header("User-Agent", "hinata")
				.GET()
				.build());
	}

	private HttpResponse<String> send(HttpRequest request) {
		try {
			return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw ApiException.badRequest("error.git.apiFailed");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw ApiException.badRequest("error.git.apiFailed");
		}
	}

	private JsonNode parse(HttpResponse<String> resp) {
		try {
			String body = resp.body();
			return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
		}
		catch (IOException e) {
			throw ApiException.badRequest("error.git.apiFailed");
		}
	}

	// ─────────────────────────── helpers ───────────────────────────

	private static String form(String... kv) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			map.put(kv[i], kv[i + 1]);
		}
		StringBuilder sb = new StringBuilder();
		map.forEach((k, v) -> {
			if (sb.length() > 0) {
				sb.append('&');
			}
			sb.append(enc(k)).append('=').append(enc(v));
		});
		return sb.toString();
	}

	private static String basic(String user, String pass) {
		return "Basic " + Base64.getEncoder()
				.encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
	}

	private static String enc(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	private static String textOrNull(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
	}

	private static String langColor(String lang) {
		return lang == null ? null : LANG_COLORS.get(lang);
	}

	private static String capitalize(String s) {
		return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** ISO-8601 timestamp → short relative label (e.g. {@code 3d}) shown in the picker. */
	private static String relative(String iso) {
		if (iso == null || iso.isBlank()) {
			return null;
		}
		Instant then;
		try {
			then = Instant.parse(iso);
		}
		catch (RuntimeException e) {
			return null;
		}
		long mins = ChronoUnit.MINUTES.between(then, Instant.now());
		if (mins < 1) {
			return "now";
		}
		if (mins < 60) {
			return mins + "m";
		}
		if (mins < 60 * 24) {
			return (mins / 60) + "h";
		}
		long days = mins / (60 * 24);
		if (days < 30) {
			return days + "d";
		}
		if (days < 365) {
			return (days / 30) + "mo";
		}
		return (days / 365) + "y";
	}
}
