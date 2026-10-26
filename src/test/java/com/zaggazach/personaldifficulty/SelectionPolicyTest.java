package com.zaggazach.personaldifficulty;

import static com.zaggazach.personaldifficulty.PersonalDifficultyMode.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.Actor;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.RestrictFlag;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.RestrictPlan;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.RestrictStatus;
import com.zaggazach.personaldifficulty.logic.SelectionPolicy.Verdict;
import com.zaggazach.personaldifficulty.store.PlayerRecord;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Rules R1 to R8 over the complete decision space, generated rather than hand-picked: every actor, every possible set of
 * globally enabled modes (at least one is always enabled), no allow-list or every non-empty allow-list, locked or not,
 * every current mode and every requested mode. The expected result is computed from the plain-language rules, not from
 * the code under test.
 */
class SelectionPolicyTest {
	private static final PersonalDifficultyMode[] MODES = PersonalDifficultyMode.values();
	private static final UUID ADMIN = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

	/** All 2^5 - 1 non-empty subsets, in a fixed order. */
	private static List<Set<PersonalDifficultyMode>> nonEmptySubsets() {
		List<Set<PersonalDifficultyMode>> out = new ArrayList<>();
		for (int mask = 1; mask < (1 << MODES.length); mask++) {
			EnumSet<PersonalDifficultyMode> s = EnumSet.noneOf(PersonalDifficultyMode.class);
			for (int i = 0; i < MODES.length; i++) {
				if ((mask & (1 << i)) != 0) {
					s.add(MODES[i]);
				}
			}
			out.add(s);
		}
		return out;
	}

	private static PlayerRecord record(PersonalDifficultyMode current, boolean locked, Set<PersonalDifficultyMode> allowed) {
		return new PlayerRecord(current, locked, allowed, null, 0L, new JsonObject());
	}

	/** The rules as sentences. Returns the first reason that applies, in the documented order, or null if the change is permitted. */
	private static Verdict expectedRefusal(boolean locked, Set<PersonalDifficultyMode> enabled, Set<PersonalDifficultyMode> allowed, PersonalDifficultyMode requested) {
		if (locked) {
			return Verdict.REFUSED_LOCKED; // R3: a locked player cannot change their own difficulty by any path
		}
		if (!enabled.contains(requested)) {
			return Verdict.REFUSED_DISABLED; // R1: M must be globally enabled
		}
		if (allowed != null && !allowed.contains(requested)) {
			return Verdict.REFUSED_NOT_IN_ALLOW_LIST; // R1: M must be in the allow-list when one is set
		}
		return null;
	}

	// ------------------------------------------------------------------ hand-written table (independent of the production logic)

	private static Set<PersonalDifficultyMode> set(String csv) {
		if (csv.equals("-")) {
			return null; // no allow-list
		}
		if (csv.equals("all")) {
			return EnumSet.allOf(PersonalDifficultyMode.class);
		}
		EnumSet<PersonalDifficultyMode> out = EnumSet.noneOf(PersonalDifficultyMode.class);
		for (String part : csv.split(",")) {
			out.add(PersonalDifficultyMode.valueOf(part.trim()));
		}
		return out;
	}

