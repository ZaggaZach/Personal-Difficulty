package com.zaggazach.personaldifficulty.config;

import java.util.Map;

/**
 * Immutable, validated server-wide configuration. Build via {@link PdConfigLoader}; direct
 * construction is for tests. All numeric values are expected to already be inside their bounds.
 */
public record PdConfig(
	Profile easy,
	Profile hard,
	Profile elite,
	double easyRangedMinUncertainty,
	double eliteRangedMaxUncertainty,
	double eliteArmorEffectiveness,
	double eliteRegenMultiplier,
	double pvpDamageMultiplier,
	double selfDamageMultiplier,
	boolean environmentalApplyProfile,
	boolean voidApplyProfile,
	boolean adminApplyProfile,
	RangedMobs rangedMobs,
	EliteAi eliteAi
) {
	public static final double MAX_MULTIPLIER = 10.0;
	public static final double MAX_UNCERTAINTY = 60.0;
	/** Elite natural regeneration can be slowed down to a tenth, never sped up. */
	public static final double MIN_REGEN_MULTIPLIER = 0.1;
	/** Range multipliers may at most double a mob's own range (and are further capped, see EliteSurvival). */
	public static final double MAX_AI_RANGE_MULTIPLIER = 2.0;
	public static final double MAX_MEMORY_MULTIPLIER = 4.0;

	/**
	 * One personal difficulty profile.
	 *
	 * @param damageMultiplier factor applied once to the player's incoming damage, after vanilla
	 *     mitigation and before absorption hearts
	 * @param hungerMultiplier factor applied to every exhaustion amount the player accrues
	 * @param damageTypeOverrides damage type id ("minecraft:fall") to multiplier replacing
	 *     {@code damageMultiplier} for that type
	 */
	public record Profile(double damageMultiplier, double hungerMultiplier, Map<String, Double> damageTypeOverrides) {
		public Profile {
			damageTypeOverrides = Map.copyOf(damageTypeOverrides);
		}
	}

	/**
	 * How supported hostile mobs behave while their target is an Elite player. Every value is read at the moment a
	 * goal decides something, from the mob's CURRENT target, so nothing is stored on mobs or in attributes.
	 *
	 * @param acquisitionRangeMultiplier an idle mob may also acquire an Elite player out to this multiple of its own
	 *     follow range (still subject to line of sight, visibility, invisibility and every vanilla targeting rule)
	 * @param followRangeMultiplier a mob keeps pursuing an Elite target, and can path to it, out to this multiple
	 * @param improvedPursuit longer memory of an Elite target that is out of sight, and a faster first move
	 *     toward an Elite target for melee mobs
	 * @param targetMemoryMultiplier multiplier on the ticks a mob remembers an unseen Elite target (needs improvedPursuit)
	 * @param advancedCombat skeletons fire at Elite players at their Hard-difficulty cadence on any world difficulty
	 */
	public record EliteAi(
		double acquisitionRangeMultiplier,
		double followRangeMultiplier,
		boolean improvedPursuit,
		double targetMemoryMultiplier,
		boolean advancedCombat
	) {
	}

	/** Which ranged attacker families have their aim adjusted (Easy: wider, Elite: tighter). */
	public record RangedMobs(boolean skeletons, boolean illusioners, boolean drowned, boolean crossbowMobs, boolean piglins, boolean witches) {
	}

	public static PdConfig defaults() {
		return new PdConfig(
			new Profile(0.60, 0.50, Map.of()),
			new Profile(1.25, 1.25, Map.of()),
			new Profile(2.00, 2.00, Map.of()),
			14.0,
			1.0,
			0.60,
			0.50,
			1.0,
			1.0,
			true,
			false,
			false,
			new RangedMobs(true, true, true, true, true, true),
			new EliteAi(1.25, 1.25, true, 2.0, true)
		);
	}
}
