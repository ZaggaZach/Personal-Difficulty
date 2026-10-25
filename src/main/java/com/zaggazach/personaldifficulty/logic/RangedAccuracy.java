package com.zaggazach.personaldifficulty.logic;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfig;

public final class RangedAccuracy {
	public enum Family {
		SKELETON, ILLUSIONER, DROWNED, CROSSBOW, PIGLIN, WITCH
	}

	private RangedAccuracy() {
	}

	public static boolean familyEnabled(PdConfig config, Family family) {
		return switch (family) {
			case SKELETON -> config.rangedMobs().skeletons();
			case ILLUSIONER -> config.rangedMobs().illusioners();
			case DROWNED -> config.rangedMobs().drowned();
			case CROSSBOW -> config.rangedMobs().crossbowMobs();
			case PIGLIN -> config.rangedMobs().piglins();
			case WITCH -> config.rangedMobs().witches();
		};
	}

	/**
	 * Easy widens (never tightens) and Elite narrows (never widens) the spread the shooter would have
	 * used. Only the vanilla "uncertainty" argument changes; projectile speed, damage and aim vector
	 * are untouched.
	 *
	 * @param vanillaUncertainty what the mob would have passed to Projectile.shoot
	 * @param targetMode the targeted player's mode, or null if the target is not a player
	 */
	public static float adjust(PdConfig config, Family family, PersonalDifficultyMode targetMode, float vanillaUncertainty) {
		if (targetMode == null || !familyEnabled(config, family)) {
			return vanillaUncertainty;
		}
		return switch (targetMode) {
			case EASY -> Math.max(vanillaUncertainty, (float) config.easyRangedMinUncertainty());
			case ELITE -> Math.min(vanillaUncertainty, (float) config.eliteRangedMaxUncertainty());
			case NORMAL, HARD, PEACEFUL -> vanillaUncertainty;
		};
	}
}