	/**
	 * One row = one situation written out by hand from the plan's rule text, with the outcome a person reading R1 to R8 expects.
	 * Columns: actor, locked, globally enabled, allow-list ("-" none), current mode, requested mode, expected verdict.
	 *
	 * <p>Refusal ORDER is not fixed by the plan (R1: "refused with a specific reason"), so the rows marked ORDER document the
	 * chosen order: unreadable record, then lock, then global policy, then allow-list. Reason: a player is told the most
	 * fundamental blocker first (a lock overrides everything; telling a locked player their choice is "not in your list" would
	 * be misleading because removing that restriction would not let them change anything), and the same order is used in
	 * every user-facing message (see PlayerPolicyGameTests#policy_refusalReasonsFollowTheDocumentedOrder_inMessages).
	 */
	private static final String[][] TABLE = {
		// R1: self-service needs: not locked, globally enabled, in the allow-list when one is set
		{"PLAYER", "no", "all", "-", "NORMAL", "HARD", "ALLOWED"},
		{"PLAYER", "no", "all", "-", "HARD", "HARD", "UNCHANGED"},
		{"PLAYER", "no", "NORMAL,EASY", "-", "NORMAL", "ELITE", "REFUSED_DISABLED"},
		{"PLAYER", "no", "all", "NORMAL,EASY", "NORMAL", "HARD", "REFUSED_NOT_IN_ALLOW_LIST"},
		{"PLAYER", "no", "all", "NORMAL,EASY", "NORMAL", "EASY", "ALLOWED"},
		// R1 conflicts between global policy and allow-list
		{"PLAYER", "no", "NORMAL,HARD", "EASY,HARD", "NORMAL", "EASY", "REFUSED_DISABLED"},        // allowed for the player, but the server disabled it
		{"PLAYER", "no", "NORMAL,HARD", "EASY", "NORMAL", "HARD", "REFUSED_NOT_IN_ALLOW_LIST"},    // enabled by the server, but not allowed for the player
		{"PLAYER", "no", "NORMAL", "HARD", "NORMAL", "EASY", "REFUSED_DISABLED"},                  // ORDER: both fail, global policy is reported first
		{"PLAYER", "no", "NORMAL,EASY", "EASY", "NORMAL", "EASY", "ALLOWED"},                      // both agree
		// R3: a locked player cannot change their own difficulty by any path (and the lock beats the other two reasons)
		{"PLAYER", "yes", "all", "-", "NORMAL", "HARD", "REFUSED_LOCKED"},
		{"PLAYER", "yes", "all", "-", "HARD", "HARD", "REFUSED_LOCKED"},                           // even their own current mode
		{"PLAYER", "yes", "NORMAL", "EASY", "NORMAL", "ELITE", "REFUSED_LOCKED"},                  // ORDER: lock, disabled and not-in-list all apply: lock first
		{"PLAYER", "yes", "NORMAL", "EASY", "ELITE", "NORMAL", "REFUSED_LOCKED"},
		// R6: lock and allow-list are independent
		{"PLAYER", "yes", "all", "NORMAL", "NORMAL", "NORMAL", "REFUSED_LOCKED"},                  // allow-list present, still locked
		{"PLAYER", "no", "all", "NORMAL", "NORMAL", "NORMAL", "UNCHANGED"},                        // unlocked later: the allow-list applies
		// R5: a kept exception (current mode outside the list) can not be re-selected, a listed mode can be chosen
		{"PLAYER", "no", "all", "NORMAL", "ELITE", "ELITE", "REFUSED_NOT_IN_ALLOW_LIST"},
		{"PLAYER", "no", "all", "NORMAL", "ELITE", "NORMAL", "ALLOWED"},
		// R4: disabling a mode never changes a saved mode, but nobody can newly select it (including its current users)
		{"PLAYER", "no", "NORMAL", "-", "ELITE", "ELITE", "REFUSED_DISABLED"},
		{"PLAYER", "no", "NORMAL", "-", "ELITE", "NORMAL", "ALLOWED"},
		// R8: an operator using the ordinary command is an ordinary player (the same rows as PLAYER)
		{"OPERATOR_SELF", "no", "all", "-", "NORMAL", "HARD", "ALLOWED"},
		{"OPERATOR_SELF", "no", "NORMAL,EASY", "-", "NORMAL", "ELITE", "REFUSED_DISABLED"},
		{"OPERATOR_SELF", "no", "all", "NORMAL,EASY", "NORMAL", "HARD", "REFUSED_NOT_IN_ALLOW_LIST"},
		{"OPERATOR_SELF", "yes", "all", "-", "NORMAL", "HARD", "REFUSED_LOCKED"},
		{"OPERATOR_SELF", "yes", "NORMAL", "EASY", "NORMAL", "ELITE", "REFUSED_LOCKED"},
		// R2: an administrator's assign overrides global policy, allow-list and lock, for any player
		{"ADMIN_ASSIGN", "no", "all", "-", "NORMAL", "ELITE", "ALLOWED"},
		{"ADMIN_ASSIGN", "yes", "NORMAL", "EASY", "NORMAL", "ELITE", "ALLOWED"},                   // locked + disabled + not allowed: still allowed
		{"ADMIN_ASSIGN", "yes", "NORMAL", "EASY", "ELITE", "PEACEFUL", "ALLOWED"},
		{"ADMIN_ASSIGN", "no", "all", "-", "ELITE", "ELITE", "ALLOWED"},                           // same mode: still a valid assign (it may add the lock)
	};

