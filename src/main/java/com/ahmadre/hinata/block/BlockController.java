package com.ahmadre.hinata.block;

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

import java.util.List;

/** One's own blocks (HIN-26, HIN-168). */
@Tag(name = "Account")
@RestController
@RequestMapping("/api/v1/me/blocks")
@RequiredArgsConstructor
public class BlockController {

	private final BlockService blocks;
	private final CurrentUser currentUser;

	@Operation(summary = "Block a person")
	@PutMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void block(@PathVariable String userId) {
		blocks.block(currentUser.require(), userId);
	}

	@Operation(summary = "Lift a block")
	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unblock(@PathVariable String userId) {
		blocks.unblock(currentUser.require(), userId);
	}

	@Operation(summary = "The people one blocks, newest first")
	@GetMapping
	public BlockService.BlockPage list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return blocks.list(currentUser.require(), page, size);
	}

	@Operation(summary = "Every id one blocks, for folding their posts away")
	@GetMapping("/ids")
	public List<String> ids() {
		return blocks.blockedIds(currentUser.require());
	}
}
