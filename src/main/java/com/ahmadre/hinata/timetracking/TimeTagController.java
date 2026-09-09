package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import com.ahmadre.hinata.user.User;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

/**
 * The tag catalogue over HTTP.
 *
 * <p>Reading is open to every member: the picker needs it, and a tag is a word,
 * not somebody's data. Coining one follows {@code limitTagAccess}; renaming or
 * deleting one is an administrator's act, always. The service decides both, not
 * this controller, so the MCP tools and the admin screen cannot end up with two
 * opinions — the one thing decided here is that the usage count, which costs a
 * query per row, is only handed to the screen that acts on it.
 *
 * <p>Behind {@link AdvancedTimeTrackingGate} like everything under
 * {@code /api/v1/time}: with the module off the catalogue does not exist.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/tags")
@RequiredArgsConstructor
public class TimeTagController {

	private final TimeTagService tags;
	private final CurrentUser currentUser;

	/**
	 * A tag on the wire. {@code entries} is filled only where it was counted —
	 * the admin list, and the answer to a rename or a delete — because counting
	 * it reads the entries collection and the picker has no use for it.
	 */
	public record TimeTagResponse(String id, String name, int hue, Long entries) {

		static TimeTagResponse from(TimeTag tag) {
			return new TimeTagResponse(tag.getId(), tag.getName(), tag.getHue(), null);
		}

		static TimeTagResponse from(TimeTag tag, long entries) {
			return new TimeTagResponse(tag.getId(), tag.getName(), tag.getHue(), entries);
		}
	}

	/** A new tag. The hue is optional: without one it is derived from the word. */
	public record TimeTagRequest(
			@NotBlank @Size(max = TimeTag.MAX_NAME) String name,
			@Min(0) @Max(359) Integer hue) {
	}

	/** An edit. Both fields are optional; what is absent is left alone. */
	public record TimeTagPatchRequest(
			@Size(max = TimeTag.MAX_NAME) String name,
			@Min(0) @Max(359) Integer hue) {
	}

	@GetMapping
	public Page<TimeTagResponse> list(
			@RequestParam(required = false) @Size(max = TimeTag.MAX_NAME) String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size,
			@RequestParam(defaultValue = "false") boolean withUsage) {
		User user = currentUser.require();
		Page<TimeTag> found = tags.page(q, page, size);
		if (!withUsage) {
			return found.map(TimeTagResponse::from);
		}
		// One indexed count per row, and only for the screen that needs it. The
		// number is what makes "delete this tag" a different decision from
		// "delete this tag from 1 204 entries", and the only person who can act
		// on it is the administrator who may rename or delete one. Refused rather
		// than quietly ignored for anybody else, so the contract is visible.
		if (!user.isAdmin()) {
			throw ApiException.forbidden("error.time.tagsRestricted");
		}
		return found.map(tag -> TimeTagResponse.from(tag, tags.usage(tag)));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TimeTagResponse create(@RequestBody @Valid TimeTagRequest request) {
		User user = currentUser.require();
		return TimeTagResponse.from(tags.create(request.name(), request.hue(), user));
	}

	/** Renames or recolours a tag; the answer says how many entries were rewritten. */
	@PatchMapping("/{id}")
	public TimeTagResponse update(@PathVariable String id,
			@RequestBody @Valid TimeTagPatchRequest request) {
		User user = currentUser.require();
		TimeTagService.TagUsage result = tags.update(id, request.name(), request.hue(), user);
		return TimeTagResponse.from(result.tag(), result.entries());
	}

	/** Removes a tag and takes it off every entry; the entries themselves stay. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		tags.delete(id, currentUser.require());
	}
}