	@Test
	void handWrittenTableOfLiteralExpectedOutcomes() {
		for (String[] row : TABLE) {
			Actor actor = Actor.valueOf(row[0]);
			PlayerRecord rec = record(PersonalDifficultyMode.valueOf(row[4]), row[1].equals("yes"), set(row[3]));
			Verdict actual = SelectionPolicy.decide(actor, rec, PersonalDifficultyMode.valueOf(row[5]), set(row[2]));
			assertEquals(Verdict.valueOf(row[6]), actual, String.join(" | ", row));
		}
	}

	@Test
	void anUnreadableRecordBlocksEveryActorBeforeAnythingElse() {
		// ORDER: first of all; an administrator's assign cannot override data that could not be read (R2 needs a record to override)
		for (Actor actor : Actor.values()) {
			assertEquals(Verdict.REFUSED_INVALID_RECORD, SelectionPolicy.decide(actor, PlayerRecord.quarantinedView(ELITE), NORMAL, EnumSet.of(NORMAL)), actor.toString());
		}
	}

	/** current, locked, list, flag, expected status, expected resulting mode, switched? (R5/R6, written by hand) */
	private static final String[][] RESTRICT_TABLE = {
		{"HARD", "no", "EASY,HARD", "NONE", "APPLY", "HARD", "no"},                     // current mode is listed: nothing to decide
		{"HARD", "no", "EASY,HARD", "SWITCH", "APPLY", "HARD", "no"},                   // ...so the flag is irrelevant
		{"HARD", "no", "EASY,ELITE", "NONE", "NEEDS_CHOICE", "HARD", "no"},             // R5 cancel: refuse and ask
		{"HARD", "no", "EASY,ELITE", "KEEP", "APPLY", "HARD", "no"},                    // R5 keep: stays on HARD as an exception
		{"HARD", "no", "EASY,ELITE", "SWITCH", "APPLY", "EASY", "yes"},                 // R5 switch: first listed (NORMAL is not listed)
		{"ELITE", "no", "PEACEFUL,HARD,NORMAL", "SWITCH", "APPLY", "NORMAL", "yes"},    // NORMAL wins when listed, whatever the typing order
		{"PEACEFUL", "no", "ELITE,HARD", "SWITCH", "APPLY", "HARD", "yes"},             // canonical order: HARD before ELITE
		{"NORMAL", "no", "PEACEFUL", "SWITCH", "APPLY", "PEACEFUL", "yes"},
		{"ELITE", "yes", "PEACEFUL,HARD", "SWITCH", "APPLY", "HARD", "yes"},            // R6: restrict never touches the lock (checked below)
		{"ELITE", "yes", "PEACEFUL,HARD", "NONE", "NEEDS_CHOICE", "ELITE", "no"},
	};

