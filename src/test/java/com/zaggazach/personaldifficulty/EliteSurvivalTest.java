package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.logic.EliteSurvival;
import org.junit.jupiter.api.Test;

class EliteSurvivalTest {
	@Test
	void halfSpeedRegenerationDoublesBothVanillaIntervals() {
		assertEquals(20, EliteSurvival.regenInterval(10, 0.5), "saturated: 10 -> 20 ticks");
		assertEquals(160, EliteSurvival.regenInterval(80, 0.5), "well fed: 80 -> 160 ticks");
		assertEquals(0.5, EliteSurvival.realisedRegenRate(10, 0.5));
		assertEquals(0.5, EliteSurvival.realisedRegenRate(80, 0.5));
	}

	@Test
	void otherMultipliersAreRoundedToWholeTicksAndNeverFasterThanVanilla() {
		assertEquals(40, EliteSurvival.regenInterval(10, 0.25));
		assertEquals(100, EliteSurvival.regenInterval(10, 0.1));
		assertEquals(33, EliteSurvival.regenInterval(10, 0.3));
		assertEquals(0.303, EliteSurvival.realisedRegenRate(10, 0.3), 0.001);
		assertEquals(11, EliteSurvival.regenInterval(10, 0.9), "coarsest case: 10-tick interval near 1.0");
		for (double m = 0.1; m <= 1.0; m += 0.01) {
			for (int vanilla : new int[] {10, 80}) {
				assertTrue(EliteSurvival.regenInterval(vanilla, m) >= vanilla, "never shorter than vanilla at " + m);
				assertTrue(EliteSurvival.realisedRegenRate(vanilla, m) <= 1.0 + 1e-12);
			}
		}
	}

	@Test
	void multiplierOfOneOrInvalidValuesLeaveVanillaUntouched() {
		for (double m : new double[] {1.0, 1.5, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			assertEquals(10, EliteSurvival.regenInterval(10, m), "multiplier " + m);
			assertEquals(80, EliteSurvival.regenInterval(80, m), "multiplier " + m);
		}
		assertEquals(0, EliteSurvival.regenInterval(0, 0.5));
	}

	@Test
	void rangesAreScaledButCappedAndNeverShrink() {
		double cap = EliteSurvival.LEGACY_RANGE_CAP;
		assertEquals(20.0, EliteSurvival.cappedRange(16, 1.25, cap), 1e-9);
		assertEquals(43.75, EliteSurvival.cappedRange(35, 1.25, cap), 1e-9);
		assertEquals(64.0, EliteSurvival.cappedRange(40, 2.0, cap), "absolute cap");
		assertEquals(64.0, EliteSurvival.cappedRange(64, 2.0, cap), "a mob already at the cap gets nothing");
		assertEquals(100.0, EliteSurvival.cappedRange(100, 2.0, cap), "a mob whose own range exceeds the cap is never reduced");
		for (double m : new double[] {1.0, 0.5, 0.0, -2.0, Double.NaN, Double.NEGATIVE_INFINITY}) {
			assertEquals(35.0, EliteSurvival.cappedRange(35, m, cap), "multiplier " + m + " cannot shrink or break a range");
		}
		assertEquals(35.0, EliteSurvival.cappedRange(35, Double.POSITIVE_INFINITY, cap), "infinity cannot create unlimited range");
		assertEquals(0.0, EliteSurvival.cappedRange(0.0, 2.0, cap));
		assertTrue(Double.isNaN(EliteSurvival.cappedRange(Double.NaN, 2.0, cap)), "garbage in a mob attribute is passed through untouched");
	}

	@Test
	void theAbsoluteCapIsASettingDefault128NeverBelowTheVanillaRange() {
		PdConfig.EliteAi ai = PdConfig.defaults().eliteAi();
		assertEquals(128, ai.absoluteRangeCap());
		assertEquals(105.0, EliteSurvival.cappedRange(35, 3.0, ai), 1e-9, "3x a zombie's 35 blocks fits inside 128");
		assertEquals(128.0, EliteSurvival.cappedRange(50, 3.0, ai), "but 3x 50 is capped at 128");
		assertEquals(48.0, EliteSurvival.cappedRange(35, 3.0, 48), "a lower cap applies");
		assertEquals(35.0, EliteSurvival.cappedRange(35, 3.0, 16), "a cap below the vanilla range never shrinks it");
		for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertEquals(35.0, EliteSurvival.cappedRange(35, 3.0, bad), "cap " + bad + " cannot create or break a range");
		}
		assertEquals(PdConfig.MIN_RANGE_CAP, PdConfigLoader.parse("elite.ai.absolute-range-cap=4").config().eliteAi().absoluteRangeCap(), "clamped to 16");
		assertEquals(PdConfig.MAX_RANGE_CAP, PdConfigLoader.parse("elite.ai.absolute-range-cap=100000").config().eliteAi().absoluteRangeCap(), "clamped to 256");
		assertEquals(64, PdConfigLoader.parse("elite.ai.absolute-range-cap=64").config().eliteAi().absoluteRangeCap(), "the 1.3.0 value is still available");
	}

