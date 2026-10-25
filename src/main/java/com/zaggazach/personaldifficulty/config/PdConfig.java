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
	double pvpDamageMultiplier,
	double selfDamageMultiplier,
	boolean environmentalApplyProfile,
	boolean voidApplyProfile,
	boolean adminApplyProfile,
	RangedMobs rangedMobs
) {
	public static final double MAX_MULTIPLIER = 10.0;
	public static final double MAX_UNCERTAINTY = 60.0;

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
			1.0,
			1.0,
			true,
			false,
			false,
			new RangedMobs(true, true, true, true, true, true)
		);
	}
}
