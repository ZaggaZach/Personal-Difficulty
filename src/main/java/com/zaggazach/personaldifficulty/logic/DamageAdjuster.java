package com.zaggazach.personaldifficulty.logic;

import com.zaggazach.personaldifficulty.PersonalDifficultyMode;
import com.zaggazach.personaldifficulty.config.PdConfig;

/**
 * Pure damage rules. Two stages exist, in this vanilla order:
 * <ol>
 * <li>armour mitigation, optionally weakened for Elite ({@link #armorEffectiveness}, {@link #applyArmor});
 * <li>after resistance and protection enchantments, the final profile multiplier
 *     ({@link #multiplier}, {@link #apply}).
 * </ol>
 * Peaceful never reaches here for ordinary damage: it is cancelled earlier (see {@link PeacefulRules}).
 */
public final class DamageAdjuster {
	private DamageAdjuster() {
	}

	/** The profile for a mode, or null if the mode has no damage/hunger profile (NORMAL, PEACEFUL). */
	public static PdConfig.Profile profile(PdConfig config, PersonalDifficultyMode mode) {
		return switch (mode) {
			case EASY -> config.easy();
			case HARD -> config.hard();
			case ELITE -> config.elite();
			case NORMAL, PEACEFUL -> null;
		};
	}

	/**
	 * True if the exceptions (void, admin, self, PvP and the environmental gate) leave this damage
	 * eligible for the profile.
	 */
	private static boolean profileApplies(PdConfig config, DamageContext ctx) {
		if (ctx.voidDamage()) {
			return config.voidApplyProfile();
		}
		if (ctx.adminDamage()) {
			return config.adminApplyProfile();
		}
		if (ctx.selfInflicted() || ctx.fromOtherPlayer()) {
			return false;
		}
		return !(ctx.environmental() && !config.environmentalApplyProfile());
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
	 * Fraction of vanilla armour mitigation that stays in force. 1.0 for everyone except Elite, and for
	 * Elite when an exception (PvP/self/void/admin) leaves the damage untouched.
	 */
	public static double armorEffectiveness(PdConfig config, PersonalDifficultyMode mode, DamageContext ctx) {
		if (mode != PersonalDifficultyMode.ELITE || !profileApplies(config, ctx)) {
			return 1.0;
		}
		return config.eliteArmorEffectiveness();
	}

	/**
	 * Weakens armour's own mitigation: vanilla removed {@code original - afterArmor}; keep only
	 * {@code effectiveness} of that. Never returns less than vanilla's result or more than the input,
	 * so it cannot heal and cannot exceed the unmitigated damage.
	 */
	public static float applyArmor(float original, float afterArmor, double effectiveness) {
		if (effectiveness >= 1.0 || Float.isNaN(original) || Float.isNaN(afterArmor) || afterArmor >= original || original <= 0.0F) {
			return afterArmor;
		}
		if (Float.isInfinite(original) || original >= Float.MAX_VALUE / 16) {
			return afterArmor;
		}
		float out = (float) (original - (original - afterArmor) * Math.max(0.0, effectiveness));
		return Math.min(original, Math.max(afterArmor, out));
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
