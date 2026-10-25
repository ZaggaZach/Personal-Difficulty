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
	void normalAndPeacefulNeverModifyViaTheProfile() {
		for (PersonalDifficultyMode m : new PersonalDifficultyMode[] {PersonalDifficultyMode.NORMAL, PersonalDifficultyMode.PEACEFUL}) {
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

	// ---- Elite ----

	/** Vanilla CombatRules, replicated so the design table in docs/DESIGN.md is checked, not just quoted. */
	private static float vanillaArmour(float damage, float armour, float toughness) {
		float t = 2.0F + toughness / 4.0F;
		float real = Math.min(Math.max(armour - damage / t, armour * 0.2F), 20.0F);
		return damage * (1.0F - real / 25.0F);
	}

	private static float vanillaEnchant(float damage, float epf) {
		return damage * (1.0F - Math.min(Math.max(epf, 0.0F), 20.0F) / 25.0F);
	}

	private float finalDamage(PersonalDifficultyMode mode, float raw, float armour, float toughness, float epf) {
		DamageContext ctx = mob();
		float afterArmour = vanillaArmour(raw, armour, toughness);
		afterArmour = DamageAdjuster.applyArmor(raw, afterArmour, DamageAdjuster.armorEffectiveness(cfg, mode, ctx));
		float afterMagic = vanillaEnchant(afterArmour, epf);
		return DamageAdjuster.apply(afterMagic, DamageAdjuster.multiplier(cfg, mode, ctx));
	}

	@Test
	void eliteMultiplierIsTwoAppliedOnce() {
		assertEquals(2.0, DamageAdjuster.multiplier(cfg, PersonalDifficultyMode.ELITE, mob()));
		assertEquals(20.0F, DamageAdjuster.apply(10.0F, 2.0), 1e-5);
	}

	@Test
	void eliteIsMoreDangerousThanHardAtEveryArmourTier() {
		float[][] tiers = {{0, 0, 0}, {15, 0, 0}, {20, 8, 0}, {20, 8, 16}, {20, 12, 0}, {20, 12, 16}};
		for (float raw : new float[] {4.5F, 7.0F, 19.5F}) {
			for (float[] t : tiers) {
				float hard = finalDamage(PersonalDifficultyMode.HARD, raw, t[0], t[1], t[2]);
				float elite = finalDamage(PersonalDifficultyMode.ELITE, raw, t[0], t[1], t[2]);
				assertTrue(elite > hard * 1.5F, "raw " + raw + " armour " + t[0] + "/" + t[1] + " epf " + t[2] + ": elite " + elite + " vs hard " + hard);
			}
		}
	}

	@Test
	void armourStillMattersUnderElite() {
		float naked = finalDamage(PersonalDifficultyMode.ELITE, 7.0F, 0, 0, 0);
		float iron = finalDamage(PersonalDifficultyMode.ELITE, 7.0F, 15, 0, 0);
		float diamond = finalDamage(PersonalDifficultyMode.ELITE, 7.0F, 20, 8, 0);
		float netherite = finalDamage(PersonalDifficultyMode.ELITE, 7.0F, 20, 12, 16);
		assertTrue(naked > iron && iron > diamond && diamond > netherite, naked + " > " + iron + " > " + diamond + " > " + netherite);
	}

	@Test
	void designTableForFullyEnchantedNetheriteAgainstAHardZombieHit() {
		// raw 4.5 = vanilla zombie 3.0 x1.5 (world Hard). Numbers quoted in docs/DESIGN.md.
		assertEquals(0.38F, finalDamage(PersonalDifficultyMode.NORMAL, 4.5F, 20, 12, 16), 0.02F);
		assertEquals(0.48F, finalDamage(PersonalDifficultyMode.HARD, 4.5F, 20, 12, 16), 0.02F);
		assertEquals(1.75F, finalDamage(PersonalDifficultyMode.ELITE, 4.5F, 20, 12, 16), 0.03F);
		// ...and the problem a bare x2 would have had (armour fully neutralises it):
		assertEquals(0.76F, vanillaEnchant(vanillaArmour(4.5F, 20, 12), 16) * 2.0F, 0.02F);
		// unarmoured and iron players die in a few hits (20 HP)
		assertTrue(finalDamage(PersonalDifficultyMode.ELITE, 4.5F, 0, 0, 0) * 3 > 20.0F);
		assertTrue(finalDamage(PersonalDifficultyMode.ELITE, 4.5F, 15, 0, 0) * 4 > 20.0F);
		// netherite survives many hits from a zombie but only a couple from a heavy hitter
		assertTrue(20.0F / finalDamage(PersonalDifficultyMode.ELITE, 4.5F, 20, 12, 16) > 8);
		assertTrue(20.0F / finalDamage(PersonalDifficultyMode.ELITE, 19.5F, 20, 12, 16) < 3.5F);
	}

	@Test
	void armourWeakeningNeverExceedsTheUnmitigatedHitNorDropsBelowVanilla() {
		for (double k : new double[] {0.0, 0.3, 0.6, 0.99, 1.0}) {
			float out = DamageAdjuster.applyArmor(10.0F, 4.0F, k);
			assertTrue(out >= 4.0F - 1e-6F && out <= 10.0F + 1e-6F, "k=" + k + " out=" + out);
		}
		assertEquals(10.0F, DamageAdjuster.applyArmor(10.0F, 4.0F, 0.0), 1e-5, "0 = armour does nothing");
		assertEquals(4.0F, DamageAdjuster.applyArmor(10.0F, 4.0F, 1.0), "1 = vanilla");
		assertEquals(6.4F, DamageAdjuster.applyArmor(10.0F, 4.0F, 0.6), 1e-4, "armour removed 6; at 60% effectiveness it removes 3.6");
		assertEquals(10.0F, DamageAdjuster.applyArmor(10.0F, 10.0F, 0.6), "armour-bypassing damage is untouched");
		assertEquals(4.0F, DamageAdjuster.applyArmor(10.0F, 4.0F, 5.0), "effectiveness above 1 never strengthens armour");
		assertTrue(Float.isNaN(DamageAdjuster.applyArmor(Float.NaN, Float.NaN, 0.6)));
		assertEquals(1.0F, DamageAdjuster.applyArmor(Float.MAX_VALUE, 1.0F, 0.6), "kill sentinels: vanilla's own armour result is returned untouched");
	}

	@Test
	void armourWeakeningOnlyAppliesToEliteAndNotToTheExceptions() {
		assertEquals(0.60, DamageAdjuster.armorEffectiveness(cfg, PersonalDifficultyMode.ELITE, mob()));
		for (PersonalDifficultyMode m : new PersonalDifficultyMode[] {PersonalDifficultyMode.NORMAL, PersonalDifficultyMode.EASY, PersonalDifficultyMode.HARD, PersonalDifficultyMode.PEACEFUL}) {
			assertEquals(1.0, DamageAdjuster.armorEffectiveness(cfg, m, mob()), m.name());
		}
		DamageContext pvp = new DamageContext("minecraft:player_attack", false, false, false, true, false);
		DamageContext self = new DamageContext("minecraft:arrow", false, false, true, false, false);
		DamageContext voidDmg = new DamageContext("minecraft:out_of_world", true, false, false, false, true);
		assertEquals(1.0, DamageAdjuster.armorEffectiveness(cfg, PersonalDifficultyMode.ELITE, pvp), "Elite vs a Normal player: existing PvP rules");
		assertEquals(1.0, DamageAdjuster.armorEffectiveness(cfg, PersonalDifficultyMode.ELITE, self));
		assertEquals(1.0, DamageAdjuster.armorEffectiveness(cfg, PersonalDifficultyMode.ELITE, voidDmg));
		PdConfig optIn = PdConfigLoader.parse("void.apply-profile=true").config();
		assertEquals(0.60, DamageAdjuster.armorEffectiveness(optIn, PersonalDifficultyMode.ELITE, voidDmg));
		PdConfig noEnv = PdConfigLoader.parse("environmental.apply-profile=false").config();
		assertEquals(1.0, DamageAdjuster.armorEffectiveness(noEnv, PersonalDifficultyMode.ELITE, env("minecraft:cactus")));
	}

	@Test
	void eliteDoesNotChangeEasyNormalOrHardOutcomes() {
		assertEquals(6.0F, finalDamage(PersonalDifficultyMode.EASY, 10.0F, 0, 0, 0), 1e-5);
		assertEquals(10.0F, finalDamage(PersonalDifficultyMode.NORMAL, 10.0F, 0, 0, 0), 1e-5);
		assertEquals(12.5F, finalDamage(PersonalDifficultyMode.HARD, 10.0F, 0, 0, 0), 1e-5);
		assertEquals(20.0F, finalDamage(PersonalDifficultyMode.ELITE, 10.0F, 0, 0, 0), 1e-5, "unarmoured: armour weakening has nothing to weaken");
	}
}