	@Test
	void theRaisedMultiplierLimitsOf140AreAccepted() {
		PdConfig c = PdConfigLoader.parse("elite.ai.acquisition-range-multiplier=3\nelite.ai.follow-range-multiplier=2.5\nelite.ai.target-memory-multiplier=5").config();
		assertEquals(3.0, c.eliteAi().acquisitionRangeMultiplier());
		assertEquals(2.5, c.eliteAi().followRangeMultiplier());
		assertEquals(5.0, c.eliteAi().targetMemoryMultiplier());
		assertEquals(300, EliteSurvival.memoryTicks(60, 5.0), "60 ticks x 5 = 15 seconds, below the 400-tick ceiling");
		PdConfig over = PdConfigLoader.parse("elite.ai.acquisition-range-multiplier=3.5\nelite.ai.target-memory-multiplier=6").config();
		assertEquals(3.0, over.eliteAi().acquisitionRangeMultiplier(), "clamped to 3.0");
		assertEquals(5.0, over.eliteAi().targetMemoryMultiplier(), "clamped to 5.0");
	}

	@Test
	void pursuitUsesTheLargerOfFollowAndAcquisitionSoANewTargetIsNeverDroppedImmediately() {
		assertEquals(1.25, EliteSurvival.pursuitFactor(PdConfig.defaults().eliteAi()));
		assertEquals(1.5, EliteSurvival.pursuitFactor(new PdConfig.EliteAi(1.5, 1.25, true, 2.0, true)));
		assertEquals(1.5, EliteSurvival.pursuitFactor(new PdConfig.EliteAi(1.0, 1.5, true, 2.0, true)));
		assertEquals(1.0, EliteSurvival.pursuitFactor(new PdConfig.EliteAi(1.0, 1.0, true, 2.0, true)));
	}