	@Test
	void handWrittenRestrictTable() {
		for (String[] row : RESTRICT_TABLE) {
			PlayerRecord before = record(PersonalDifficultyMode.valueOf(row[0]), row[1].equals("yes"), null);
			RestrictPlan plan = SelectionPolicy.planRestrict(before, set(row[2]), RestrictFlag.valueOf(row[3]), ADMIN, 1L);
			String ctx = String.join(" | ", row);
			assertEquals(RestrictStatus.valueOf(row[4]), plan.status(), ctx);
			assertEquals(PersonalDifficultyMode.valueOf(row[5]), plan.result().mode(), ctx);
			assertEquals(row[6].equals("yes"), plan.modeSwitched(), ctx);
			assertEquals(before.locked(), plan.result().locked(), "R6: the lock is never changed by restrict: " + ctx);
			if (plan.status() == RestrictStatus.APPLY) {
				assertEquals(set(row[2]), plan.result().allowed(), ctx);
			} else {
				assertNull(plan.result().allowed(), "a refused restrict stores nothing: " + ctx);
			}
		}
		assertEquals(RestrictStatus.REFUSED_EMPTY, SelectionPolicy.planRestrict(record(NORMAL, false, null), EnumSet.noneOf(PersonalDifficultyMode.class), RestrictFlag.SWITCH, ADMIN, 1L).status(), "R5: never an empty list");
	}

	@Test
	void completeDecisionMatrixHasZeroMismatches() {
		List<Set<PersonalDifficultyMode>> enabledSets = nonEmptySubsets();
		List<Set<PersonalDifficultyMode>> allowLists = new ArrayList<>();
		allowLists.add(null); // follow the server policy
		allowLists.addAll(nonEmptySubsets()); // R5: never empty
		long cases = 0;
		List<String> mismatches = new ArrayList<>();
		for (Actor actor : Actor.values()) {
			for (Set<PersonalDifficultyMode> enabled : enabledSets) {
				for (Set<PersonalDifficultyMode> allowed : allowLists) {
					for (boolean locked : new boolean[] {false, true}) {
						for (PersonalDifficultyMode current : MODES) {
							for (PersonalDifficultyMode requested : MODES) {
								cases++;
								PlayerRecord rec = record(current, locked, allowed);
								Verdict actual = SelectionPolicy.decide(actor, rec, requested, enabled);
								Verdict expected;
								if (actor == Actor.ADMIN_ASSIGN) {
									expected = Verdict.ALLOWED; // R2
								} else {
									Verdict refusal = expectedRefusal(locked, enabled, allowed, requested); // R1, R3, R8
									expected = refusal != null ? refusal : current == requested ? Verdict.UNCHANGED : Verdict.ALLOWED;
								}
								if (actual != expected) {
									mismatches.add(actor + " enabled=" + enabled + " allowed=" + allowed + " locked=" + locked + " current=" + current + " requested=" + requested + ": expected " + expected + " got " + actual);
								}
							}
						}
					}
				}
			}
		}
		assertEquals(3L * 31 * 32 * 2 * 5 * 5, cases, "the generator covers the whole space");
		assertTrue(mismatches.isEmpty(), mismatches.size() + " mismatches; first: " + mismatches.stream().limit(5).toList());
	}

	@Test
	void anOperatorUsingTheOrdinaryCommandIsExactlyAnOrdinaryPlayer_R8() {
		for (Set<PersonalDifficultyMode> enabled : nonEmptySubsets()) {
			for (Set<PersonalDifficultyMode> allowed : java.util.stream.Stream.concat(java.util.stream.Stream.of((Set<PersonalDifficultyMode>) null), nonEmptySubsets().stream()).toList()) {
				for (boolean locked : new boolean[] {false, true}) {
					for (PersonalDifficultyMode current : MODES) {
						for (PersonalDifficultyMode requested : MODES) {
							PlayerRecord rec = record(current, locked, allowed);
							assertEquals(SelectionPolicy.decide(Actor.PLAYER, rec, requested, enabled), SelectionPolicy.decide(Actor.OPERATOR_SELF, rec, requested, enabled));
						}
					}
				}
			}
		}
	}

