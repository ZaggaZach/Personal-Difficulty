package com.zaggazach.personaldifficulty.admin;

import com.zaggazach.personaldifficulty.PdHooks;
import com.zaggazach.personaldifficulty.PdRuntime;
import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.admin.AdminResult.Outcome;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy;
import com.zaggazach.personaldifficulty.store.AvailabilityPolicy;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import com.zaggazach.personaldifficulty.store.PlayerSettings;
import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

/**
 * Every administrative change to a player's record or to the server-wide difficulty policy, in ONE place, used by both the
 * {@code /personaldifficulty admin ...} commands and the admin menus (Phase 3). The logic, the order of the refusals and the message
 * texts are those of the Phase 1/2 commands, moved here unchanged; the commands only parse their arguments and call in.
 *
 * <p>Security: every mutating method first asks {@link AdminActor#authorised()} (a live permission check, never a cached flag) and
 * refuses with {@link Outcome#UNAUTHORISED} before reading or changing anything. A caller's own check (the command's {@code requires},
 * a menu's {@code stillValid} or click handler) is therefore only the first of several; this is the one that guards the mutation.
 *
 * <p>Atomicity: a record is stored with a single {@link PlayerSettings#put} (atomic file write, rolled back on failure); the online
 * mode-change hook runs only after the write succeeded. A failure leaves everything as it was.
 */
public final class AdminService {
	/** Said when a restriction would have an empty allow-list (the command and the menu show the same text). */
	public static final String EMPTY_LIST = "An allow-list cannot be empty. Use 'admin unrestrict <player>' to remove a restriction.";
	static final String UNAUTHORISED = "You do not have permission to administer Personal Difficulty (or no longer have it). Nothing changed.";

	private AdminService() {
	}

	// ------------------------------------------------------------------ shared formatting

	public static String names(Collection<PersonalDifficultyMode> modes) {
		return modes.stream().sorted().map(PersonalDifficultyMode::commandName).collect(Collectors.joining(", "));
	}

	public static String upper(PersonalDifficultyMode m) {
		return m.name();
	}

	private static AdminResult success(AdminActor actor, String message, boolean broadcast, Outcome outcome) {
		actor.feedback().success(message, broadcast);
		return new AdminResult(outcome, message);
	}

	private static AdminResult failure(AdminActor actor, String message, Outcome outcome) {
		actor.feedback().failure(message);
		return new AdminResult(outcome, message);
	}

	private static AdminResult unauthorised(AdminActor actor) {
		return failure(actor, UNAUTHORISED, Outcome.UNAUTHORISED);
	}

	// ------------------------------------------------------------------ player status (read only)

