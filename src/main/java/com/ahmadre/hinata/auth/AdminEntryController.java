package com.ahmadre.hinata.auth;

import com.ahmadre.hinata.config.TrustedForwardedHeaderFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What the admin area shows at the top of moderation (HIN-175): every way in that lets new
 * accounts in without approval, and whether requests come through a proxy this server does not
 * trust, in which case every client counts as the proxy's address and the limits per address
 * count all of them together. Administrators only, by the path.
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/moderation/entry")
@RequiredArgsConstructor
class AdminEntryController {

	private final AccountAdmission admission;
	private final TrustedForwardedHeaderFilter forwarded;
	private final Clock clock;

	record Entry(List<AccountAdmission.OpenWay> openWays, boolean proxyNotTrusted) {
	}

	@Operation(summary = "Ways in without approval, and whether a proxy in front is not trusted")
	@GetMapping
	Entry entry() {
		Instant ignored = forwarded.ignoredAt();
		return new Entry(admission.openWays(),
				ignored != null && ignored.isAfter(clock.instant().minus(Duration.ofDays(1))));
	}
}