	@Test
	void selectableAgreesWithDecideForEveryCombination() {
		for (Set<PersonalDifficultyMode> enabled : nonEmptySubsets()) {
			for (Set<PersonalDifficultyMode> allowed : java.util.stream.Stream.concat(java.util.stream.Stream.of((Set<PersonalDifficultyMode>) null), nonEmptySubsets().stream()).toList()) {
				for (boolean locked : new boolean[] {false, true}) {
					PlayerRecord rec = record(NORMAL, locked, allowed);
					Set<PersonalDifficultyMode> selectable = SelectionPolicy.selectable(rec, enabled);
					for (PersonalDifficultyMode m : MODES) {
						Verdict v = SelectionPolicy.decide(Actor.PLAYER, rec, m, enabled);
						assertEquals(!v.refused(), selectable.contains(m), "selectable() must list exactly what decide() accepts: " + m + " " + enabled + " " + allowed + " locked=" + locked);
					}
					if (locked) {
						assertTrue(selectable.isEmpty(), "a locked player can select nothing");
					}
				}
			}
		}
	}

	@Test
	void decideNeverMutatesTheRecordAndDisabledOrLockedStateIsDataNotBehaviour() {
		// R4: lock and allow-list are fields of the record; nothing about the decision depends on time, session or history
		PlayerRecord rec = new PlayerRecord(ELITE, true, EnumSet.of(NORMAL, ELITE), ADMIN, 99L, new JsonObject());
		PlayerRecord copy = new PlayerRecord(ELITE, true, EnumSet.of(NORMAL, ELITE), ADMIN, 99L, new JsonObject());
		for (int i = 0; i < 3; i++) {
			SelectionPolicy.decide(Actor.PLAYER, rec, NORMAL, EnumSet.allOf(PersonalDifficultyMode.class));
		}
		assertEquals(copy, rec);
		// disabling every other mode (global policy change) neither unlocks nor changes the stored mode
		assertEquals(Verdict.REFUSED_LOCKED, SelectionPolicy.decide(Actor.PLAYER, rec, NORMAL, EnumSet.of(NORMAL)));
		assertEquals(Verdict.REFUSED_LOCKED, SelectionPolicy.decide(Actor.PLAYER, rec, ELITE, EnumSet.of(NORMAL)));
		assertEquals(ELITE, rec.mode());
	}

	@Test
	void anAllowListCanNeverBeEmpty_R5() {
		assertThrows(IllegalArgumentException.class, () -> record(NORMAL, false, EnumSet.noneOf(PersonalDifficultyMode.class)));
		RestrictPlan plan = SelectionPolicy.planRestrict(record(HARD, false, null), EnumSet.noneOf(PersonalDifficultyMode.class), RestrictFlag.SWITCH, ADMIN, 1L);
		assertEquals(RestrictStatus.REFUSED_EMPTY, plan.status());
	}

