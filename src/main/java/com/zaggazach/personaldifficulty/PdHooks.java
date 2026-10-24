package com.zaggazach.personaldifficulty;

import com.zaggazach.personaldifficulty.logic.DamageAdjuster;
import com.zaggazach.personaldifficulty.logic.DamageContext;
import com.zaggazach.personaldifficulty.logic.HungerAdjuster;
import com.zaggazach.personaldifficulty.logic.RangedAccuracy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * Thin bridge from mixins to the Minecraft-free logic. Every method returns its input unchanged
 * unless a personal profile genuinely applies, so non-participants see pure vanilla behaviour.
 */
public final class PdHooks {
	private PdHooks() {
	}

	/** Called with damage that has already been through armour, resistance and enchantments. */
	public static float incomingDamage(ServerPlayer victim, DamageSource source, float vanillaDamage) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			return vanillaDamage;
		}
		PersonalDifficultyMode mode = rt.modeOf(victim.getUUID());
		if (!mode.hasProfile()) {
			return vanillaDamage;
		}
		double factor = DamageAdjuster.multiplier(rt.config(), mode, describe(victim, source));
		return DamageAdjuster.apply(vanillaDamage, factor);
	}

	public static DamageContext describe(Player victim, DamageSource source) {
		Entity attacker = source.getEntity();
		boolean isVoid = source.is(DamageTypes.FELL_OUT_OF_WORLD);
		boolean admin = !isVoid && (source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
		boolean self = attacker == victim;
		boolean otherPlayer = attacker instanceof Player && !self;
		boolean environmental = attacker == null && source.getDirectEntity() == null;
		String typeId = source.typeHolder().unwrapKey().map(k -> k.identifier().toString()).orElse("unknown:unknown");
		return new DamageContext(typeId, isVoid, admin, self, otherPlayer, environmental);
	}

	public static float exhaustion(ServerPlayer player, float amount) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null) {
			return amount;
		}
		return HungerAdjuster.adjustExhaustion(rt.config(), rt.modeOf(player.getUUID()), amount);
	}

	/** Only a player target on Easy ever changes the shooter's spread; any other target is vanilla. */
	public static float rangedUncertainty(Mob shooter, LivingEntity target, RangedAccuracy.Family family, float vanilla) {
		PdRuntime rt = PdRuntime.get();
		if (rt == null || !(target instanceof ServerPlayer player)) {
			return vanilla;
		}
		return RangedAccuracy.adjust(rt.config(), family, rt.modeOf(player.getUUID()), vanilla);
	}
}
