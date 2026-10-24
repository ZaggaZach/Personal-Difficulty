package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Personal incoming-damage scaling.
 *
 * <p>Why a mixin: Fabric API's {@code ServerLivingEntityEvents} can only cancel damage
 * (ALLOW_DAMAGE) or observe it (AFTER_DAMAGE); there is no event that changes the amount.
 *
 * <p>Why here: {@code getDamageAfterMagicAbsorb} is the last step of vanilla mitigation inside
 * {@code actuallyHurt} (armour, then resistance, then protection enchantments) and runs exactly
 * once per applied hit, before absorption hearts. Scaling its result means (a) the factor is an
 * exact fraction of what vanilla would deal, (b) invulnerability-frame logic in
 * {@code hurtServer}, which compares RAW damage, is untouched, (c) shields, totems and death
 * handling are untouched, and (d) the factor can never be applied twice. The world's difficulty
 * scaling happens earlier in {@code Player.hurtServer} and is not repeated.
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityDamageMixin {
	@ModifyReturnValue(method = "getDamageAfterMagicAbsorb", at = @At("RETURN"))
	private float personaldifficulty$scalePersonalDamage(float mitigated, DamageSource source, float original) {
		if ((Object) this instanceof ServerPlayer player) {
			return PdHooks.incomingDamage(player, source, mitigated);
		}
		return mitigated;
	}
}
