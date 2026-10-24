package com.ahmadre.hinata.moderation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The yearly figures of moderation for administrators (HIN-178): the Commission's template, one
 * CSV per part in a ZIP. Under {@code /api/v1/admin}, so only the platform role reaches it.
 */
@Tag(name = "Moderation")
@RestController
@RequestMapping("/api/v1/admin/moderation/annual-figures")
@RequiredArgsConstructor
public class AnnualFiguresController {

	/** Moderation counts from this year on; nothing before it can have figures. */
	static final int FIRST_YEAR = 2025;

	private final AnnualFigures figures;
	private final Clock clock;

	@Operation(summary = "The years there are figures for, newest first")
	@GetMapping("/years")
	public List<Integer> years() {
		return figures.years();
	}

	@Operation(summary = "The figures of one calendar year in the Commission's template, as a ZIP of CSV files")
	@GetMapping
	public ResponseEntity<byte[]> download(@RequestParam(required = false) Integer year) {
		int current = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).getYear();
		int asked = year == null ? current : Math.clamp(year, FIRST_YEAR, current);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename("transparency-report-" + asked + ".zip").build().toString())
				.contentType(MediaType.parseMediaType("application/zip"))
				.body(figures.zip(asked));
	}
}
