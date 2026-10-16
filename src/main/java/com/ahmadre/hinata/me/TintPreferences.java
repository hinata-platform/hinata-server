package com.ahmadre.hinata.me;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The tint this person gives the app: a hue and how strongly it colours the
 * accent and the glass chrome, as in iOS 27's "Customize" sheet (HIN-150).
 * Embedded in the {@code users} document, next to {@link TimePreferences}.
 *
 * <p>Here rather than in {@code ServerSettings} because it is the person's own
 * taste; an administrator has no say in it. One tint only: the rail and every
 * dark step are derived from the same hue by the client.
 *
 * <p>Fields are {@link Integer} and {@link String}, not primitives and not
 * enums, on purpose. A document written before the field existed reads back as
 * {@code null}, and {@code null} means "use the default"; {@code 0} is a real
 * value here (hue 0 is red, level 0 is clear glass) and must never be replaced
 * by a default — which is why {@link TimePreferences}'s clamp with its zero
 * special case is not used. The style is stored as a string and parsed on
 * read so that an unknown value in the database (after a rollback, say) falls
 * back to the default instead of failing the mapping of the whole user and
 * locking the account out.
 *
 * <p>The request validators reject new input outside the bounds; {@link #sanitized()}
 * repairs what is already stored.
 */
@Data
@NoArgsConstructor
public class TintPreferences {

	public enum TintStyle { DEFAULT, TINTED }

	public static final int MAX_HUE = 359;
	public static final int MAX_LEVEL = 100;

	/** The measured oklch hue of the brand honey {@code #D9A032}. */
	public static final int DEFAULT_HUE = 79;
	public static final int DEFAULT_LEVEL = 100;

	/** {@link TintStyle} name; read through {@link #getStyle()}. */
	@JsonIgnore
	private String style;

	/** oklch hue in degrees, 0 to 359. */
	private Integer hue;

	/** How strong the tint is, 0 (clear glass, pale accent) to 100. */
	private Integer level;

	public static TintPreferences defaults() {
		return new TintPreferences();
	}

	/** What a stored value reads as: the defaults for a document without one, sanitized otherwise. */
	public static TintPreferences resolve(TintPreferences stored) {
		return (stored == null ? defaults() : stored).sanitized();
	}

	@JsonGetter("style")
	public TintStyle getStyle() {
		if (style == null) {
			return TintStyle.DEFAULT;
		}
		try {
			return TintStyle.valueOf(style);
		} catch (IllegalArgumentException unknown) {
			return TintStyle.DEFAULT;
		}
	}

	public void setStyle(TintStyle style) {
		this.style = style == null ? null : style.name();
	}

	public void setStyle(String style) {
		this.style = style;
	}

	public int getHue() {
		return hue == null ? DEFAULT_HUE : hue;
	}

	public int getLevel() {
		return level == null ? DEFAULT_LEVEL : level;
	}

	/** A copy with every value inside its bounds and the style resolved. */
	public TintPreferences sanitized() {
		TintPreferences copy = new TintPreferences();
		copy.setStyle(getStyle());
		copy.hue = Math.clamp(getHue(), 0, MAX_HUE);
		copy.level = Math.clamp(getLevel(), 0, MAX_LEVEL);
		return copy;
	}
}
