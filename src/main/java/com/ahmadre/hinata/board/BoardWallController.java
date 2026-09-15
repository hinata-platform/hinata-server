package com.ahmadre.hinata.board;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.issue.IssueLinkGraphService;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A board read in pages: the wall, a page of cards, the facets of the filter. See
 * {@link BoardReader}. {@code GET /api/v1/boards/{id}} keeps answering in its old shape for app
 * versions that do not know these routes.
 *
 * <p>The search and the filter arrive as plain query parameters, every list as repeated values:
 * {@code q} is matched against the key, the title and the labels ignoring case, {@code sprints}
 * takes {@code __none__} for the cards in no sprint, and {@code shape} is {@code wall} (the
 * default), {@code subtasks}, {@code timeline} or {@code planning}, see {@link BoardQuery.Shape}.
 * They are bound one by one rather than into an object, so an indexed name such as
 * {@code labels[3]} is an unknown parameter and never a path into a list.
 */
@Tag(name = "Boards")
@RestController
@RequestMapping("/api/v1/boards/{id}")
@RequiredArgsConstructor
public class BoardWallController {

	private final BoardReader reader;
	private final CurrentUser currentUser;

	/**
	 * The wall: every column with its number of cards and the first [size] of them, and the people,
	 * epics and parents those cards name. Without [sprintId] the board's active sprint applies.
	 */
	@GetMapping("/wall")
	public BoardReader.BoardWall wall(@PathVariable String id,
			@RequestParam(required = false) String sprintId,
			@RequestParam(defaultValue = "30") int size,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) List<String> states,
			@RequestParam(required = false) List<String> types,
			@RequestParam(required = false) List<String> priorities,
			@RequestParam(required = false) List<String> assigneeIds,
			@RequestParam(required = false) List<String> reporterIds,
			@RequestParam(required = false) List<String> labels,
			@RequestParam(required = false) List<String> sprints,
			@RequestParam(required = false) List<String> epicIds,
			@RequestParam(required = false) String shape) {
		User user = currentUser.require();
		BoardQuery query = BoardQuery.of(q, states, types, priorities, assigneeIds, reporterIds, labels, sprints,
				epicIds, shape);
		return reader.wall(id, sprintId, query, size, user);
	}

	/**
	 * One page of cards: of the column called [column], of the sprint [sprintId], of the [backlog],
	 * or of the whole board. [dated] splits the timeline, [summary] adds the cards by state.
	 */
	@GetMapping("/cards")
	public BoardReader.BoardCardPage cards(@PathVariable String id,
			@RequestParam(required = false) String column,
			@RequestParam(required = false) String sprintId,
			@RequestParam(defaultValue = "false") boolean backlog,
			@RequestParam(required = false) Boolean dated,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "30") int size,
			@RequestParam(defaultValue = "false") boolean summary,
			@RequestParam(required = false) String q,
			@RequestParam(required = false) List<String> states,
			@RequestParam(required = false) List<String> types,
			@RequestParam(required = false) List<String> priorities,
			@RequestParam(required = false) List<String> assigneeIds,
			@RequestParam(required = false) List<String> reporterIds,
			@RequestParam(required = false) List<String> labels,
			@RequestParam(required = false) List<String> sprints,
			@RequestParam(required = false) List<String> epicIds,
			@RequestParam(required = false) String shape) {
		User user = currentUser.require();
		BoardQuery query = BoardQuery.of(q, states, types, priorities, assigneeIds, reporterIds, labels, sprints,
				epicIds, shape);
		return reader.cards(id, new BoardReader.CardSource(column, sprintId, backlog, dated), query, page, size,
				summary, user);
	}

	/**
	 * What the filter and the row of faces can offer over every card of the board: the people, the
	 * reporters, the labels and the epics on its cards, the states of its projects' workflows, and the
	 * types and priorities a card of [shape] can have.
	 */
	@GetMapping("/facets")
	public BoardFacets facets(@PathVariable String id, @RequestParam(required = false) String shape) {
		User user = currentUser.require();
		return reader.facets(id, BoardQuery.shapeOf(shape), user);
	}

	/** The ids of the cards a view holds. */
	public record CardIds(@NotNull @Size(max = BoardReader.MAX_LINK_CARDS) List<String> ids) {
	}

	/**
	 * The connectors between cards of the board a view holds, both ends among them: what a timeline
	 * draws between the cards it has loaded.
	 *
	 * <p>They are a read of their own rather than a part of each page, because a connector joins cards
	 * of different pages, and of the dated and the undated list, which only the view holds together.
	 * A page that carried them would have to read the links of cards it does not hold. The ids travel
	 * in the body, since a timeline holds more cards than a query string carries, and the read changes
	 * nothing.
	 */
	@PostMapping("/links")
	public List<IssueLinkGraphService.LinkEdge> links(@PathVariable String id, @RequestBody @Valid CardIds cards) {
		return reader.links(id, cards.ids(), currentUser.require());
	}
}
