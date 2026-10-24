package com.zaggazach.personaldifficulty;

import java.util.Locale;
import java.util.Optional;

/**
 * A player's personal difficulty selection.
 *
 * <p>{@link #INHERIT} (never configured) and {@link #NORMAL} (explicit opt-in to vanilla) are
 * deliberately distinct states even though both apply no modifiers today.
 */
public enum PersonalDifficultyMode {
	INHERIT,
	NORMAL,
	EASY,
	HARD;

	public String commandName() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** True if this mode applies a configurable profile (damage/hunger/aim modifiers). */
	public boolean hasProfile() {
		return this == EASY || this == HARD;
	}

	public static Optional<PersonalDifficultyMode> parse(String text) {
		if (text == null) {
			return Optional.empty();
		}
		String t = text.trim().toUpperCase(Locale.ROOT);
		for (PersonalDifficultyMode mode : values()) {
			if (mode.name().equals(t)) {
				return Optional.of(mode);
			}
		}
		return Optional.empty();
	}
}
