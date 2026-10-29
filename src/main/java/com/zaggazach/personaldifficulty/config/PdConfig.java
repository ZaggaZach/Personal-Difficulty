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
	EliteAi eliteAi,
	EliteHunt eliteHunt,
	EliteEffects eliteEffects
) {
	public static final double MAX_MULTIPLIER = 10.0;
	public static final double MAX_UNCERTAINTY = 60.0;
	/** Elite natural regeneration can be slowed down to a tenth, never sped up. */
	public static final double MIN_REGEN_MULTIPLIER = 0.1;
	/**
	 * Range multipliers may at most triple a mob's own range, and never reach beyond {@link EliteAi#absoluteRangeCap()} (1.4.0
	 * decision 6; 1.3.0 allowed 2.0).
	 */
	public static final double MAX_AI_RANGE_MULTIPLIER = 3.0;
	/** 1.4.0 decision 6 (1.3.0 allowed 4.0); the memory itself is still capped at 400 ticks (see EliteSurvival). */
	public static final double MAX_MEMORY_MULTIPLIER = 5.0;
	/** Bounds and default of {@code elite.ai.absolute-range-cap}, in blocks (1.4.0; 1.3.0 had a fixed 64). */
	public static final int MIN_RANGE_CAP = 16;
	public static final int MAX_RANGE_CAP = 256;
	public static final int DEFAULT_RANGE_CAP = 128;
	public static final int MIN_SCAN_INTERVAL_TICKS = 5;
	public static final int MAX_SCAN_INTERVAL_TICKS = 40;
	public static final int MAX_REACQUIRE_SECONDS = 30;
	/** Upper bound for every Elite mob-effect duration and for the refresh cap. */
	public static final int MAX_EFFECT_SECONDS = 60;

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
	 * @param absoluteRangeCap no Elite range adjustment ever reaches beyond this many blocks (unless the mob's own vanilla range
	 *     already does); mobs only act in entity-ticking chunks, so the server's simulation distance is a further real limit
	 */
	public record EliteAi(
		double acquisitionRangeMultiplier,
		double followRangeMultiplier,
		boolean improvedPursuit,
		double targetMemoryMultiplier,
		boolean advancedCombat,
		int absoluteRangeCap
	) {
		/** The five-value form used before the cap became a setting (tests and the frozen 1.3.0 oracle); the cap is the default. */
		public EliteAi(double acquisitionRangeMultiplier, double followRangeMultiplier, boolean improvedPursuit, double targetMemoryMultiplier, boolean advancedCombat) {
			this(acquisitionRangeMultiplier, followRangeMultiplier, improvedPursuit, targetMemoryMultiplier, advancedCombat, DEFAULT_RANGE_CAP);
		}
	}

	/**
	 * Elite target priority (1.3.0).
	 *
	 * @param priorityEnabled hostile mobs prefer an eligible Elite player over any other player, including one they
	 *     are already fighting
	 * @param scanIntervalTicks how often (per mob, staggered by entity id) an already-busy mob re-evaluates its target
	 * @param reacquireHiddenSeconds a mob that lost sight of an Elite target keeps tracking them by pathfinding (never
	 *     through walls) for this long; 0 turns it off
	 */
	public record EliteHunt(boolean priorityEnabled, int scanIntervalTicks, int reacquireHiddenSeconds) {
	}

	/** One attacker family's effect: probability per successful hit and base duration. */
	public record MobEffectSetting(double chance, int seconds) {
	}

	/**
	 * Effects that hostile mobs inflict on ELITE players when they genuinely damage them (1.3.0).
	 *
	 * @param maxDurationSeconds no effect from this system is ever refreshed beyond this remaining duration
	 */
	public record EliteEffects(
		boolean enabled,
		int maxDurationSeconds,
		MobEffectSetting zombie,
		MobEffectSetting spider,
		MobEffectSetting caveSpider,
		MobEffectSetting skeleton,
		MobEffectSetting drowned
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
			new EliteAi(1.25, 1.25, true, 2.0, true, DEFAULT_RANGE_CAP),
			new EliteHunt(true, 10, 8),
			new EliteEffects(true, 30, new MobEffectSetting(0.35, 8), new MobEffectSetting(0.35, 5), new MobEffectSetting(0.50, 6),
				new MobEffectSetting(0.25, 4), new MobEffectSetting(0.30, 3))
		);
	}
}