	@Test
	void restrictPlansForEveryCurrentModeListAndFlag_R5_R6() {
		for (PersonalDifficultyMode current : MODES) {
			for (boolean locked : new boolean[] {false, true}) {
				for (Set<PersonalDifficultyMode> list : nonEmptySubsets()) {
					for (RestrictFlag flag : RestrictFlag.values()) {
						PlayerRecord before = record(current, locked, null);
						RestrictPlan plan = SelectionPolicy.planRestrict(before, list, flag, ADMIN, 7L);
						String ctx = current + " locked=" + locked + " list=" + list + " flag=" + flag;
						if (list.contains(current)) {
							// the current mode stays selectable: nothing to decide, the flag is irrelevant
							assertEquals(RestrictStatus.APPLY, plan.status(), ctx);
							assertEquals(current, plan.result().mode(), ctx);
							assertFalse(plan.modeSwitched(), ctx);
							assertFalse(plan.result().isException(), ctx);
							assertEquals(list, plan.result().allowed(), ctx);
						} else {
							switch (flag) {
								case NONE -> {
									assertEquals(RestrictStatus.NEEDS_CHOICE, plan.status(), ctx);
									assertEquals(before, plan.result(), "refusal changes nothing: " + ctx);
								}
								case KEEP -> {
									assertEquals(RestrictStatus.APPLY, plan.status(), ctx);
									assertEquals(current, plan.result().mode(), "keep leaves the mode alone: " + ctx);
									assertTrue(plan.result().isException(), "the kept mode is a recorded exception outside the list: " + ctx);
									assertEquals(list, plan.result().allowed(), ctx);
									assertFalse(plan.modeSwitched(), ctx);
								}
								case SWITCH -> {
									assertEquals(RestrictStatus.APPLY, plan.status(), ctx);
									PersonalDifficultyMode expected = list.contains(NORMAL) ? NORMAL : list.stream().min(java.util.Comparator.naturalOrder()).orElseThrow();
									assertEquals(expected, plan.result().mode(), "switch target is NORMAL if listed, else the first listed in canonical order: " + ctx);
									assertTrue(list.contains(plan.result().mode()), ctx);
									assertTrue(plan.modeSwitched(), ctx);
									assertFalse(plan.result().isException(), ctx);
								}
							}
						}
						if (plan.status() == RestrictStatus.APPLY) {
							assertEquals(locked, plan.result().locked(), "R6: restrict never changes the lock: " + ctx);
						}
					}
				}
			}
		}
	}

	@Test
	void restrictAndLockAreIndependent_R6() {
		PlayerRecord locked = record(ELITE, true, null);
		PlayerRecord restricted = SelectionPolicy.planRestrict(locked, EnumSet.of(ELITE, EASY), RestrictFlag.NONE, ADMIN, 1L).result();
		assertTrue(restricted.locked());
		assertEquals(EnumSet.of(ELITE, EASY), restricted.allowed());
		// unlocking later: the allow-list applies from then on
		PlayerRecord unlocked = restricted.withLocked(false, ADMIN, 2L);
		assertEquals(Verdict.REFUSED_NOT_IN_ALLOW_LIST, SelectionPolicy.decide(Actor.PLAYER, unlocked, HARD, EnumSet.allOf(PersonalDifficultyMode.class)));
		assertEquals(Verdict.ALLOWED, SelectionPolicy.decide(Actor.PLAYER, unlocked, EASY, EnumSet.allOf(PersonalDifficultyMode.class)));
		// removing the allow-list leaves the lock alone
		assertTrue(restricted.withAllowed(null, ADMIN, 3L).locked());
	}

	@Test
	void anAdministrativeSwitchIgnoresGlobalPolicy_R2() {
		// switching to a mode that is globally disabled is still an administrative act (the switch target is not filtered by policy)
		PlayerRecord rec = record(HARD, false, null);
		RestrictPlan plan = SelectionPolicy.planRestrict(rec, EnumSet.of(PEACEFUL), RestrictFlag.SWITCH, ADMIN, 1L);
		assertEquals(PEACEFUL, plan.result().mode());
	}

	@Test
	void resetStateIsTheDefaultRecord_R7() {
		PlayerRecord rec = new PlayerRecord(ELITE, true, EnumSet.of(ELITE), ADMIN, 5L, new JsonObject());
		PlayerRecord reset = new PlayerRecord(NORMAL, false, null, null, 0L, rec.extras());
		assertTrue(reset.isDefault());
		assertEquals(Verdict.UNCHANGED, SelectionPolicy.decide(Actor.PLAYER, reset, NORMAL, EnumSet.allOf(PersonalDifficultyMode.class)));
		assertEquals(Verdict.ALLOWED, SelectionPolicy.decide(Actor.PLAYER, reset, ELITE, EnumSet.allOf(PersonalDifficultyMode.class)));
	}
}
