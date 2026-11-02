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
	EliteEffects eliteEffects,
	EliteAlert eliteAlert,
	EliteSkeleton eliteSkeleton,
	EliteEnderman eliteEnderman,
	EliteMobs eliteMobs
) {
	/** The form used before the per-mob Elite settings existed (zombie doors, creeper fuse, pillager crossbow); those are the defaults. */
	public PdConfig(Profile easy, Profile hard, Profile elite, double easyRangedMinUncertainty, double eliteRangedMaxUncertainty,
		double eliteArmorEffectiveness, double eliteRegenMultiplier, double pvpDamageMultiplier, double selfDamageMultiplier,
		boolean environmentalApplyProfile, boolean voidApplyProfile, boolean adminApplyProfile, RangedMobs rangedMobs, EliteAi eliteAi,
		EliteHunt eliteHunt, EliteEffects eliteEffects, EliteAlert eliteAlert, EliteSkeleton eliteSkeleton, EliteEnderman eliteEnderman) {
		this(easy, hard, elite, easyRangedMinUncertainty, eliteRangedMaxUncertainty, eliteArmorEffectiveness, eliteRegenMultiplier,
			pvpDamageMultiplier, selfDamageMultiplier, environmentalApplyProfile, voidApplyProfile, adminApplyProfile, rangedMobs, eliteAi,
			eliteHunt, eliteEffects, eliteAlert, eliteSkeleton, eliteEnderman, EliteMobs.DEFAULTS);
	}

	/** The form used before the Enderman Blindside settings existed; those settings are the defaults. */
	public PdConfig(Profile easy, Profile hard, Profile elite, double easyRangedMinUncertainty, double eliteRangedMaxUncertainty,
		double eliteArmorEffectiveness, double eliteRegenMultiplier, double pvpDamageMultiplier, double selfDamageMultiplier,
		boolean environmentalApplyProfile, boolean voidApplyProfile, boolean adminApplyProfile, RangedMobs rangedMobs, EliteAi eliteAi,
		EliteHunt eliteHunt, EliteEffects eliteEffects, EliteAlert eliteAlert, EliteSkeleton eliteSkeleton) {
		this(easy, hard, elite, easyRangedMinUncertainty, eliteRangedMaxUncertainty, eliteArmorEffectiveness, eliteRegenMultiplier,
			pvpDamageMultiplier, selfDamageMultiplier, environmentalApplyProfile, voidApplyProfile, adminApplyProfile, rangedMobs, eliteAi,
			eliteHunt, eliteEffects, eliteAlert, eliteSkeleton, EliteEnderman.DEFAULTS);
	}

	/** The form used before the Elite skeleton settings existed (Phase 5 and earlier); those settings are the defaults. */
	public PdConfig(Profile easy, Profile hard, Profile elite, double easyRangedMinUncertainty, double eliteRangedMaxUncertainty,
		double eliteArmorEffectiveness, double eliteRegenMultiplier, double pvpDamageMultiplier, double selfDamageMultiplier,
		boolean environmentalApplyProfile, boolean voidApplyProfile, boolean adminApplyProfile, RangedMobs rangedMobs, EliteAi eliteAi,
		EliteHunt eliteHunt, EliteEffects eliteEffects, EliteAlert eliteAlert) {
		this(easy, hard, elite, easyRangedMinUncertainty, eliteRangedMaxUncertainty, eliteArmorEffectiveness, eliteRegenMultiplier,
			pvpDamageMultiplier, selfDamageMultiplier, environmentalApplyProfile, voidApplyProfile, adminApplyProfile, rangedMobs, eliteAi,
			eliteHunt, eliteEffects, eliteAlert, EliteSkeleton.DEFAULTS);
	}

	/** The form used before the alert network existed (tests and the frozen 1.3.0 oracle); the alert settings are the defaults. */
	public PdConfig(Profile easy, Profile hard, Profile elite, double easyRangedMinUncertainty, double eliteRangedMaxUncertainty,
		double eliteArmorEffectiveness, double eliteRegenMultiplier, double pvpDamageMultiplier, double selfDamageMultiplier,
		boolean environmentalApplyProfile, boolean voidApplyProfile, boolean adminApplyProfile, RangedMobs rangedMobs, EliteAi eliteAi,
		EliteHunt eliteHunt, EliteEffects eliteEffects) {
		this(easy, hard, elite, easyRangedMinUncertainty, eliteRangedMaxUncertainty, eliteArmorEffectiveness, eliteRegenMultiplier,
			pvpDamageMultiplier, selfDamageMultiplier, environmentalApplyProfile, voidApplyProfile, adminApplyProfile, rangedMobs, eliteAi,
			eliteHunt, eliteEffects, EliteAlert.DEFAULTS);
	}

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
	/** Bounds of the alert network settings (1.4.0, Phase 5). */
	public static final int MIN_ALERT_RADIUS = 8;
	public static final int MAX_ALERT_RADIUS = 128;
	public static final int MAX_ALERT_HOPS = 4;
	public static final int MIN_ALERT_MEMORY_SECONDS = 5;
	public static final int MAX_ALERT_MEMORY_SECONDS = 300;
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

	/**
	 * The alert network and last-known-position search (1.4.0, Phase 5). A mob that SEES an Elite player records where and when;
	 * hostile mobs nearby learn of that sighting (a few hops at most) and, if they have no target, walk to that remembered point
	 * with their ordinary pathfinding. Nobody learns where the player is now: the point goes stale as soon as they move.
	 *
	 * @param enabled master switch (off by default until the Phase 5 performance measurement)
	 * @param radius blocks within which a mob that knows of a sighting passes it on
	 * @param hops how many times a sighting may be passed on (0 = only the mob that saw it searches)
	 * @param memorySeconds how long a sighting is kept
	 */
	public record EliteAlert(boolean enabled, int radius, int hops, int memorySeconds) {
		public static final EliteAlert DEFAULTS = new EliteAlert(true, 64, 2, 60);
	}

	/** Bounds of the Elite skeleton settings (1.4.0, Phase 6). */
	public static final double MAX_ARROW_SPEED_MULTIPLIER = 3.0;
	public static final double MIN_ARROW_DAMAGE_MULTIPLIER = 0.1;
	public static final double MAX_ARROW_DAMAGE_MULTIPLIER = 3.0;
	/** Vanilla skeletons shoot from 15 blocks; the Elite range never goes below that. */
	public static final int VANILLA_BOW_RANGE = 15;
	public static final int MAX_BOW_RANGE = 48;
	/** The countdown after a shot; vanilla is 40 (20 on Hard). A shot also needs vanilla's fixed 20-tick draw. */
	public static final int MIN_FIRING_INTERVAL_TICKS = 10;
	public static final int MAX_FIRING_INTERVAL_TICKS = 60;

	/**
	 * Elite skeletons (1.4.0, Phase 6, plan 6.4): applied to a skeleton-family shot only while its target is an Elite player.
	 * Speed and damage are separate controls with no compensation between them: vanilla arrow damage already grows with the
	 * speed at impact, and what that does in practice is measured, not assumed.
	 *
	 * @param enabled master switch for everything below
	 * @param arrowSpeedMultiplier launch speed relative to vanilla's 1.6
	 * @param arrowDamageMultiplier scale on the arrow's base damage (vanilla's speed-at-impact rule still applies on top)
	 * @param predictionStrength 0 = vanilla aim, 1 = aim fully at where the player will be (from their movement)
	 * @param maxRange blocks within which the skeleton starts shooting (vanilla 15)
	 * @param firingIntervalTicks the countdown after a shot (vanilla 40, Hard 20); the 20-tick draw is never shortened
	 */
	public record EliteSkeleton(boolean enabled, double arrowSpeedMultiplier, double arrowDamageMultiplier, double predictionStrength,
		int maxRange, int firingIntervalTicks) {
		public static final EliteSkeleton DEFAULTS = new EliteSkeleton(true, 1.5, 1.0, 1.0, 24, 10);
	}

	/** Bounds of {@code elite.enderman.blindside-distance}, in blocks behind the player. */
	public static final int MIN_BLINDSIDE_DISTANCE = 2;
	public static final int MAX_BLINDSIDE_DISTANCE = 6;

	/**
	 * Enderman Blindside (1.4.0, Phase 6, plan 6.5): an Enderman that is already angry at an Elite player (or was just hit by their
	 * projectile) may land behind them instead of where vanilla would put it. Nothing makes an Enderman angry that vanilla would not.
	 *
	 * @param enabled master switch
	 * @param chance probability that a qualifying teleport (closing the distance, or dodging a projectile) goes behind the player
	 * @param distance blocks behind the player (plus or minus one, at random)
	 */
	public record EliteEnderman(boolean enabled, double chance, int distance) {
		public static final EliteEnderman DEFAULTS = new EliteEnderman(true, 0.5, 3);
	}

	/** Bounds of the creeper fuse against Elite players, in ticks (vanilla 30 = 1.5 s); never below 15 (0.75 s). */
	public static final int MIN_CREEPER_FUSE_TICKS = 15;
	public static final int VANILLA_CREEPER_FUSE_TICKS = 30;
	/** Bounds of the pillager's wait after charging, in ticks (vanilla 20 to 39). */
	public static final int MAX_CROSSBOW_DELAY_TICKS = 40;

	/**
	 * Per-mob Elite behaviour (1.4.0, Phase 6, plan 6.6), each only while the mob's target is an Elite player.
	 *
	 * @param zombieBreakDoors zombies (and husks, zombie villagers) break wooden doors on any world difficulty (vanilla: Hard only)
	 * @param creeperShortFuse creepers explode after {@code creeperFuseTicks} instead of vanilla's 30
	 * @param creeperFuseTicks the shortened fuse
	 * @param pillagerFastCrossbow pillagers fire sooner after charging their crossbow
	 * @param pillagerAttackDelayTicks the wait after charging (vanilla 20 to 39 ticks); the charge itself is unchanged
	 */
	public record EliteMobs(boolean zombieBreakDoors, boolean creeperShortFuse, int creeperFuseTicks, boolean pillagerFastCrossbow,
		int pillagerAttackDelayTicks) {
		/** Door-breaking and the creeper fuse ship OFF (plan C2); the pillager change ships on. */
		public static final EliteMobs DEFAULTS = new EliteMobs(false, false, 20, true, 5);
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
			new EliteAi(3.0, 1.25, true, 5.0, true, DEFAULT_RANGE_CAP),
			new EliteHunt(true, 10, 8),
			new EliteEffects(true, 30, new MobEffectSetting(0.35, 8), new MobEffectSetting(0.35, 5), new MobEffectSetting(0.50, 6),
				new MobEffectSetting(0.25, 4), new MobEffectSetting(0.30, 3)),
			EliteAlert.DEFAULTS,
			EliteSkeleton.DEFAULTS,
			EliteEnderman.DEFAULTS,
			EliteMobs.DEFAULTS
		);
	}
}
