package com.ahmadre.hinata.board;

/** The cards of one workflow state: how many, whether resolved, and their story points. */
public record BoardStateSummary(String state, boolean resolved, long count, long points) {
}
