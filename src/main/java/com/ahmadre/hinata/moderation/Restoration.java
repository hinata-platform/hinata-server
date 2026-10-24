package com.ahmadre.hinata.moderation;

import com.ahmadre.hinata.content.RestoreOutcome;

import java.time.Instant;

/**
 * A removal undone (HIN-171), after an appeal or by moderation itself: when, by whom, why, and
 * whether the content came back, had been written anew since, or was gone.
 */
public record Restoration(Instant at, String by, String note, RestoreOutcome outcome) {
}
