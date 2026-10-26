package com.zaggazach.personaldifficulty.logic;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one place that decides who may put which player on which difficulty (rules R1 to R8 of the 1.4.0 plan).
 * Pure functions over a {@link PlayerRecord} and the server's globally enabled modes; no Minecraft types, so the
 * whole decision space can be enumerated in unit tests.
 *
 * <ul>
 * <li><b>R1</b> self-service change to M: allowed only if the player is not locked, M is globally enabled and
 * M is in the player's allow-list (when one is set); otherwise refused with a specific reason, nothing changes.</li>
 * <li><b>R2</b> an administrator's {@code assign} is allowed for any mode regardless of global policy, allow-list or lock.</li>
 * <li><b>R3, R4, R6</b> a lock only ever blocks self-service; it is data in the record, so it survives death,
 * respawn, reconnect, restart and changes to the global policy, and is independent of the allow-list.</li>
 * <li><b>R5</b> an allow-list is never empty; {@link #planRestrict} makes the administrator choose
 * switch / keep / cancel when the player's current mode would fall outside it.</li>
 * <li><b>R8</b> an operator using the ordinary self-service command is an ordinary player ({@link Actor#OPERATOR_SELF}
 * is decided exactly like {@link Actor#PLAYER}); only {@code assign} overrides.</li>
 * </ul>
 */
public final class SelectionPolicy {
	private SelectionPolicy() {
	}

	/** Who is asking. Only {@link #ADMIN_ASSIGN} (the {@code admin assign} command) can override the player's restrictions. */
	public enum Actor {
		PLAYER,
		/** An operator choosing their own difficulty through the ordinary command. Identical to PLAYER (R8). */
		OPERATOR_SELF,
		ADMIN_ASSIGN
	}

	public enum Verdict {
		ALLOWED,
		/** Self-service request for the mode the player already has; allowed but nothing to do. */
		UNCHANGED,
		/** The player's stored record could not be read (quarantined); nothing may change until an administrator repairs it. */
		REFUSED_INVALID_RECORD,
		REFUSED_LOCKED,
		REFUSED_DISABLED,
		REFUSED_NOT_IN_ALLOW_LIST;

		public boolean refused() {
			return this != ALLOWED && this != UNCHANGED;
		}
	}

	/**
	 * Refusals are reported in a fixed order so the message is deterministic: unreadable record, then lock, then global
	 * policy, then allow-list. The plan only requires "a specific reason" (R1), so the order is a documented choice: it goes from
	 * the condition only an administrator's explicit repair can clear (unreadable data), to the one only an administrator
	 * can clear (R3: a lock), to server policy (also administrator-controlled but the broadest), to the player's own
	 * allow-list, so the player is told the most fundamental blocker first and never a misleading secondary one
	 * (for example "not in your list" for a player who could not change anything anyway).
	 * A request for the player's current mode is still refused if that mode is disabled or outside the allow-list
	 * (a disabled/excluded mode cannot be "newly selected"), and is otherwise {@link Verdict#UNCHANGED}.
	 * An unreadable record blocks every actor, administrators' {@code assign} included: R2's override needs data to override.
	 */
	public static Verdict decide(Actor actor, PlayerRecord record, PersonalDifficultyMode requested, Collection<PersonalDifficultyMode> globallyEnabled) {
		if (record.quarantined()) {
			return Verdict.REFUSED_INVALID_RECORD;
		}
		if (actor == Actor.ADMIN_ASSIGN) {
			return Verdict.ALLOWED;
		}
		if (record.locked()) {
			return Verdict.REFUSED_LOCKED;
		}
		if (!globallyEnabled.contains(requested)) {
			return Verdict.REFUSED_DISABLED;
		}
		if (record.allowed() != null && !record.allowed().contains(requested)) {
			return Verdict.REFUSED_NOT_IN_ALLOW_LIST;
		}
		return record.mode() == requested ? Verdict.UNCHANGED : Verdict.ALLOWED;
	}

	/** The modes a player could select for themselves right now (empty while locked). */
	public static Set<PersonalDifficultyMode> selectable(PlayerRecord record, Collection<PersonalDifficultyMode> globallyEnabled) {
		EnumSet<PersonalDifficultyMode> out = EnumSet.noneOf(PersonalDifficultyMode.class);
		if (record.locked() || record.quarantined()) {
			return out;
		}
		for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
			if (globallyEnabled.contains(m) && (record.allowed() == null || record.allowed().contains(m))) {
				out.add(m);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ restrict (R5)

	/** What to do when {@code restrict} would remove the player's current mode from the list. */
	public enum RestrictFlag {
		/** No flag given: refuse and tell the administrator the options (R5's "cancel"). */
		NONE,
		/** Move the player to {@link #switchTarget}. */
		SWITCH,
		/** Leave the player on their current mode, which is then outside the list (a recorded exception). */
		KEEP
	}

	public enum RestrictStatus {
		APPLY,
		REFUSED_INVALID_RECORD,
		REFUSED_EMPTY,
		NEEDS_CHOICE
	}

	/** @param result the record to store when {@code status == APPLY}, otherwise the unchanged input */
	public record RestrictPlan(RestrictStatus status, PlayerRecord result, boolean modeSwitched) {
	}

	/**
	 * The mode {@code restrict ... switch} moves a player to: NORMAL if it is in the list, otherwise the first listed
	 * mode in canonical order (NORMAL, EASY, HARD, ELITE, PEACEFUL). Deterministic regardless of how the list was typed.
	 */
	public static PersonalDifficultyMode switchTarget(Set<PersonalDifficultyMode> list) {
		if (list.contains(PersonalDifficultyMode.NORMAL)) {
			return PersonalDifficultyMode.NORMAL;
		}
		for (PersonalDifficultyMode m : PersonalDifficultyMode.values()) {
			if (list.contains(m)) {
				return m;
			}
		}
		throw new IllegalArgumentException("an allow-list may never be empty");
	}

	/**
	 * Plans {@code restrict <player> <list> [switch|keep]}. The administrator's action is not subject to global policy:
	 * a switch target may be a globally disabled mode (R2 applies to every administrative assignment). The flag is
	 * irrelevant when the current mode is already in the list. A lock is left exactly as it is (R6).
	 */
	public static RestrictPlan planRestrict(PlayerRecord record, Set<PersonalDifficultyMode> list, RestrictFlag flag, @Nullable UUID admin, long now) {
		if (record.quarantined()) {
			return new RestrictPlan(RestrictStatus.REFUSED_INVALID_RECORD, record, false);
		}
		if (list.isEmpty()) {
			return new RestrictPlan(RestrictStatus.REFUSED_EMPTY, record, false);
		}
		PlayerRecord withList = record.withAllowed(list, admin, now);
		if (list.contains(record.mode())) {
			return new RestrictPlan(RestrictStatus.APPLY, withList, false);
		}
		return switch (flag) {
			case NONE -> new RestrictPlan(RestrictStatus.NEEDS_CHOICE, record, false);
			case KEEP -> new RestrictPlan(RestrictStatus.APPLY, withList, false);
			case SWITCH -> new RestrictPlan(RestrictStatus.APPLY, withList.withMode(switchTarget(list), admin, now), true);
		};
	}
}
