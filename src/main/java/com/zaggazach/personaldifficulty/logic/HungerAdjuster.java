package com.zaggazach.personaldifficulty.logic;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfig;

public final class HungerAdjuster {
	private HungerAdjuster() {
	}

	/**
	 * Scales one exhaustion amount at its source, so fractional values are preserved exactly (the
	 * vanilla exhaustion accumulator in FoodData keeps the float remainder). Peaceful accrues none.
	 */
	public static float adjustExhaustion(PdConfig config, PersonalDifficultyMode mode, float amount) {
		if (amount <= 0.0F || Float.isNaN(amount)) {
			return amount;
		}
		if (mode == PersonalDifficultyMode.PEACEFUL) {
			return 0.0F;
		}
		PdConfig.Profile profile = DamageAdjuster.profile(config, mode);
		if (profile == null) {
			return amount;
		}
		return (float) (amount * profile.hungerMultiplier());
	}
}
