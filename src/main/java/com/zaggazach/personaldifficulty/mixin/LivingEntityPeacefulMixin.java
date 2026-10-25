package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Peaceful targeting and effect protection.
 *
 * <ul>
 * <li>{@code canAttack} is the gate every vanilla target goal and brain sensor goes through (via
 *     {@code TargetingConditions} / {@code StartAttacking}); returning false for a Peaceful player stops
 *     mobs acquiring or keeping them as a target, and because it is re-evaluated continuously a cleared
 *     target cannot restart. Classes that override it without calling super have their own mixins.
 * <li>{@code addEffect(effect, source)} is the single funnel for potions, clouds and attacks. Harmful
 *     effects are refused for a Peaceful victim, and for any player victim when the attributable source
 *     is a Peaceful player. No public Fabric event exists for this.
 * </ul>
 */
@Mixin(LivingEntity.class)
abstract class LivingEntityPeacefulMixin {
	@ModifyReturnValue(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("RETURN"))
	private boolean personaldifficulty$noTargetingPeaceful(boolean original, LivingEntity target) {
		return original && !PdHooks.blocksTargeting((Entity) (Object) this, target);
	}

	@Inject(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
	private void personaldifficulty$noHarmfulEffects(MobEffectInstance effect, @Nullable Entity source, CallbackInfoReturnable<Boolean> cir) {
		if (PdHooks.blocksEffect((LivingEntity) (Object) this, effect, source)) {
			cir.setReturnValue(false);
		}
	}
}
