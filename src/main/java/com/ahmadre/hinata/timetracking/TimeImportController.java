package com.ahmadre.hinata.timetracking;

import com.ahmadre.hinata.auth.CurrentUser;
import com.ahmadre.hinata.common.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /api/v1/time/import} — time entries from a CSV file (HIN-93): preview, the rows that did
 * not pass, commit, discard. See {@link TimeImportService}.
 */
@Tag(name = "Time Tracking")
@RestController
@RequestMapping("/api/v1/time/import")
@RequiredArgsConstructor
public class TimeImportController {

	/** Request parameters that map a column: {@code column.DATE=0}. */
	private static final String COLUMN_PREFIX = "column.";

	private final TimeImportService imports;
	private final CurrentUser currentUser;

	@Operation(summary = "Read a CSV file and check every row, writing nothing")
	@PostMapping("/csv")
	public TimeImportService.Preview preview(@RequestPart("file") MultipartFile file,
			@RequestParam(required = false) String userId, @RequestParam Map<String, String> params) {
		return imports.preview(currentUser.require(), file, mapping(params), userId, LocaleContextHolder.getLocale());
	}

	@Operation(summary = "One page of the rows that did not pass")
	@GetMapping("/{importId}/errors")
	public Page<TimeImport.RowError> errors(@PathVariable String importId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return imports.errors(currentUser.require(), importId, page, size);
	}

	@Operation(summary = "Write the rows that passed, all of them or none")
	@PostMapping("/{importId}/commit")
	public TimeImportService.Result commit(@PathVariable String importId) {
		return imports.commit(currentUser.require(), importId);
	}

	@Operation(summary = "Throw a preview away")
	@DeleteMapping("/{importId}")
	public ResponseEntity<Void> discard(@PathVariable String importId) {
		imports.discard(currentUser.require(), importId);
		return ResponseEntity.noContent().build();
	}

	private static Map<TimeImportService.Column, Integer> mapping(Map<String, String> params) {
		Map<TimeImportService.Column, Integer> mapping = new EnumMap<>(TimeImportService.Column.class);
		params.forEach((name, value) -> {
			if (!name.startsWith(COLUMN_PREFIX) || value == null || value.isBlank()) {
				return;
			}
			try {
				mapping.put(TimeImportService.Column.valueOf(name.substring(COLUMN_PREFIX.length())
						.toUpperCase(Locale.ROOT)), Integer.parseInt(value.strip()));
			}
			catch (IllegalArgumentException ex) {
				throw ApiException.badRequest("error.time.import.mapping");
			}
		});
		return mapping;
	}
}
