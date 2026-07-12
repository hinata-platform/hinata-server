package com.ahmadre.hinata.mailingest;

import com.ahmadre.hinata.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Reply-by-e-mail to the original sender of an email-to-ticket issue. */
@Tag(name = "Issue e-mail reply")
@RestController
@RequestMapping("/api/v1/issues/{issueId}/reply-email")
@RequiredArgsConstructor
public class IssueEmailReplyController {

	private final IssueEmailReplyService replyService;
	private final CurrentUser currentUser;

	/**
	 * Sends the reply. Attachments are referenced by id and must already exist on
	 * the issue (uploaded through the normal attachment endpoint first).
	 */
	public record ReplyRequest(@NotBlank String subject, @NotBlank String body,
			List<String> attachmentIds) {}

	@Operation(summary = "Reply by e-mail to the issue's original sender")
	@PostMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reply(@PathVariable String issueId, @RequestBody ReplyRequest request) {
		replyService.reply(issueId, currentUser.require(), request.subject(), request.body(),
				request.attachmentIds());
	}
}
