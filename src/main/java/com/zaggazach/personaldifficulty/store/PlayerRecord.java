package com.zaggazach.personaldifficulty.store;

import com.google.gson.JsonObject;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One player's stored state (format v2): the chosen mode, an optional administrator lock, an optional
 * allow-list and who/when last changed it administratively. Immutable; {@link PlayerSettings} swaps whole records.
 *
 * <p>{@code allowed == null} means "follow the server policy". A non-null allow-list is never empty (R5);
 * the constructor enforces it. It need not contain {@code mode}: that is a recorded exception (an
 * administrator chose to keep the player's current mode while restricting the list).
 *
 * <p>{@code extras} holds fields this version does not understand; they are written back untouched.
 *
 * <p>{@code quarantined} marks the <em>in-memory view</em> of a player whose stored entry could not be read (an invalid
 * known field, an unknown mode, several entries for one player). The raw entry is kept verbatim by
 * {@link PlayerSettings}; this view is always {@code locked} so nothing can treat the player as unrestricted, and it is
 * never written to disk.
 */
public record PlayerRecord(
	PersonalDifficultyMode mode,
	boolean locked,
	@Nullable Set<PersonalDifficultyMode> allowed,
	@Nullable UUID by,
	long at,
	JsonObject extras,
	boolean quarantined
) {
	public static final PlayerRecord DEFAULT = new PlayerRecord(PersonalDifficultyMode.DEFAULT, false, null, null, 0L, new JsonObject());

	public PlayerRecord {
		if (allowed != null) {
			if (allowed.isEmpty()) {
				throw new IllegalArgumentException("an allow-list may never be empty");
			}
			allowed = Set.copyOf(EnumSet.copyOf(allowed));
		}
		extras = extras.deepCopy();
	}

	/** An ordinary (not quarantined) record. */
	public PlayerRecord(PersonalDifficultyMode mode, boolean locked, @Nullable Set<PersonalDifficultyMode> allowed, @Nullable UUID by, long at, JsonObject extras) {
		this(mode, locked, allowed, by, at, extras, false);
	}

	/** The fail-closed view of an unreadable entry: locked, with only the (validated) salvaged mode. */
	public static PlayerRecord quarantinedView(PersonalDifficultyMode salvagedMode) {
		return new PlayerRecord(salvagedMode, true, null, null, 0L, new JsonObject(), true);
	}

	/** True if nothing needs storing for this player (no selection, lock, allow-list or unknown fields). */
	public boolean isDefault() {
		return mode == PersonalDifficultyMode.DEFAULT && !locked && allowed == null && extras.isEmpty() && !quarantined;
	}

	public PlayerRecord withMode(PersonalDifficultyMode newMode) {
		return new PlayerRecord(newMode, locked, allowed, by, at, extras, quarantined);
	}

	public PlayerRecord withMode(PersonalDifficultyMode newMode, @Nullable UUID admin, long when) {
		return new PlayerRecord(newMode, locked, allowed, admin, when, extras, quarantined);
	}

	public PlayerRecord withLocked(boolean lock, @Nullable UUID admin, long when) {
		return new PlayerRecord(mode, lock, allowed, admin, when, extras, quarantined);
	}

	public PlayerRecord withAllowed(@Nullable Set<PersonalDifficultyMode> list, @Nullable UUID admin, long when) {
		return new PlayerRecord(mode, locked, list, admin, when, extras, quarantined);
	}

	/** True if the player's current mode is outside their allow-list (R5 "recorded exception"). */
	public boolean isException() {
		return allowed != null && !allowed.contains(mode);
	}

	/** Equality on the stored meaning, ignoring who/when (an unchanged edit must not rewrite the file). */
	public boolean sameState(PlayerRecord other) {
		return mode == other.mode && locked == other.locked && quarantined == other.quarantined && Objects.equals(allowed, other.allowed) && extras.equals(other.extras);
	}
}
