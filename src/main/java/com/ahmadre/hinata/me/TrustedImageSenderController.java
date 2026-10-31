package com.ahmadre.hinata.me;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The sender domains one loads external mail images from without being asked (HIN-181). */
@Tag(name = "Account")
@RestController
@RequestMapping("/api/v1/me/trusted-image-senders")
@RequiredArgsConstructor
public class TrustedImageSenderController {

	private final TrustedImageSenderService senders;
	private final CurrentUser currentUser;

	@Operation(summary = "The sender domains one trusts with images, newest first")
	@GetMapping
	public TrustedImageSenderService.TrustedSenderPage list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return senders.list(currentUser.require(), page, size);
	}

	@Operation(summary = "Always load images from a sender domain")
	@PutMapping("/{domain}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void trust(@PathVariable String domain) {
		senders.trust(currentUser.require(), domain);
	}

	@Operation(summary = "Ask again before loading images from a sender domain")
	@DeleteMapping("/{domain}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void distrust(@PathVariable String domain) {
		senders.distrust(currentUser.require(), domain);
	}
}
