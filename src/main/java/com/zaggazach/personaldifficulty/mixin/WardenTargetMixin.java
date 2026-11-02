package com.zaggazach.personaldifficulty.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.zaggazach.personaldifficulty.PdHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Warden decides targets (and anger) in {@code canTargetEntity} and its {@code canAttack} does not call super. */
@Mixin(Warden.class)
abstract class WardenTargetMixin {
	@ModifyReturnValue(method = "canTargetEntity", at = @At("RETURN"))
	private boolean personaldifficulty$noTargetingPeaceful(boolean original, Entity entity) {
		return original && !PdHooks.blocksTargeting((Entity) (Object) this, entity);
	}

	/**
	 * 1.4.0 (Phase 7): every anger increase goes through this method (vibrations, sniffing, roaring, being hurt). Toward an Elite
	 * player the amount is multiplied when {@code elite.warden.enabled} is on; nothing else about the Warden changes.
	 */
	@ModifyVariable(method = "increaseAngerAt(Lnet/minecraft/world/entity/Entity;IZ)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int personaldifficulty$eliteAnger(int amount, @Local(argsOnly = true) Entity entity) {
		return PdHooks.wardenAnger(entity, amount);
	}
}
