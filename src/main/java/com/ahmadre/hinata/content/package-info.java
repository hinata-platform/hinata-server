/**
 * The vocabulary of moderated content (HIN-26): what a piece of content is, how it is named,
 * removed in place, restored and scrubbed from the places that copied it.
 *
 * <p>Part of the core, not of the moderation module. {@code issue}, {@code article}, {@code user}
 * and {@code notification} implement {@link com.ahmadre.hinata.content.ContentSurface} and
 * {@link com.ahmadre.hinata.content.ContentCopies}; {@code moderation} calls them and implements
 * {@link com.ahmadre.hinata.content.Quarantine} and {@link com.ahmadre.hinata.content.EvidenceHold}.
 * The package knows none of the surfaces, so no cycle can form between them and the module that
 * reads them ({@code ModuleBoundaryTest}). Of the people it knows only
 * {@link com.ahmadre.hinata.user.User}, the person a surface is read for; that class carries a
 * {@link com.ahmadre.hinata.content.ContentMark} in turn, the one cycle accepted here.
 */
package com.ahmadre.hinata.content;
