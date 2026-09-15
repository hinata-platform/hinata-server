package com.ahmadre.hinata.board;

import com.ahmadre.hinata.user.UserController.DirectoryUser;

import java.util.List;

/**
 * What a board's filter and its row of faces can offer, over every card of the board: see
 * {@link BoardFacetsReader}.
 *
 * @param users the people behind [assigneeIds] and [reporterIds]
 */
public record BoardFacets(List<String> assigneeIds, List<String> reporterIds, List<String> labels,
		List<String> states, List<String> types, List<String> priorities, List<BoardRef> epics,
		List<DirectoryUser> users) {
}
