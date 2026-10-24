package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.logic.HungerAdjuster;
import com.zaggazach.personaldifficulty.logic.RangedAccuracy;
import com.zaggazach.personaldifficulty.logic.RangedAccuracy.Family;
import org.junit.jupiter.api.Test;

class HungerAndAimTest {
	private final PdConfig cfg = PdConfig.defaults();

	@Test
	void hungerMultipliersPerMode() {
		assertEquals(0.05F, HungerAdjuster.adjustExhaustion(cfg, PersonalDifficultyMode.EASY, 0.1F), 1e-6);
		assertEquals(0.1F, HungerAdjuster.adjustExhaustion(cfg, PersonalDifficultyMode.NORMAL, 0.1F));
		assertEquals(0.1F, HungerAdjuster.adjustExhaustion(cfg, PersonalDifficultyMode.INHERIT, 0.1F));
		assertEquals(0.125F, HungerAdjuster.adjustExhaustion(cfg, PersonalDifficultyMode.HARD, 0.1F), 1e-6);
	}

	@Test
	void fractionalExhaustionIsPreservedNotRounded() {
		// 1000 tiny sprint steps: total must equal 0.5x the vanilla total, with no per-step loss.
		float vanilla = 0, easy = 0;
		for (int i = 0; i < 1000; i++) {
			float step = 0.01F * 1.0F * 0.01F * 3.7F;
			vanilla += step;
			easy += HungerAdjuster.adjustExhaustion(cfg, PersonalDifficultyMode.EASY, step);
		}
		assertEquals(vanilla * 0.5F, easy, vanilla * 1e-3F);
	}

	@Test
	void easyDepletesFoodSlowerUnderEquivalentExhaustion() {
		// Model of FoodData: every 4.0 exhaustion costs 1 food point once saturation is empty.
		assertTrue(foodLost(PersonalDifficultyMode.EASY, 400) < foodLost(PersonalDifficultyMode.NORMAL, 400));
		assertTrue(foodLost(PersonalDifficultyMode.HARD, 400) > foodLost(PersonalDifficultyMode.NORMAL, 400));
		assertEquals(foodLost(PersonalDifficultyMode.NORMAL, 400), foodLost(PersonalDifficultyMode.INHERIT, 400));
		// Vanilla compares "exhaustion > 4.0" strictly, so exact multiples land one point lower.
		assertEquals(49, foodLost(PersonalDifficultyMode.EASY, 400));
		assertEquals(99, foodLost(PersonalDifficultyMode.NORMAL, 400));
		assertEquals(124, foodLost(PersonalDifficultyMode.HARD, 400));
	}

	private int foodLost(PersonalDifficultyMode mode, int sources) {
		float exhaustion = 0;
		int lost = 0;
		for (int i = 0; i < sources; i++) {
			exhaustion += HungerAdjuster.adjustExhaustion(cfg, mode, 1.0F); // 1.0 per source
			while (exhaustion > 4.0F) {
				exhaustion -= 4.0F;
				lost++;
			}
		}
		return lost;
	}

	@Test
	void switchingModeCarriesNoStateBecauseNothingIsStored() {
		// The adjuster is a pure function of (config, mode, amount): no accumulator to reset or corrupt.
		float a = HungerAdjuster.adjustExhaustion(cfg, PersonalDifficultyMode.EASY, 1.0F);
		float b = HungerAdjuster.adjustExhaustion(cfg, PersonalDifficultyMode.NORMAL, 1.0F);
		float c = HungerAdjuster.adjustExhaustion(cfg, PersonalDifficultyMode.EASY, 1.0F);
		assertEquals(a, c);
		assertEquals(1.0F, b);
	}

	@Test
	void nonPositiveExhaustionIsNeverTouched() {
		assertEquals(0.0F, HungerAdjuster.adjustExhaustion(cfg, PersonalDifficultyMode.EASY, 0.0F));
		assertEquals(-1.0F, HungerAdjuster.adjustExhaustion(cfg, PersonalDifficultyMode.EASY, -1.0F));
	}

	@Test
	void easyWidensSpreadForAllWorldDifficultiesAndNeverTightens() {
		for (float vanilla : new float[] {2.0F, 6.0F, 10.0F, 14.0F}) {
			assertEquals(14.0F, RangedAccuracy.adjust(cfg, Family.SKELETON, PersonalDifficultyMode.EASY, vanilla));
		}
		assertEquals(20.0F, RangedAccuracy.adjust(cfg, Family.SKELETON, PersonalDifficultyMode.EASY, 20.0F), "never tighter than vanilla");
	}

	@Test
	void otherTargetsKeepVanillaAccuracy() {
		for (PersonalDifficultyMode m : new PersonalDifficultyMode[] {PersonalDifficultyMode.NORMAL, PersonalDifficultyMode.INHERIT, PersonalDifficultyMode.HARD}) {
			assertEquals(2.0F, RangedAccuracy.adjust(cfg, Family.SKELETON, m, 2.0F), m.name());
		}
		assertEquals(2.0F, RangedAccuracy.adjust(cfg, Family.SKELETON, null, 2.0F), "non-player target (null mode)");
	}

	@Test
	void effectFollowsTheTargetPerShot() {
		// Same shooter, same vanilla value, two consecutive shots at different targets.
		float atEasy = RangedAccuracy.adjust(cfg, Family.SKELETON, PersonalDifficultyMode.EASY, 2.0F);
		float atNormal = RangedAccuracy.adjust(cfg, Family.SKELETON, PersonalDifficultyMode.NORMAL, 2.0F);
		float atEasyAgain = RangedAccuracy.adjust(cfg, Family.SKELETON, PersonalDifficultyMode.EASY, 2.0F);
		assertEquals(14.0F, atEasy);
		assertEquals(2.0F, atNormal);
		assertEquals(14.0F, atEasyAgain);
	}

	@Test
	void mobFamiliesCanBeDisabled() {
		PdConfig c = PdConfigLoader.parse("ranged.skeletons=false\nranged.drowned=false").config();
		assertEquals(2.0F, RangedAccuracy.adjust(c, Family.SKELETON, PersonalDifficultyMode.EASY, 2.0F));
		assertEquals(2.0F, RangedAccuracy.adjust(c, Family.DROWNED, PersonalDifficultyMode.EASY, 2.0F));
		assertEquals(14.0F, RangedAccuracy.adjust(c, Family.ILLUSIONER, PersonalDifficultyMode.EASY, 2.0F));
		assertEquals(14.0F, RangedAccuracy.adjust(c, Family.CROSSBOW, PersonalDifficultyMode.EASY, 2.0F));
	}
}
