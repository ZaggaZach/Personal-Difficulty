package com.zaggazach.personaldifficulty.logic;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfig;

public final class DamageAdjuster {
	private DamageAdjuster() {
	}

	/** The profile for a mode, or null if the mode applies no personal modifiers. */
	public static PdConfig.Profile profile(PdConfig config, PersonalDifficultyMode mode) {
		return switch (mode) {
			case EASY -> config.easy();
			case HARD -> config.hard();
			case INHERIT, NORMAL -> null;
		};
	}

	/**
	 * Precedence: void, admin, self, PvP (each an explicit exception with its own option), then a
	 * per-damage-type override, then the environmental gate, then the profile multiplier.
	 *
	 * @return the factor to apply; exactly 1.0 means "leave vanilla alone"
	 */
	public static double multiplier(PdConfig config, PersonalDifficultyMode mode, DamageContext ctx) {
		PdConfig.Profile profile = profile(config, mode);
		if (profile == null) {
			return 1.0;
		}
		if (ctx.voidDamage()) {
			return config.voidApplyProfile() ? profile.damageMultiplier() : 1.0;
		}
		if (ctx.adminDamage()) {
			return config.adminApplyProfile() ? profile.damageMultiplier() : 1.0;
		}
		if (ctx.selfInflicted()) {
			return config.selfDamageMultiplier();
		}
		if (ctx.fromOtherPlayer()) {
			return config.pvpDamageMultiplier();
		}
		Double override = profile.damageTypeOverrides().get(ctx.typeId());
		if (override != null) {
			return override;
		}
		if (ctx.environmental() && !config.environmentalApplyProfile()) {
			return 1.0;
		}
		return profile.damageMultiplier();
	}

	/**
	 * Applies the factor to damage that has already been through vanilla mitigation. Never negative,
	 * never NaN, so a multiplier can never heal or revive.
	 */
	public static float apply(float vanillaDamage, double multiplier) {
		if (multiplier == 1.0 || vanillaDamage <= 0.0F || Float.isNaN(vanillaDamage)) {
			return vanillaDamage;
		}
		if (Float.isInfinite(vanillaDamage) || vanillaDamage >= Float.MAX_VALUE / 16) {
			return vanillaDamage; // sentinel "kill" amounts are left alone
		}
		float out = (float) (vanillaDamage * multiplier);
		return Float.isNaN(out) || out < 0.0F ? 0.0F : out;
	}
}
