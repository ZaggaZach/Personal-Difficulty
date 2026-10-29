package com.zaggazach.personaldifficulty;

import java.util.Locale;
import java.util.Optional;

/**
 * A player's personal difficulty. {@link #NORMAL} is the default for everyone who has never chosen:
 * since 1.1.0 there is no separate "inherit" state.
 *
 * <p>Declaration order is the display order everywhere (menus, command suggestions, messages), from easiest to hardest:
 * PEACEFUL, EASY, NORMAL, HARD, ELITE (since 1.3.1). Stored data uses names, never ordinals, so reordering is safe; nothing may
 * depend on the ordinal.
 */
public enum PersonalDifficultyMode {
	PEACEFUL,
	EASY,
	NORMAL,
	HARD,
	ELITE;

	public static final PersonalDifficultyMode DEFAULT = NORMAL;

	public String commandName() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** True if this mode scales damage/hunger through a configurable {@code PdConfig.Profile}. */
	public boolean hasProfile() {
		return this == EASY || this == HARD || this == ELITE;
	}

	/** Parses a player-facing name. "inherit" (removed in 1.1.0) is deliberately NOT accepted here. */
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

	/**
	 * Parses a value read from a persisted 1.0.0 or 1.1.0 file. The retired 1.0.0 value "INHERIT" maps
	 * to {@link #NORMAL}, which is what it always behaved like.
	 */
	public static Optional<PersonalDifficultyMode> parseStored(String text) {
		if (text != null && text.trim().equalsIgnoreCase("INHERIT")) {
			return Optional.of(NORMAL);
		}
		return parse(text);
	}
}
