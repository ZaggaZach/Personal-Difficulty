package com.zaggazach.personaldifficulty;

import static com.zaggazach.personaldifficulty.PersonalDifficultyMode.*;
import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.logic.PeacefulRules;
import org.junit.jupiter.api.Test;

class PeacefulRulesTest {
	private static boolean damage(PersonalDifficultyMode victim, PersonalDifficultyMode attacker, boolean self, boolean bypass) {
		return PeacefulRules.blocksDamage(true, victim, attacker, self, bypass);
	}

	@Test
	void peacefulVictimIsProtectedFromEverythingExceptBypassingDamage() {
		for (PersonalDifficultyMode attacker : new PersonalDifficultyMode[] {null, NORMAL, EASY, HARD, ELITE, PEACEFUL}) {
			assertTrue(damage(PEACEFUL, attacker, false, false), "attacker " + attacker);
		}
		assertTrue(damage(PEACEFUL, null, true, false), "self-inflicted too (creative-like)");
		assertFalse(damage(PEACEFUL, null, false, true), "void / /kill still work");
	}

	@Test
	void peacefulAttackerCannotHurtAnyOtherPlayer() {
		for (PersonalDifficultyMode victim : new PersonalDifficultyMode[] {NORMAL, EASY, HARD, ELITE, PEACEFUL}) {
			assertTrue(damage(victim, PEACEFUL, false, false), "victim " + victim);
		}
	}

	@Test
	void pvpBetweenNonPeacefulPlayersIsUntouched() {
		PersonalDifficultyMode[] modes = {NORMAL, EASY, HARD, ELITE};
		for (PersonalDifficultyMode a : modes) {
			for (PersonalDifficultyMode v : modes) {
				assertFalse(damage(v, a, false, false), a + " -> " + v);
			}
		}
	}

	@Test
	void nonPlayerVictimsAndUnattributedDamageToNonPeacefulAreUntouched() {
		assertFalse(PeacefulRules.blocksDamage(false, NORMAL, PEACEFUL, false, false), "a Peaceful player may fight mobs");
		assertFalse(damage(NORMAL, null, false, false));
		assertFalse(damage(ELITE, null, false, false));
	}

	@Test
	void aPeacefulPlayerIsNeverATarget_othersAre() {
		assertTrue(PeacefulRules.blocksTargeting(PEACEFUL));
		for (PersonalDifficultyMode m : new PersonalDifficultyMode[] {NORMAL, EASY, HARD, ELITE}) {
			assertFalse(PeacefulRules.blocksTargeting(m), m.name());
		}
	}

	@Test
	void harmfulEffectsBothDirectionsBeneficialNever() {
		assertTrue(PeacefulRules.blocksEffect(true, true, PEACEFUL, null, false), "onto a Peaceful victim, any source");
		assertTrue(PeacefulRules.blocksEffect(true, true, NORMAL, PEACEFUL, false), "from a Peaceful player onto another player");
		assertFalse(PeacefulRules.blocksEffect(true, true, NORMAL, NORMAL, false), "ordinary PvP effects untouched");
		assertFalse(PeacefulRules.blocksEffect(true, true, NORMAL, null, false), "unattributed effects on a non-Peaceful player untouched");
		assertFalse(PeacefulRules.blocksEffect(false, true, PEACEFUL, null, false), "beneficial effects are never blocked");
		assertFalse(PeacefulRules.blocksEffect(true, false, NORMAL, PEACEFUL, false), "non-player victims are not protected");
	}
}