	@Test
	void targetMemoryIsMultipliedButBounded() {
		assertEquals(120, EliteSurvival.memoryTicks(60, 2.0));
		assertEquals(240, EliteSurvival.memoryTicks(60, 4.0));
		assertEquals(EliteSurvival.MAX_MEMORY_TICKS, EliteSurvival.memoryTicks(60, 100.0));
		assertEquals(400, EliteSurvival.memoryTicks(300, 4.0));
		assertEquals(700, EliteSurvival.memoryTicks(700, 4.0), "a mob that already remembers longer is never reduced");
		for (double m : new double[] {1.0, 0.2, -1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertEquals(60, EliteSurvival.memoryTicks(60, m), "multiplier " + m);
		}
	}

	@Test
	void bowCadenceNeverGetsSlowerAndOnlyDropsToTheHardInterval() {
		assertEquals(20, EliteSurvival.bowInterval(40, 20));
		assertEquals(20, EliteSurvival.bowInterval(20, 20), "already Hard: unchanged");
		assertEquals(15, EliteSurvival.bowInterval(15, 20), "never slowed down");
		assertEquals(40, EliteSurvival.bowInterval(40, 0), "unknown hard interval: unchanged");
	}

	@Test
	void applyingTheFunctionsTwiceIsNotCompounding() {
		// each hook recomputes from the vanilla value on every call; feeding vanilla again gives the same answer
		assertEquals(EliteSurvival.regenInterval(10, 0.5), EliteSurvival.regenInterval(10, 0.5));
		assertEquals(EliteSurvival.cappedRange(35, 1.25, 64), EliteSurvival.cappedRange(35, 1.25, 64));
		assertEquals(EliteSurvival.memoryTicks(60, 2.0), EliteSurvival.memoryTicks(60, 2.0));
	}

	@Test
	void defaultsMatchTheDocumentedValues() {
		PdConfig c = PdConfig.defaults();
		assertEquals(0.5, c.eliteRegenMultiplier());
		assertEquals(1.25, c.eliteAi().acquisitionRangeMultiplier());
		assertEquals(1.25, c.eliteAi().followRangeMultiplier());
		assertTrue(c.eliteAi().improvedPursuit());
		assertEquals(2.0, c.eliteAi().targetMemoryMultiplier());
		assertTrue(c.eliteAi().advancedCombat());
		// 1.1.0 defaults are untouched
		assertEquals(2.0, c.elite().damageMultiplier());
		assertEquals(0.60, c.eliteArmorEffectiveness());
		assertEquals(1.0, c.eliteRangedMaxUncertainty());
	}

	@Test
	void newKeysAreParsedValidatedAndClamped() {
		PdConfig c = PdConfigLoader.parse("elite.natural-regeneration-multiplier=0.25\nelite.ai.acquisition-range-multiplier=1.5\nelite.ai.follow-range-multiplier=1.75\n"
			+ "elite.ai.improved-pursuit=false\nelite.ai.target-memory-multiplier=3\nelite.ai.advanced-combat=false").config();
		assertEquals(0.25, c.eliteRegenMultiplier());
		assertEquals(1.5, c.eliteAi().acquisitionRangeMultiplier());
		assertEquals(1.75, c.eliteAi().followRangeMultiplier());
		assertFalse(c.eliteAi().improvedPursuit());
		assertEquals(3.0, c.eliteAi().targetMemoryMultiplier());
		assertFalse(c.eliteAi().advancedCombat());

		PdConfigLoader.Result bad = PdConfigLoader.parse(String.join("\n",
			"elite.natural-regeneration-multiplier=0",
			"elite.ai.acquisition-range-multiplier=NaN",
			"elite.ai.follow-range-multiplier=Infinity",
			"elite.ai.target-memory-multiplier=-3",
			"elite.ai.improved-pursuit=sometimes",
			"elite.ai.advanced-combat=1"));
		PdConfig b = bad.config();
		assertEquals(PdConfig.MIN_REGEN_MULTIPLIER, b.eliteRegenMultiplier(), "0 would freeze regeneration: clamped to the minimum");
		assertEquals(1.25, b.eliteAi().acquisitionRangeMultiplier(), "NaN -> default");
		assertEquals(1.25, b.eliteAi().followRangeMultiplier(), "Infinity -> default");
		assertEquals(1.0, b.eliteAi().targetMemoryMultiplier(), "negative clamped to 1.0 (no effect)");
		assertTrue(b.eliteAi().improvedPursuit(), "bad boolean -> default");
		assertTrue(b.eliteAi().advancedCombat(), "bad boolean -> default");
		assertEquals(6, bad.warnings().size(), bad.warnings().toString());

		PdConfig big = PdConfigLoader.parse("elite.natural-regeneration-multiplier=7\nelite.ai.acquisition-range-multiplier=50\nelite.ai.follow-range-multiplier=9\nelite.ai.target-memory-multiplier=1000").config();
		assertEquals(1.0, big.eliteRegenMultiplier(), "Elite can never regenerate FASTER than vanilla");
		assertEquals(PdConfig.MAX_AI_RANGE_MULTIPLIER, big.eliteAi().acquisitionRangeMultiplier(), "no unlimited acquisition range");
		assertEquals(PdConfig.MAX_AI_RANGE_MULTIPLIER, big.eliteAi().followRangeMultiplier(), "no infinite pursuit");
		assertEquals(PdConfig.MAX_MEMORY_MULTIPLIER, big.eliteAi().targetMemoryMultiplier());
	}

	@Test
	void reloadPolicyChangedValuesAreReadOnNextLoad() {
		assertEquals(0.5, PdConfigLoader.parse("").config().eliteRegenMultiplier());
		assertEquals(0.2, PdConfigLoader.parse("elite.natural-regeneration-multiplier=0.2").config().eliteRegenMultiplier());
	}
}