	/** The text of {@code admin status}: everything an administrator needs to know about one player's stored state. */
	public static String statusText(PdRuntime rt, NameAndId p) {
		PlayerRecord rec = rt.settings().record(p.id());
		PersonalDifficultyMode mode = rec.mode();
		Optional<PlayerSettings.Quarantine> invalid = rt.settings().quarantineOf(p.id());
		if (invalid.isPresent()) {
			PlayerSettings.Quarantine q = invalid.get();
			return p.name() + " (" + p.id() + "): INVALID STORED RECORD, kept verbatim in players.json and NOT trusted"
				+ "\n  reason: " + q.reason()
				+ "\n  stored: " + q.rawJson()
				+ "\n  in force: treated as LOCKED on " + upper(q.salvagedMode()) + " (the mode is used only if the stored mode field alone was valid); no allow-list"
				+ "\n  every change for this player is refused until you run '/personaldifficulty admin reset " + p.name() + "' (a verified backup is made first; unknown fields are kept)";
		}
		StringBuilder sb = new StringBuilder(p.name() + " (" + p.id() + "): " + upper(mode));
		if (!rt.availability().isEnabled(mode)) {
			sb.append(" (currently disabled for new selections; kept)");
		}
		sb.append("\n  locked: ").append(rec.locked() ? "YES (players cannot change it themselves)" : "no");
		if (rec.allowed() == null) {
			sb.append("\n  allow-list: none (follows the server policy)");
		} else {
			sb.append("\n  allow-list: ").append(names(rec.allowed()));
			if (rec.isException()) {
				sb.append(" (current mode ").append(upper(mode)).append(" is outside it: kept as an exception)");
			}
		}
		if (rec.by() != null || rec.at() != 0L) {
			sb.append("\n  last administrative change: ").append(rec.by() == null ? "console/command block" : rec.by().toString())
				.append(rec.at() == 0L ? "" : " at " + Instant.ofEpochMilli(rec.at()));
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ player changes

	/**
	 * Stores {@code after} for the player and, if they are online and their mode changed, runs the same mode-change hook as a
	 * self-selection (so entering Peaceful clears harmful effects). Reports failures and changes nothing on error.
	 */
	private static AdminResult applyAdministrative(AdminActor actor, PdRuntime rt, NameAndId target, PlayerRecord before, PlayerRecord after, String success, String verb) {
		try {
			rt.settings().put(target.id(), after);
		} catch (IOException e) {
			return failure(actor, "Could not " + verb + " " + target.name() + ", so nothing was changed: " + e.getMessage(), Outcome.REFUSED);
		}
		if (before.mode() != after.mode()) {
			ServerPlayer online = actor.online(target.id());
			if (online != null) {
				PdHooks.onModeChanged(online, before.mode(), after.mode());
			}
		}
		return success(actor, success, true, Outcome.APPLIED);
	}

	/** An unreadable stored record is never modified by an ordinary change: says so and points to the explicit repair. */
	private static @Nullable AdminResult refuseInvalidRecord(AdminActor actor, PdRuntime rt, NameAndId target) {
		Optional<PlayerSettings.Quarantine> q = rt.settings().quarantineOf(target.id());
		if (q.isEmpty()) {
			return null;
		}
		return failure(actor, "Refused: " + target.name() + " has an invalid stored record (" + q.get().reason() + "), which is kept untouched. Nothing changed. "
			+ "Repair it first with '/personaldifficulty admin reset " + target.name() + "' (a verified backup is made first).", Outcome.REFUSED);
	}

	/** R2: administrators may assign any mode regardless of global policy, allow-list or lock, optionally locking at the same time. */
	public static AdminResult assign(AdminActor actor, PdRuntime rt, NameAndId p, PersonalDifficultyMode mode, boolean lock) {
		if (!actor.authorised()) {
			return unauthorised(actor);
		}
		AdminResult invalid = refuseInvalidRecord(actor, rt, p);
		if (invalid != null) {
			return invalid;
		}
		PlayerRecord before = rt.settings().record(p.id());
		PlayerRecord after = before.withMode(mode, actor.id(), System.currentTimeMillis());
		if (lock) {
			after = after.withLocked(true, actor.id(), System.currentTimeMillis());
		}
		if (after.sameState(before)) {
			return success(actor, p.name() + " is already " + upper(mode) + (lock ? " and locked" : "") + "; nothing changed.", false, Outcome.UNCHANGED);
		}
		SelectionPolicy.Verdict own = SelectionPolicy.decide(SelectionPolicy.Actor.PLAYER, before, mode, rt.availability().enabledModes());
		String override = own.refused() ? " (an administrator override: the player could not have chosen this themselves)" : "";
		String lockNote = after.locked() ? " Locked: only an administrator can change it." : "";
		return applyAdministrative(actor, rt, p, before, after, p.name() + " is now " + upper(mode) + "." + lockNote + override, "assign");
	}

	public static AdminResult setLock(AdminActor actor, PdRuntime rt, NameAndId p, boolean lock) {
		if (!actor.authorised()) {
			return unauthorised(actor);
		}
		AdminResult invalid = refuseInvalidRecord(actor, rt, p);
		if (invalid != null) {
			return invalid;
		}
		PlayerRecord before = rt.settings().record(p.id());
		if (before.locked() == lock) {
			return success(actor, p.name() + " is already " + (lock ? "locked" : "unlocked") + "; nothing changed.", false, Outcome.UNCHANGED);
		}
		PlayerRecord after = before.withLocked(lock, actor.id(), System.currentTimeMillis());
		String msg = lock
			? p.name() + " is now locked on " + upper(before.mode()) + "; only an administrator can change it."
			: p.name() + " is unlocked; they can choose again" + (before.allowed() == null ? "." : " (within their allow-list: " + names(before.allowed()) + ").");
		return applyAdministrative(actor, rt, p, before, after, msg, lock ? "lock" : "unlock");
	}

	public static AdminResult unrestrict(AdminActor actor, PdRuntime rt, NameAndId p) {
		if (!actor.authorised()) {
			return unauthorised(actor);
		}
		AdminResult invalid = refuseInvalidRecord(actor, rt, p);
		if (invalid != null) {
			return invalid;
		}
		PlayerRecord before = rt.settings().record(p.id());
		if (before.allowed() == null) {
			return success(actor, p.name() + " has no allow-list; nothing changed.", false, Outcome.UNCHANGED);
		}
		PlayerRecord after = before.withAllowed(null, actor.id(), System.currentTimeMillis());
		return applyAdministrative(actor, rt, p, before, after, p.name() + " is no longer restricted to an allow-list; the server policy applies again" + (before.locked() ? " (still locked)." : "."), "unrestrict");
	}

	/** What {@link #restrict} would do for the target's CURRENT state, without changing anything (a menu uses it to decide whether to ask). */
	public static SelectionPolicy.RestrictPlan previewRestrict(PdRuntime rt, NameAndId p, Set<PersonalDifficultyMode> list, SelectionPolicy.RestrictFlag flag, java.util.UUID admin) {
		return SelectionPolicy.planRestrict(rt.settings().record(p.id()), list, flag, admin, System.currentTimeMillis());
	}

	/** An allow-list for one player. {@code flag} resolves R5 when the list would exclude the player's current mode. */
	public static AdminResult restrict(AdminActor actor, PdRuntime rt, NameAndId p, Set<PersonalDifficultyMode> list, SelectionPolicy.RestrictFlag flag) {
		if (!actor.authorised()) {
			return unauthorised(actor);
		}
		AdminResult invalid = refuseInvalidRecord(actor, rt, p);
		if (invalid != null) {
			return invalid;
		}
		PlayerRecord before = rt.settings().record(p.id());
		SelectionPolicy.RestrictPlan plan = SelectionPolicy.planRestrict(before, list, flag, actor.id(), System.currentTimeMillis());
		switch (plan.status()) {
			case REFUSED_INVALID_RECORD -> {
				AdminResult again = refuseInvalidRecord(actor, rt, p);
				return again != null ? again : new AdminResult(Outcome.REFUSED, "");
			}
			case REFUSED_EMPTY -> {
				return failure(actor, "An allow-list cannot be empty. Nothing changed.", Outcome.REFUSED);
			}
			case NEEDS_CHOICE -> {
				return failure(actor,
					"Refused: " + p.name() + " is on " + upper(before.mode()) + ", which is not in the new list (" + names(list) + "). Nothing changed. Re-run with 'switch' (moves them to "
						+ upper(SelectionPolicy.switchTarget(list)) + ") or 'keep' (they stay on " + upper(before.mode()) + " as an exception outside the list).",
					Outcome.NEEDS_CHOICE);
			}
			case APPLY -> {
				PlayerRecord after = plan.result();
				if (after.sameState(before)) {
					return success(actor, p.name() + " is already restricted to " + names(list) + "; nothing changed.", false, Outcome.UNCHANGED);
				}
				String how = plan.modeSwitched() ? " They were moved to " + upper(after.mode()) + "."
					: after.isException() ? " They stay on " + upper(after.mode()) + " as an exception outside the list; once they switch away they cannot switch back." : "";
				String lockNote = after.locked() ? " They are still locked." : "";
				return applyAdministrative(actor, rt, p, before, after, p.name() + " is now restricted to " + names(list) + "." + how + lockNote, "restrict");
			}
		}
		return new AdminResult(Outcome.REFUSED, "");
	}

	/**
	 * R7: Normal, lock cleared, allow-list cleared (unknown stored fields are kept). For an unreadable stored record this is the
	 * explicit recovery path: a verified backup is made first, then the record is replaced.
	 */
	public static AdminResult reset(AdminActor actor, PdRuntime rt, NameAndId p) {
		if (!actor.authorised()) {
			return unauthorised(actor);
		}
		if (rt.settings().isQuarantined(p.id())) {
			try {
				PlayerSettings.Repair repair = rt.settings().repairQuarantined(p.id());
				if (repair != null) {
					String backup = repair.backup() == null ? "no file to back up" : "backup: " + repair.backup().getFileName();
					String kept = repair.keptUnknownFields().isEmpty() ? "" : " Unknown fields kept: " + repair.keptUnknownFields() + ".";
					return success(actor, p.name() + ": the invalid stored record was replaced by a default one (NORMAL, unlocked, no allow-list). "
						+ "The old entry, including its invalid values, is in the verified " + backup + "." + kept, true, Outcome.APPLIED);
				}
			} catch (IOException e) {
				return failure(actor, "Could not repair " + p.name() + ", so nothing was changed: " + e.getMessage(), Outcome.REFUSED);
			}
			return new AdminResult(Outcome.UNCHANGED, "");
		}
		if (!rt.settings().hasExplicit(p.id())) {
			return success(actor, p.name() + " (" + p.id() + ") has no stored selection, lock or allow-list; already NORMAL. Nothing changed.", false, Outcome.UNCHANGED);
		}
		PlayerRecord before = rt.settings().record(p.id());
		PlayerRecord after = new PlayerRecord(PersonalDifficultyMode.NORMAL, false, null, null, 0L, before.extras());
		return applyAdministrative(actor, rt, p, before, after, p.name() + " reset to NORMAL (was " + upper(before.mode())
			+ (before.locked() ? ", lock cleared" : "") + (before.allowed() != null ? ", allow-list cleared" : "") + ").", "reset");
	}

	// ------------------------------------------------------------------ server-wide policy

	/** {@code admin enable <mode>} / {@code admin disable <mode>}. REFUSED covers the last-enabled refusal and a failed save. */
	public static AdminResult setEnabled(AdminActor actor, PdRuntime rt, PersonalDifficultyMode mode, boolean enable) {
		if (!actor.authorised()) {
			return unauthorised(actor);
		}
		AvailabilityPolicy policy = rt.availability();
		try {
			AvailabilityPolicy.Change change = policy.setEnabled(mode, enable);
			switch (change) {
				case UNCHANGED -> {
					return success(actor, upper(mode) + " is already " + (enable ? "enabled" : "disabled") + "; nothing changed.", false, Outcome.UNCHANGED);
				}
				case REFUSED_LAST_ENABLED -> {
					return failure(actor, "Refused: " + upper(mode) + " is the last enabled difficulty, and at least one must stay selectable. Nothing changed.", Outcome.REFUSED);
				}
				case CHANGED -> {
					long using = rt.settings().countUsing(mode);
					String tail = enable
						? " It can be selected again now (no restart needed)."
						: " Players cannot newly select it; the " + using + " saved selection(s) using it are kept, and those players keep playing on it until they switch away.";
					return success(actor, upper(mode) + " is now " + (enable ? "ENABLED" : "DISABLED") + "." + tail, true, Outcome.APPLIED);
				}
			}
			return new AdminResult(Outcome.UNCHANGED, "");
		} catch (IOException e) {
			return failure(actor, "Could not save the availability policy, so nothing was changed: " + e.getMessage(), Outcome.REFUSED);
		}
	}

	/** {@code admin enable all}. */
	public static AdminResult enableAll(AdminActor actor, PdRuntime rt) {
		if (!actor.authorised()) {
			return unauthorised(actor);
		}
		AvailabilityPolicy policy = rt.availability();
		try {
			AvailabilityPolicy.Change change = policy.enableAll();
			if (change == AvailabilityPolicy.Change.UNCHANGED) {
				return success(actor, "Every difficulty is already enabled; nothing changed.", false, Outcome.UNCHANGED);
			}
			return success(actor, "All difficulties are enabled again. Players can select: " + names(policy.enabledModes()) + ".", true, Outcome.APPLIED);
		} catch (IOException e) {
			return failure(actor, "Could not save the availability policy, so nothing was changed: " + e.getMessage(), Outcome.REFUSED);
		}
	}

	// ------------------------------------------------------------------ settings (admin set / unset), used by the Settings menu

	/**
	 * {@code admin set <key> <value>} for an actor: authority first, then exactly the runtime's strict set (validate, write, publish).
	 * A change is broadcast to operators like the command; "nothing to store" goes to the actor only; a refusal changes nothing.
	 */
	public static AdminResult setSetting(AdminActor actor, PdRuntime rt, String key, String valueText) {
		if (!actor.authorised()) {
			return unauthorised(actor);
		}
		return report(actor, rt.setValue(key, valueText));
	}

	/** {@code admin unset <key>} for an actor. */
	public static AdminResult unsetSetting(AdminActor actor, PdRuntime rt, String key) {
		if (!actor.authorised()) {
			return unauthorised(actor);
		}
		return report(actor, rt.unsetValue(key));
	}

	/** The same reporting as the commands' {@code report}: changes broadcast, no-ops to the sender, refusals as failures. */
	private static AdminResult report(AdminActor actor, com.zaggazach.personaldifficulty.config.SetResult r) {
		return switch (r.kind()) {
			case APPLIED -> success(actor, r.message(), true, Outcome.APPLIED);
			case UNCHANGED -> success(actor, r.message(), false, Outcome.UNCHANGED);
			case REFUSED -> failure(actor, r.message(), Outcome.REFUSED);
		};
	}
}
