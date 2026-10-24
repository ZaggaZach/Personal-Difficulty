package com.zaggazach.personaldifficulty;

import static org.junit.jupiter.api.Assertions.*;

import com.zaggazach.personaldifficulty.config.PdConfig;
import com.zaggazach.personaldifficulty.config.PdConfigLoader;
import com.zaggazach.personaldifficulty.logic.DamageAdjuster;
import com.zaggazach.personaldifficulty.logic.DamageContext;
import org.junit.jupiter.api.Test;

class DamageAdjusterTest {
	private final PdConfig cfg = PdConfig.defaults();

	private static DamageContext mob() {
		return new DamageContext("minecraft:mob_attack", false, false, false, false, false);
	}

	private static DamageContext env(String id) {
		return new DamageContext(id, false, false, false, false, true);
	}

	@Test
	void easyAppliesConfiguredMultiplierToSupportedSources() {
		for (String id : new String[] {"minecraft:fall", "minecraft:lava", "minecraft:in_fire", "minecraft:drown", "minecraft:in_wall", "minecraft:explosion", "minecraft:cactus"}) {
			double m = DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.EASY, env(id));
			assertEquals(0.60, m, id);
			assertEquals(6.0F, DamageAdjuster.apply(10.0F, m), 1e-5, id);
		}
		assertEquals(0.60, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.EASY, mob()));
		assertEquals(0.60, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.EASY, new DamageContext("minecraft:arrow", false, false, false, false, false)));
	}

	@Test
	void normalAndInheritNeverModify() {
		for (PersonalDifficultyMode m : new PersonalDifficultyMode[] {PersonalDifficultyMode.NORMAL, PersonalDifficultyMode.INHERIT}) {
			assertEquals(1.0, DamageAdjuster.multiplier(cfg, m, mob()));
			assertEquals(1.0, DamageAdjuster.multiplier(cfg, m, env("minecraft:fall")));
			assertEquals(7.5F, DamageAdjuster.apply(7.5F, DamageAdjuster.multiplier(cfg, m, mob())));
		}
	}

	@Test
	void hardIsASingleFlatFactorNotARepeatOfVanillaHard() {
		// Vanilla Hard scaling (x1.5) happens earlier in Player.hurtServer and is part of the input.
		// The personal factor is exactly the configured value, applied once to that input.
		float afterVanillaHard = 6.0F * 1.5F;
		float out = DamageAdjuster.apply(afterVanillaHard, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.HARD, mob()));
		assertEquals(afterVanillaHard * 1.25F, out, 1e-5);
	}

	@Test
	void pvpAndSelfUnchangedByDefaultButConfigurable() {
		DamageContext pvp = new DamageContext("minecraft:player_attack", false, false, false, true, false);
		DamageContext self = new DamageContext("minecraft:arrow", false, false, true, false, false);
		assertEquals(1.0, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.EASY, pvp));
		assertEquals(1.0, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.EASY, self));
		assertEquals(1.0, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.HARD, pvp));
		PdConfig custom = PdConfigLoader.parse("pvp.damage-multiplier=0.5\nself.damage-multiplier=0.25").config();
		assertEquals(0.5, DamageAdjuster.multiplier(custom, PersonalDifficultyMode.EASY, pvp));
		assertEquals(0.25, DamageAdjuster.multiplier(custom, PersonalDifficultyMode.EASY, self));
	}

	@Test
	void voidAndAdminDamageUnchangedByDefaultAndOptIn() {
		DamageContext voidDmg = new DamageContext("minecraft:out_of_world", true, false, false, false, true);
		DamageContext kill = new DamageContext("minecraft:generic_kill", false, true, false, false, true);
		assertEquals(1.0, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.EASY, voidDmg));
		assertEquals(1.0, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.EASY, kill));
		PdConfig optIn = PdConfigLoader.parse("void.apply-profile=true\nadmin.apply-profile=true").config();
		assertEquals(0.60, DamageAdjuster.multiplier(optIn, PersonalDifficultyMode.EASY, voidDmg));
		assertEquals(0.60, DamageAdjuster.multiplier(optIn, PersonalDifficultyMode.EASY, kill));
	}

	@Test
	void environmentalGate() {
		PdConfig noEnv = PdConfigLoader.parse("environmental.apply-profile=false").config();
		assertEquals(1.0, DamageAdjuster.multiplier(noEnv, PersonalDifficultyMode.EASY, env("minecraft:fall")));
		assertEquals(0.60, DamageAdjuster.multiplier(noEnv, PersonalDifficultyMode.EASY, mob()), "mobs still scaled");
	}

	@Test
	void perTypeOverrideReplacesProfileMultiplierButNotExceptions() {
		PdConfig c = PdConfigLoader.parse("easy.damage-type.minecraft.fall=0.2").config();
		assertEquals(0.2, DamageAdjuster.multiplier(c, PersonalDifficultyMode.EASY, env("minecraft:fall")));
		assertEquals(0.60, DamageAdjuster.multiplier(c, PersonalDifficultyMode.EASY, env("minecraft:lava")));
		assertEquals(1.0, DamageAdjuster.multiplier(c, PersonalDifficultyMode.EASY, new DamageContext("minecraft:fall", false, false, true, false, false)), "self exception wins");
		assertEquals(1.0, DamageAdjuster.multiplier(c, PersonalDifficultyMode.NORMAL, env("minecraft:fall")));
	}

	@Test
	void applyNeverHealsRevivesOrCreatesNaN() {
		assertEquals(0.0F, DamageAdjuster.apply(5.0F, 0.0));
		assertEquals(0.0F, DamageAdjuster.apply(5.0F, -1.0), "negative factor floors at zero damage");
		assertEquals(0.0F, DamageAdjuster.apply(0.0F, 3.0));
		assertEquals(-1.0F, DamageAdjuster.apply(-1.0F, 3.0), "non-damage passes through untouched");
		assertTrue(Float.isNaN(DamageAdjuster.apply(Float.NaN, 2.0)));
		assertEquals(Float.MAX_VALUE, DamageAdjuster.apply(Float.MAX_VALUE, 0.6), "kill sentinel untouched");
		assertEquals(Float.POSITIVE_INFINITY, DamageAdjuster.apply(Float.POSITIVE_INFINITY, 0.6));
		assertFalse(Float.isNaN(DamageAdjuster.apply(3.0F, Double.NaN)));
	}

	@Test
	void exactlyOnceMeansMultiplierIsNotComposedAcrossCalls() {
		float once = DamageAdjuster.apply(10.0F, 0.6);
		assertEquals(6.0F, once, 1e-5);
		// A second application would give 3.6; the pipeline calls the hook exactly once per hit.
		assertNotEquals(3.6F, once, 1e-3);
	}

	@Test
	void twoPlayersDifferentModesGetDifferentOutcomesFromEquivalentAttacks() {
		DamageContext hit = mob();
		float easy = DamageAdjuster.apply(10.0F, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.EASY, hit));
		float normal = DamageAdjuster.apply(10.0F, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.NORMAL, hit));
		float hard = DamageAdjuster.apply(10.0F, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.HARD, hit));
		assertEquals(6.0F, easy, 1e-5);
		assertEquals(10.0F, normal);
		assertEquals(12.5F, hard, 1e-5);
	}
}
