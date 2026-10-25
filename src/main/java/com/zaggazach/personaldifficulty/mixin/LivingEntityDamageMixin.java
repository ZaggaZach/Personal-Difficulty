package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
 * <p>Two narrow hooks, each called exactly once per applied hit inside {@code actuallyHurt}:
 * <ol>
 * <li>{@code getDamageAfterArmorAbsorb}: Elite only, scales how much of the hit armour absorbs. A pure
 *     multiplier is neutralised by endgame armour, so Elite keeps the armour step but at reduced
 *     effectiveness (documented in docs/DESIGN.md).
 * <li>{@code getDamageAfterMagicAbsorb}: the last step of vanilla mitigation (armour, then resistance,
 *     then protection enchantments). The profile multiplier is applied to its result, before absorption
 *     hearts. This keeps invulnerability frames (compared on RAW damage in {@code hurtServer}), shields,
 *     totems and death handling untouched, and the factor can never be applied twice. The world's
 *     difficulty scaling happens earlier in {@code Player.hurtServer} and is not repeated.
 * </ol>
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityDamageMixin {
	/**
	 * {@code @WrapMethod}, not a RETURN injection: vanilla reassigns its {@code damage} parameter, so at
	 * RETURN the "original" argument would already equal the post-armour value and the weakening would
	 * silently do nothing (caught by the in-server armour-tier test).
	 */
	@WrapMethod(method = "getDamageAfterArmorAbsorb")
	private float personaldifficulty$weakenArmour(DamageSource source, float damage, Operation<Float> original) {
		float afterArmor = original.call(source, damage);
		if ((Object) this instanceof ServerPlayer player) {
			return PdHooks.armorMitigated(player, source, damage, afterArmor);
		}
		return afterArmor;
	}

	@ModifyReturnValue(method = "getDamageAfterMagicAbsorb", at = @At("RETURN"))
	private float personaldifficulty$scalePersonalDamage(float mitigated, DamageSource source) {
		if ((Object) this instanceof ServerPlayer player) {
			return PdHooks.incomingDamage(player, source, mitigated);
		}
		return mitigated;
	}
}
