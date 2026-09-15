package com.ahmadre.hinata.board;

/**
 * The in-app address of a board, as the server hands it to clients: in search hits and in the
 * notifications about a sprint.
 *
 * <p>{@code /boards/{id}}, although the app now keeps a board at {@code /board/{id}} under the
 * overview (HIN-114). The published app knows only the older address and follows a link exactly
 * as it is given, so the server keeps writing that one and the current app forwards it.
 */
public final class BoardLinks {

	/** Where a link without a board leads: the overview of all boards. */
	private static final String OVERVIEW = "/board";

	private BoardLinks() {
	}

	/** The board's own page, or the overview when there is no board to name. */
	public static String of(String boardId) {
		return boardId == null || boardId.isBlank() ? OVERVIEW : "/boards/" + boardId;
	}
}
