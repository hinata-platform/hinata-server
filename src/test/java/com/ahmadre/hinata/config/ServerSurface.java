package com.ahmadre.hinata.config;

import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Everything this server answers, read from the running application rather than from the
 * source: the routes of every handler mapping (annotated controllers and the actuator, router
 * functions such as the MCP transport, URL mappings such as static resources), the filters of
 * every security chain, and every servlet and servlet filter. {@link UnauthenticatedSurfaceTest}
 * holds it against what was reviewed.
 */
final class ServerSurface {

	/** Fills a path variable or wildcard; the first one its pattern accepts wins. */
	private static final List<String> SAMPLES = List.of(
			"0123456789abcdef01234567", "01234567-89ab-cdef-0123-456789abcdef", "HIN-1", "x");

	private static final Authentication ANONYMOUS = new AnonymousAuthenticationToken(
			"surface", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	private ServerSurface() {
	}

	/** A route as its mapping declares it: an HTTP method, or {@link #ANY}, and a path pattern. */
	record Route(String method, String pattern) {

		static final String ANY = "ANY";

		/** The declared method, or for a mapping without one every method that can change state. */
		List<String> methods() {
			return ANY.equals(method) ? List.of("GET", "POST", "PUT", "PATCH", "DELETE") : List.of(method);
		}

		@Override
		public String toString() {
			return method + " " + pattern;
		}
	}

	/** Every route of every handler mapping; one the test cannot read fails it instead of hiding. */
	static SortedSet<Route> routes(ApplicationContext context) {
		SortedSet<Route> routes = new TreeSet<>(Comparator.comparing(Route::pattern).thenComparing(Route::method));
		for (HandlerMapping mapping : context.getBeansOfType(HandlerMapping.class).values()) {
			switch (mapping) {
				case RequestMappingInfoHandlerMapping annotated -> annotated.getHandlerMethods().keySet()
						.forEach(info -> {
							Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
							for (String pattern : info.getPatternValues()) {
								if (methods.isEmpty()) {
									routes.add(new Route(Route.ANY, pattern));
								}
								methods.forEach(method -> routes.add(new Route(method.name(), pattern)));
							}
						});
				case RouterFunctionMapping functional -> {
					if (functional.getRouterFunction() != null) {
						functional.getRouterFunction().accept(new RouterReader(routes));
					}
				}
				case AbstractUrlHandlerMapping urls -> {
					urls.getHandlerMap().keySet().forEach(pattern -> routes.add(new Route(Route.ANY, pattern)));
					if (urls.getRootHandler() != null) {
						routes.add(new Route(Route.ANY, "/"));
					}
				}
				default -> throw new IllegalStateException("A handler mapping this test cannot read: "
						+ mapping.getClass().getName() + ". Teach ServerSurface its routes; until then nothing it "
						+ "answers is checked.");
			}
		}
		return routes;
	}

	/**
	 * Each route outside [reviewed], sent without a token with each of its methods to the server on
	 * [port]: every answer that is not 401, with the address and the status.
	 */
	static List<String> answeredWithoutToken(Collection<Route> routes, Set<String> reviewed, int port) {
		HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
		List<String> answered = new ArrayList<>();
		for (Route route : routes) {
			if (reviewed.contains(route.toString())) {
				continue;
			}
			String uri = sampleUri(route.pattern());
			for (String method : route.methods()) {
				int status = send(http, method, "http://localhost:" + port + uri);
				if (status != 401) {
					answered.add(route + "  (" + method + " " + uri + " answered " + status + ")");
				}
			}
		}
		return answered;
	}

	/**
	 * Entries of [reviewed] that say more than the server does: no route of that name, or a route
	 * the security rules no longer open to a caller without a token. Asked of the rules, not sent,
	 * so that checking the list signs nobody up and sets nothing up.
	 */
	static List<String> staleEntries(Set<String> reviewed, Collection<Route> routes, ApplicationContext context) {
		Map<String, Route> byName = routes.stream().collect(Collectors.toMap(Route::toString, Function.identity()));
		FilterChainProxy security = context.getBean(FilterChainProxy.class);
		List<String> stale = new ArrayList<>();
		for (String entry : new TreeSet<>(reviewed)) {
			Route route = byName.get(entry);
			if (route == null) {
				stale.add(entry + "  (no such route)");
				continue;
			}
			String uri = sampleUri(route.pattern());
			for (String method : route.methods()) {
				if (!openByRule(security, method, uri)) {
					stale.add(entry + "  (" + method + " " + uri + " needs a token)");
				}
			}
		}
		return stale;
	}

	/** The filters of each security chain, in order, by class name. */
	static List<List<String>> chainFilters(ApplicationContext context) {
		return context.getBean(FilterChainProxy.class).getFilterChains().stream()
				.map(chain -> chain.getFilters().stream().map(filter -> filter.getClass().getSimpleName()).toList())
				.toList();
	}

	/** The servlets the container runs, by registration name. */
	static Set<String> servlets(ApplicationContext context) {
		return new TreeSet<>(((WebApplicationContext) context).getServletContext().getServletRegistrations().keySet());
	}

	/** The servlet filters the container runs around every servlet, by registration name. */
	static Set<String> servletFilters(ApplicationContext context) {
		return new TreeSet<>(((WebApplicationContext) context).getServletContext().getFilterRegistrations().keySet());
	}

	/** A concrete address [pattern] matches, each variable and wildcard filled with a sample. */
	static String sampleUri(String pattern) {
		PathPattern parsed;
		try {
			parsed = PathPatternParser.defaultInstance.parse(pattern);
		} catch (PatternParseException legacyPattern) {
			return fill(pattern, SAMPLES.getLast());
		}
		for (String sample : SAMPLES) {
			String uri = fill(pattern, sample);
			if (parsed.matches(PathContainer.parsePath(uri))) {
				return uri;
			}
		}
		throw new IllegalStateException("No sample address matches " + pattern + "; add one to ServerSurface.SAMPLES.");
	}

	/** [pattern] with every {variable} (regular expressions with braces included) and wildcard as [sample]. */
	private static String fill(String pattern, String sample) {
		StringBuilder uri = new StringBuilder();
		int depth = 0;
		for (char c : pattern.toCharArray()) {
			if (c == '{') {
				if (depth++ == 0) {
					uri.append(sample);
				}
			} else if (c == '}') {
				depth--;
			} else if (depth == 0) {
				uri.append(c);
			}
		}
		return uri.toString().replaceAll("\\*+", sample);
	}

	private static boolean openByRule(FilterChainProxy security, String method, String uri) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
		SecurityFilterChain chain = security.getFilterChains().stream()
				.filter(candidate -> candidate.matches(request))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("No security chain takes " + method + " " + uri));
		AuthorizationFilter authorization = chain.getFilters().stream()
				.filter(AuthorizationFilter.class::isInstance)
				.map(AuthorizationFilter.class::cast)
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("A security chain without authorization takes " + uri));
		AuthorizationResult result = authorization.getAuthorizationManager().authorize(() -> ANONYMOUS, request);
		return result != null && result.isGranted();
	}

	private static int send(HttpClient http, String method, String address) {
		try {
			return http.send(HttpRequest.newBuilder(URI.create(address))
					.method(method, HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (IOException e) {
			throw new IllegalStateException(method + " " + address + " failed", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(method + " " + address + " was interrupted", e);
		}
	}

	/** Reads method and path out of router functions, nested ones and their prefixes included. */
	private static final class RouterReader implements RouterFunctions.Visitor, RequestPredicates.Visitor {

		private final Set<Route> routes;
		private final Deque<String> prefixes = new ArrayDeque<>();
		private final Set<String> methods = new TreeSet<>();
		private String path;

		RouterReader(Set<Route> routes) {
			this.routes = routes;
		}

		@Override
		public void startNested(RequestPredicate predicate) {
			read(predicate);
			prefixes.addLast(path == null ? "" : path);
		}

		@Override
		public void endNested(RequestPredicate predicate) {
			prefixes.removeLast();
		}

		@Override
		public void route(RequestPredicate predicate, HandlerFunction<?> handlerFunction) {
			read(predicate);
			String pattern = String.join("", prefixes) + (path == null ? "" : path);
			if (methods.isEmpty()) {
				routes.add(new Route(Route.ANY, pattern));
			}
			methods.forEach(method -> routes.add(new Route(method, pattern)));
		}

		@Override
		public void resources(Function<ServerRequest, Optional<Resource>> lookupFunction) {
			throw new IllegalStateException("A router function serves resources; list their paths in ServerSurface.");
		}

		@Override
		public void attributes(Map<String, Object> attributes) {
		}

		@Override
		public void unknown(RouterFunction<?> routerFunction) {
			throw new IllegalStateException("A router function this test cannot read: " + routerFunction);
		}

		@Override
		public void method(Set<HttpMethod> methods) {
			methods.forEach(method -> this.methods.add(method.name()));
		}

		@Override
		public void path(String pattern) {
			path = pattern;
		}

		@Override
		public void pathExtension(String extension) {
		}

		@Override
		public void header(String name, String value) {
		}

		@Override
		public void param(String name, String value) {
		}

		@Override
		public void version(String version) {
		}

		@Override
		public void startAnd() {
		}

		@Override
		public void and() {
		}

		@Override
		public void endAnd() {
		}

		@Override
		public void startOr() {
		}

		@Override
		public void or() {
		}

		@Override
		public void endOr() {
		}

		@Override
		public void startNegate() {
		}

		@Override
		public void endNegate() {
		}

		@Override
		public void unknown(RequestPredicate predicate) {
			throw new IllegalStateException("A request predicate this test cannot read: " + predicate);
		}

		private void read(RequestPredicate predicate) {
			path = null;
			methods.clear();
			predicate.accept(this);
		}
	}
}
