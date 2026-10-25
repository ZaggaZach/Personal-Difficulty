package com.zaggazach.personaldifficulty.logic;

import com.zaggazach.personaldifficulty.config.PdConfig;

/**
 * Minecraft-free arithmetic for the Elite survival mechanics (slower natural regeneration, and the target-specific
 * AI adjustments). Everything is a pure function of the vanilla value and the configuration, so applying it twice,
 * or after a configuration reload, can never accumulate.
 */
public final class EliteSurvival {
	/** No Elite adjustment ever makes a mob act beyond this absolute distance, whatever the config says. */
	public static final double ABSOLUTE_RANGE_CAP = 64.0;
	/** Hard ceiling for how long a mob may remember an unseen Elite target (20 seconds). */
	public static final int MAX_MEMORY_TICKS = 400;
	/** Vanilla melee mobs wait up to 20 ticks between "should I start chasing?" checks; Elite targets get 5. */
	public static final long ELITE_MELEE_RECHECK_TICKS = 5L;

	private EliteSurvival() {
	}

	/**
	 * Natural regeneration happens when a per-player counter reaches an interval (10 ticks when saturated, 80 ticks
	 * when only well fed). Stretching the interval by {@code 1/multiplier} slows every heal cycle by the same factor while
	 * leaving the amount per cycle, and the exhaustion each heal costs, exactly as in vanilla. Intervals are whole
	 * ticks, so the realised rate is {@code vanilla / round(vanilla / multiplier)}: exact for 0.5, 0.25, 0.2, 0.1 and
	 * within a few percent elsewhere (coarsest for the 10-tick interval near 1.0).
	 */
	public static int regenInterval(int vanillaInterval, double multiplier) {
		if (!(multiplier > 0.0 && multiplier < 1.0) || vanillaInterval <= 0) {
			return vanillaInterval;
		}
		return Math.max(vanillaInterval, (int) Math.round(vanillaInterval / multiplier));
	}

	/** The rate multiplier actually realised for a vanilla interval (for documentation and tests). */
	public static double realisedRegenRate(int vanillaInterval, double multiplier) {
		return vanillaInterval / (double) regenInterval(vanillaInterval, multiplier);
	}

	/** {@code base * multiplier}, never below {@code base}, never above {@link #ABSOLUTE_RANGE_CAP} unless {@code base} already is. */
	public static double cappedRange(double base, double multiplier) {
		if (!(multiplier > 1.0) || !Double.isFinite(multiplier) || !(base > 0.0) || !Double.isFinite(base)) {
			return base;
		}
		return Math.max(base, Math.min(base * multiplier, ABSOLUTE_RANGE_CAP));
	}

	/**
	 * Factor applied both to the distance at which a pursuit is abandoned and to the path-search length. They share one
	 * factor so that any distance at which a target is kept or acquired is also a distance the mob can actually path across.
	 */
	public static double pursuitFactor(PdConfig.EliteAi ai) {
		return Math.max(ai.followRangeMultiplier(), ai.acquisitionRangeMultiplier());
	}

	/** Ticks a mob remembers a target it cannot see. */
	public static int memoryTicks(int vanillaTicks, double multiplier) {
		if (!(multiplier > 1.0) || !Double.isFinite(multiplier) || vanillaTicks <= 0) {
			return vanillaTicks;
		}
		return Math.max(vanillaTicks, Math.min(MAX_MEMORY_TICKS, (int) Math.round(vanillaTicks * multiplier)));
	}

	/** A skeleton's bow cadence against an Elite player: the Hard-difficulty interval, never slower than it already was. */
	public static int bowInterval(int currentInterval, int hardInterval) {
		return hardInterval > 0 ? Math.min(currentInterval, hardInterval) : currentInterval;
	}
}
